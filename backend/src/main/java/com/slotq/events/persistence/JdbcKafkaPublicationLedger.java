package com.slotq.events.persistence;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventId;
import com.slotq.events.application.KafkaPublicationPolicy;
import com.slotq.events.application.StoredEvent;
import com.slotq.observability.ProductTelemetry;
import com.slotq.tenancy.domain.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Publication ledger and discovery have no DB-delivery cursor, target, or receipt identity. */
@Component
public final class JdbcKafkaPublicationLedger {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;

    public JdbcKafkaPublicationLedger(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
    }

    public int discover(String destination, int batchSize) {
        return execute(() -> {
            var cursorRow = db.queryForMap(
                "SELECT boundary_sequence,destination FROM event_kafka_discovery WHERE singleton_id = 1 FOR UPDATE");
            long cursor = ((Number) cursorRow.get("boundary_sequence")).longValue();
            String boundDestination = (String) cursorRow.get("destination");
            if (boundDestination == null) {
                one(db.update("UPDATE event_kafka_discovery SET destination=? WHERE singleton_id=1", destination));
            } else if (!boundDestination.equals(destination)) {
                throw new IllegalStateException("Kafka publication destination changed without cutover");
            }
            List<Long> positions = db.queryForList("""
                SELECT boundary_sequence FROM event_records WHERE boundary_sequence > ?
                 ORDER BY boundary_sequence LIMIT ?
                """, Long.class, cursor, batchSize);
            if (positions.isEmpty()) return 0;
            long through = positions.getLast();
            int inserted = db.update("""
                INSERT INTO event_kafka_publications
                    (tenant_id, event_id, destination, state, discovered_boundary, next_attempt_at)
                SELECT e.tenant_id, e.event_id, ?, 'PENDING', e.boundary_sequence, UTC_TIMESTAMP(6)
                  FROM event_records e
                 WHERE e.boundary_sequence > ? AND e.boundary_sequence <= ?
                   AND ((e.event_type = 'booking.capacity-released' AND e.schema_version = 1)
                     OR (e.event_type = 'waitlist.promotion-requested' AND e.schema_version = 1))
                   AND EXISTS (SELECT 1 FROM event_registrations r
                        WHERE r.consumer_id = 'waitlist.promotion'
                          AND r.event_type = e.event_type AND r.schema_version = e.schema_version
                          AND e.boundary_sequence > r.activation_boundary
                          AND (r.deactivation_boundary IS NULL OR e.boundary_sequence < r.deactivation_boundary))
                   AND NOT EXISTS (SELECT 1 FROM event_kafka_publications p
                        WHERE p.tenant_id = e.tenant_id AND p.event_id = e.event_id AND p.destination = ?)
                """, destination, cursor, through, destination);
            one(db.update("UPDATE event_kafka_discovery SET boundary_sequence = ? WHERE singleton_id = 1", through));
            return inserted;
        });
    }

    public List<Key> candidates(int batchSize) {
        return execute(() -> db.query("""
            SELECT tenant_id, event_id, destination FROM event_kafka_publications
             WHERE (state = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6))
                OR (state = 'PROCESSING' AND lease_until <= UTC_TIMESTAMP(6))
             ORDER BY COALESCE(next_attempt_at, lease_until), event_id LIMIT ?
            """, (row, n) -> key(row), batchSize));
    }

    public Optional<Claim> claim(Key key, KafkaPublicationPolicy policy) {
        return execute(() -> {
            Optional<Snapshot> found = lock(key);
            Instant now = now(); // fresh DB time after the blocking row lock
            if (found.isEmpty()) return Optional.empty();
            Snapshot row = found.get();
            boolean due = row.state().equals("PENDING") && !row.nextAttemptAt().isAfter(now);
            boolean expired = row.state().equals("PROCESSING") && !row.leaseUntil().isAfter(now);
            if (!due && !expired) return Optional.empty();
            if (row.attempts() >= policy.maxAttempts()) {
                one(db.update("""
                    UPDATE event_kafka_publications SET state='DEAD', lease_until=NULL, next_attempt_at=NULL,
                        failure_code='CRASH_EXHAUSTED', updated_at=?
                     WHERE tenant_id=? AND event_id=? AND destination=? AND fencing_token=?
                    """, utc(now), bytes(key.tenantId()), bytes(key.eventId()), key.destination(), row.token()));
                return Optional.empty();
            }
            Instant leaseUntil = now.plus(policy.lease());
            one(db.update("""
                UPDATE event_kafka_publications SET state='PROCESSING', cycle_attempts=cycle_attempts+1,
                    lifetime_attempts=lifetime_attempts+1, fencing_token=fencing_token+1,
                    lease_until=?, next_attempt_at=NULL, updated_at=?
                 WHERE tenant_id=? AND event_id=? AND destination=? AND fencing_token=?
                """, utc(leaseUntil), utc(now), bytes(key.tenantId()), bytes(key.eventId()),
                key.destination(), row.token()));
            return Optional.of(new Claim(key, row.token() + 1, row.attempts() + 1));
        });
    }

    public Publication load(Claim claim) {
        return execute(() -> db.query("""
            SELECT e.*, p.state, p.fencing_token, p.lease_until
              FROM event_kafka_publications p JOIN event_records e
                ON e.tenant_id=p.tenant_id AND e.event_id=p.event_id
             WHERE p.tenant_id=? AND p.event_id=? AND p.destination=?
            """, (row, n) -> {
                if (!row.getString("state").equals("PROCESSING")
                    || row.getLong("fencing_token") != claim.token()) throw new OwnershipLost();
                StoredEvent stored = JdbcEventRecordStore.storedEvent(row, n);
                return new Publication(stored, new ProductTelemetry.Origin(row.getString("origin_request_id"),
                    row.getString("origin_trace_id"), row.getString("origin_span_id")));
            }, scope(claim.key())).stream().findFirst().orElseThrow(OwnershipLost::new));
    }

    public void published(Claim claim, int partition, long offset) {
        execute(() -> {
            one(db.update("""
                UPDATE event_kafka_publications SET state='PUBLISHED', lease_until=NULL,
                    failure_code=NULL, ack_partition=?, ack_offset=?, ack_at=UTC_TIMESTAMP(6),
                    updated_at=UTC_TIMESTAMP(6)
                 WHERE tenant_id=? AND event_id=? AND destination=?
                   AND state='PROCESSING' AND fencing_token=? AND lease_until>UTC_TIMESTAMP(6)
                """, partition, offset, bytes(claim.key().tenantId()), bytes(claim.key().eventId()),
                claim.key().destination(), claim.token()));
            return null;
        });
    }

    public void failed(Claim claim, String code, boolean retryable, KafkaPublicationPolicy policy) {
        execute(() -> {
            Optional<Snapshot> found = lock(claim.key());
            Instant now = now();
            if (found.isEmpty() || !found.get().state().equals("PROCESSING")
                || found.get().token() != claim.token() || !found.get().leaseUntil().isAfter(now)) {
                throw new OwnershipLost();
            }
            boolean retry = retryable && found.get().attempts() < policy.maxAttempts();
            one(db.update("""
                UPDATE event_kafka_publications SET state=?, lease_until=NULL, next_attempt_at=?,
                    failure_code=?, updated_at=?
                 WHERE tenant_id=? AND event_id=? AND destination=?
                   AND state='PROCESSING' AND fencing_token=? AND lease_until>UTC_TIMESTAMP(6)
                """, retry ? "PENDING" : "DEAD",
                retry ? utc(now.plus(policy.retryDelay(found.get().attempts()))) : null, code, utc(now),
                bytes(claim.key().tenantId()), bytes(claim.key().eventId()), claim.key().destination(), claim.token()));
            return null;
        });
    }

    public Inventory inventory() {
        return execute(() -> db.queryForObject("""
            SELECT COUNT(*) AS pending_count,
                   TIMESTAMPDIFF(MICROSECOND, MIN(e.recorded_at), UTC_TIMESTAMP(6)) / 1000000.0 AS oldest_seconds
              FROM event_kafka_publications p JOIN event_records e
                ON e.tenant_id=p.tenant_id AND e.event_id=p.event_id
             WHERE p.state <> 'PUBLISHED'
            """, (row, n) -> new Inventory(row.getLong("pending_count"),
                row.getObject("oldest_seconds") == null ? null : row.getDouble("oldest_seconds"))));
    }

    /** A changed topic identity or log start beyond an unhanded-off publication is a hard gap. */
    public void verifyTopic(String destination, String topicId, java.util.Map<Integer, Long> logStarts) {
        execute(() -> {
            List<String> known = db.queryForList("""
                SELECT topic_id FROM event_kafka_topic_state WHERE destination=? FOR UPDATE
                """, String.class, destination);
            if (known.isEmpty()) {
                one(db.update("INSERT INTO event_kafka_topic_state(destination,topic_id) VALUES (?,?)",
                    destination, topicId));
            } else if (!known.getFirst().equals(topicId)) {
                throw new IllegalStateException("Kafka topic identity changed");
            }
            for (var entry : logStarts.entrySet()) {
                Long oldestNeeded = db.queryForObject("""
                    SELECT MIN(ack_offset) FROM event_kafka_publications
                     WHERE destination=? AND state='PUBLISHED' AND ack_partition=?
                    """, Long.class, destination, entry.getKey());
                if (oldestNeeded != null && entry.getValue() > oldestNeeded)
                    throw new IllegalStateException("Kafka log-start gap before durable intake");
            }
            return null;
        });
    }

    private Optional<Snapshot> lock(Key key) {
        return db.query("""
            SELECT * FROM event_kafka_publications
             WHERE tenant_id=? AND event_id=? AND destination=? FOR UPDATE
            """, (row, n) -> new Snapshot(row.getString("state"), row.getInt("cycle_attempts"),
                row.getLong("fencing_token"), time(row, "lease_until"), time(row, "next_attempt_at")),
            scope(key)).stream().findFirst();
    }
    private Instant now() { return db.queryForObject("SELECT UTC_TIMESTAMP(6)",
        (row, n) -> row.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC)); }
    private <T> T execute(java.util.function.Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Publication work must start outside a caller transaction");
        return transaction.execute(status -> action.get());
    }
    private static Object[] scope(Key key) { return new Object[]{bytes(key.tenantId()), bytes(key.eventId()), key.destination()}; }
    private static Key key(ResultSet row) throws SQLException {
        return new Key(uuid(row.getBytes("tenant_id")), uuid(row.getBytes("event_id")), row.getString("destination"));
    }
    private static Instant time(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    private static LocalDateTime utc(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value); return new UUID(buffer.getLong(), buffer.getLong());
    }
    private static void one(int rows) { if (rows != 1) throw new OwnershipLost(); }

    public record Key(UUID tenantId, UUID eventId, String destination) { }
    public record Claim(Key key, long token, int attempt) { }
    public record Publication(StoredEvent event, ProductTelemetry.Origin origin) { }
    public record Inventory(long pending, Double oldestSeconds) { }
    private record Snapshot(String state, int attempts, long token, Instant leaseUntil, Instant nextAttemptAt) { }
    public static final class OwnershipLost extends IllegalStateException { }
}
