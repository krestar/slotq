package com.slotq.events.persistence;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryClaim;
import com.slotq.events.application.DeliveryFailure;
import com.slotq.events.application.DeliveryKey;
import com.slotq.events.application.DeliveryPolicy;
import com.slotq.events.application.DeliverySnapshot;
import com.slotq.events.application.EventDeliveryStore;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventOwnershipLostException;
import com.slotq.events.application.StoredEvent;
import com.slotq.tenancy.domain.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public final class JdbcEventDeliveryStore implements EventDeliveryStore {
    private final JdbcTemplate db;

    public JdbcEventDeliveryStore(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void configureTimeouts(DeliveryPolicy policy) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Delivery persistence requires a writable Product transaction");
        }
        Connection connection = DataSourceUtils.getConnection(db.getDataSource());
        try {
            int oldNetworkTimeout = connection.getNetworkTimeout();
            int oldLockTimeout = db.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
            // Restore session state after completion, before the Product manager returns the connection.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    try {
                        try (var statement = connection.createStatement()) {
                            statement.setQueryTimeout(Math.toIntExact(policy.effectTimeout().toSeconds()));
                            statement.execute("SET SESSION innodb_lock_wait_timeout = " + oldLockTimeout);
                        }
                        connection.setNetworkTimeout(Runnable::run, oldNetworkTimeout);
                    } catch (SQLException failure) {
                        // A connection whose session could not be restored must not re-enter the pool.
                        try {
                            connection.abort(Runnable::run);
                        } catch (SQLException ignored) {
                            // The connection may already have been closed by a network failure.
                        }
                    }
                }
            });
            connection.setNetworkTimeout(Runnable::run, Math.toIntExact(policy.effectTimeout().toMillis()));
            db.update("SET SESSION innodb_lock_wait_timeout = ?", policy.lockWait().toSeconds());
        } catch (SQLException failure) {
            throw new org.springframework.dao.DataAccessResourceFailureException(
                "Cannot bound event transaction network I/O", failure);
        }
    }

    @Override
    public int materialize(int batchSize) {
        long cursor = db.queryForObject(
            "SELECT boundary_sequence FROM event_discovery WHERE singleton_id = 1 FOR UPDATE", Long.class);
        List<Long> positions = db.queryForList("""
            SELECT boundary_sequence FROM event_records
             WHERE boundary_sequence > ? ORDER BY boundary_sequence LIMIT ?
            """, Long.class, cursor, batchSize);
        if (positions.isEmpty()) {
            return 0;
        }
        long through = positions.getLast();
        int inserted = db.update("""
            INSERT INTO event_deliveries (tenant_id, event_id, registration_id, state, next_attempt_at)
            SELECT e.tenant_id, e.event_id, r.registration_id, 'PENDING', UTC_TIMESTAMP(6)
              FROM event_records e JOIN event_registrations r
                ON r.event_type = e.event_type AND r.schema_version = e.schema_version
               AND e.boundary_sequence > r.activation_boundary
               AND (r.deactivation_boundary IS NULL OR e.boundary_sequence < r.deactivation_boundary)
             WHERE e.boundary_sequence > ? AND e.boundary_sequence <= ?
               AND NOT EXISTS (SELECT 1 FROM event_deliveries d
                                WHERE d.registration_id = r.registration_id AND d.event_id = e.event_id)
            """, cursor, through);
        requireOne(db.update("UPDATE event_discovery SET boundary_sequence = ? WHERE singleton_id = 1", through));
        return inserted;
    }

    @Override
    public List<DeliveryKey> candidates(int batchSize) {
        return db.query("""
            SELECT tenant_id, event_id, registration_id FROM event_deliveries
             WHERE (state = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6))
                OR (state = 'PROCESSING' AND lease_until <= UTC_TIMESTAMP(6))
             ORDER BY COALESCE(next_attempt_at, lease_until), event_id, registration_id LIMIT ?
            """, (row, n) -> key(row), batchSize);
    }

    @Override
    public Optional<DeliverySnapshot> lock(DeliveryKey key) {
        return db.query("""
            SELECT * FROM event_deliveries
             WHERE tenant_id = ? AND event_id = ? AND registration_id = ? FOR UPDATE
            """, (row, n) -> new DeliverySnapshot(
                key(row), DeliverySnapshot.State.valueOf(row.getString("state")), row.getInt("cycle_attempts"),
                row.getLong("lifetime_attempts"), row.getLong("fencing_token"), time(row, "lease_until"),
                time(row, "next_attempt_at"), row.getString("failure_code"), row.getString("failure_detail")),
            scope(key)).stream().findFirst();
    }

    @Override
    public Instant databaseNow() {
        // Always a separate statement AFTER any blocking lock has completed.
        return db.queryForObject("SELECT UTC_TIMESTAMP(6)",
            (row, n) -> row.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    @Override
    public Target target(DeliveryKey key) {
        return db.queryForObject("""
            SELECT e.*, r.consumer_id, r.event_type AS target_event_type, r.schema_version AS target_version
              FROM event_records e JOIN event_registrations r ON r.registration_id = ?
             WHERE e.tenant_id = ? AND e.event_id = ?
            """, (row, n) -> new Target(new StoredEvent(new EventEnvelope(
                new EventId(uuid(row.getBytes("event_id"))), new TenantId(uuid(row.getBytes("tenant_id"))),
                row.getString("aggregate_type"), uuid(row.getBytes("aggregate_id")), row.getString("event_type"),
                row.getInt("schema_version"), time(row, "occurred_at"), row.getString("payload")),
                row.getLong("boundary_sequence"), time(row, "recorded_at")),
                new ConsumerRoute(row.getString("consumer_id"), row.getString("target_event_type"),
                    row.getInt("target_version"))),
            bytes(key.registrationId()), bytes(key.tenantId().value()), bytes(key.eventId().value()));
    }

    @Override
    public void claim(DeliverySnapshot previous, Instant now, Instant leaseUntil) {
        requireOne(db.update("""
            UPDATE event_deliveries SET state = 'PROCESSING', cycle_attempts = cycle_attempts + 1,
                   lifetime_attempts = lifetime_attempts + 1, fencing_token = fencing_token + 1,
                   lease_until = ?, next_attempt_at = NULL, updated_at = ?
             WHERE tenant_id = ? AND event_id = ? AND registration_id = ? AND fencing_token = ?
               AND ((state = 'PENDING' AND next_attempt_at <= ?)
                 OR (state = 'PROCESSING' AND lease_until <= ?))
            """, utc(leaseUntil), utc(now), bytes(previous.key().tenantId().value()),
            bytes(previous.key().eventId().value()), bytes(previous.key().registrationId()),
            previous.fencingToken(), utc(now), utc(now)));
    }

    @Override
    public void done(DeliveryClaim claim, Instant now) {
        requireOne(db.update("""
            UPDATE event_deliveries SET state = 'DONE', lease_until = NULL,
                   failure_code = NULL, failure_detail = NULL, updated_at = ?
             WHERE tenant_id = ? AND event_id = ? AND registration_id = ?
               AND state = 'PROCESSING' AND fencing_token = ? AND lease_until > UTC_TIMESTAMP(6)
            """, utc(now), bytes(claim.key().tenantId().value()), bytes(claim.key().eventId().value()),
            bytes(claim.key().registrationId()), claim.fencingToken()));
    }

    @Override
    public void fail(DeliveryClaim claim, DeliveryFailure failure, Instant now, Instant nextAttemptAt) {
        requireOne(db.update("""
            UPDATE event_deliveries SET state = ?, lease_until = NULL, next_attempt_at = ?,
                   failure_code = ?, failure_detail = ?, updated_at = ?
             WHERE tenant_id = ? AND event_id = ? AND registration_id = ?
               AND state = 'PROCESSING' AND fencing_token = ? AND lease_until > UTC_TIMESTAMP(6)
            """, nextAttemptAt == null ? "DEAD" : "PENDING", utc(nextAttemptAt), failure.name(),
            failure.name(), utc(now), bytes(claim.key().tenantId().value()), bytes(claim.key().eventId().value()),
            bytes(claim.key().registrationId()), claim.fencingToken()));
    }

    @Override
    public void exhaust(DeliverySnapshot previous, Instant now) {
        requireOne(db.update("""
            UPDATE event_deliveries SET state = 'DEAD', lease_until = NULL, next_attempt_at = NULL,
                   failure_code = 'CRASH_EXHAUSTED', failure_detail = 'Claim attempt budget exhausted', updated_at = ?
             WHERE tenant_id = ? AND event_id = ? AND registration_id = ? AND fencing_token = ?
               AND ((state = 'PROCESSING' AND lease_until <= ?) OR (state = 'PENDING' AND next_attempt_at <= ?))
            """, utc(now), bytes(previous.key().tenantId().value()), bytes(previous.key().eventId().value()),
            bytes(previous.key().registrationId()), previous.fencingToken(), utc(now), utc(now)));
    }

    @Override
    public void replay(DeliverySnapshot previous, String reason, Instant now) {
        DeliveryKey key = previous.key();
        requireOne(db.update("""
            INSERT INTO event_replay_audit (replay_id, tenant_id, event_id, registration_id, consumer_id,
                   reason, recovery_origin, recorded_at, prior_state, prior_cycle_attempts, lifetime_attempts,
                   prior_fencing_token, prior_failure_code, prior_failure_detail)
            SELECT ?, d.tenant_id, d.event_id, d.registration_id, r.consumer_id, ?, 'TRUSTED_INTERNAL', ?,
                   d.state, d.cycle_attempts, d.lifetime_attempts, d.fencing_token, d.failure_code, d.failure_detail
              FROM event_deliveries d JOIN event_registrations r ON r.registration_id = d.registration_id
             WHERE d.tenant_id = ? AND d.event_id = ? AND d.registration_id = ?
               AND d.state = 'DEAD' AND d.fencing_token = ?
            """, bytes(UUID.randomUUID()), reason, utc(now), bytes(key.tenantId().value()),
            bytes(key.eventId().value()), bytes(key.registrationId()), previous.fencingToken()));
        requireOne(db.update("""
            UPDATE event_deliveries SET state = 'PENDING', cycle_attempts = 0, fencing_token = fencing_token + 1,
                   lease_until = NULL, next_attempt_at = ?, failure_code = NULL, failure_detail = NULL, updated_at = ?
             WHERE tenant_id = ? AND event_id = ? AND registration_id = ? AND state = 'DEAD' AND fencing_token = ?
            """, utc(now), utc(now), bytes(key.tenantId().value()), bytes(key.eventId().value()),
            bytes(key.registrationId()), previous.fencingToken()));
    }

    private static void requireOne(int changed) {
        if (changed != 1) {
            throw new EventOwnershipLostException();
        }
    }

    private static DeliveryKey key(ResultSet row) throws SQLException {
        return new DeliveryKey(new TenantId(uuid(row.getBytes("tenant_id"))),
            new EventId(uuid(row.getBytes("event_id"))), uuid(row.getBytes("registration_id")));
    }

    private static Instant time(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Object[] scope(DeliveryKey key) {
        return new Object[] {bytes(key.tenantId().value()), bytes(key.eventId().value()), bytes(key.registrationId())};
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
