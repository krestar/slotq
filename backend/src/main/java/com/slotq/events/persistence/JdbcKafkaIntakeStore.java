package com.slotq.events.persistence;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventId;
import com.slotq.events.application.KafkaConsumerCatalog.ConsumerDefinition;
import com.slotq.events.application.KafkaIntakeWire;
import com.slotq.events.application.StoredEvent;
import com.slotq.observability.ProductTelemetry;
import com.slotq.tenancy.domain.TenantId;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A Kafka offset may advance only after this short MySQL transaction commits. */
@Repository
public class JdbcKafkaIntakeStore {
    private static final int MAX_WIRE_BYTES = 1_048_576;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final KafkaIntakeWire wire;

    public JdbcKafkaIntakeStore(JdbcTemplate db, PlatformTransactionManager manager, KafkaIntakeWire wire) {
        this.db = db;
        this.transactions = new TransactionTemplate(manager);
        this.wire = wire;
    }

    public Outcome intake(ConsumerRecord<byte[], byte[]> record, ConsumerDefinition consumer, long expectedEpoch) {
        if (expectedEpoch <= 0) throw new IllegalArgumentException("Kafka authority epoch must be positive");
        if (record.partition() < 0 || record.offset() < 0 || !record.topic().matches("[A-Za-z0-9._-]{1,100}"))
            throw new IllegalArgumentException("Invalid Kafka coordinate");
        byte[] hash = digest(record);
        return transactions.execute(status -> intakeInTransaction(record, consumer, expectedEpoch, hash));
    }

    /** Called on assignment before seeking; no expired offset is silently treated as latest. */
    public long startOffset(ConsumerDefinition consumer, String topic, int partition, long brokerBeginning,
                            Long committedNextOffset) {
        if (brokerBeginning < 0) throw new IllegalArgumentException("Invalid broker beginning offset");
        List<Long> positions = db.queryForList("""
            SELECT last_durable_offset FROM event_kafka_consumer_positions
             WHERE consumer_id=? AND topic=? AND partition_id=?
            """, Long.class, consumer.consumerId(), topic, partition);
        if (positions.isEmpty()) {
            if (committedNextOffset != null)
                throw new IllegalStateException("Kafka group position has no durable intake provenance");
            Long oldestPublication = db.queryForObject("""
                SELECT MIN(ack_offset) FROM event_kafka_publications
                 WHERE destination=? AND state='PUBLISHED' AND ack_partition=?
                """, Long.class, topic, partition);
            if (oldestPublication != null && brokerBeginning > oldestPublication)
                throw new IllegalStateException("Kafka log start passed an original publication");
            return brokerBeginning;
        }
        long durableNext = Math.addExact(positions.getFirst(), 1);
        if (brokerBeginning > durableNext || (committedNextOffset != null && committedNextOffset > durableNext))
            throw new IllegalStateException("Kafka group position exceeds durable intake prefix or retention");
        if (committedNextOffset == null || committedNextOffset < brokerBeginning) return durableNext;
        return committedNextOffset;
    }

    public void verifyTopicIdentity(String topic, String observedTopicId) {
        List<String> bound = db.queryForList(
            "SELECT topic_id FROM event_kafka_topic_state WHERE destination=?", String.class, topic);
        if (bound.size() != 1 || !bound.getFirst().equals(observedTopicId))
            throw new IllegalStateException("Kafka intake topic identity differs from durable publication topic");
    }

    /** Fresh DB UTC is observed after the intake transaction, never recorded as its exact commit time. */
    public Optional<DelayObservation> firstTargetDelay(ConsumerRecord<byte[], byte[]> record,
                                                       ConsumerDefinition consumer) {
        return db.query("""
            SELECT e.recorded_at,UTC_TIMESTAMP(6) AS observed_at
              FROM event_kafka_intake_records i
              JOIN event_kafka_target_intakes t
                ON t.tenant_id=i.tenant_id AND t.event_id=i.event_id
               AND t.registration_id=i.registration_id AND t.topic=i.topic
               AND t.partition_id=i.partition_id AND t.first_offset=i.record_offset
              JOIN event_records e ON e.tenant_id=t.tenant_id AND e.event_id=t.event_id
             WHERE i.consumer_id=? AND i.topic=? AND i.partition_id=? AND i.record_offset=?
               AND t.first_materialization=1
            """, (row, n) -> {
            Instant recorded = instant(row, "recorded_at");
            Instant observed = instant(row, "observed_at");
            return new DelayObservation(Duration.between(recorded, observed));
        }, consumer.consumerId(), record.topic(), record.partition(), record.offset()).stream().findFirst();
    }

    public List<QuarantineCount> quarantineCounts(String consumerId) {
        return db.query("""
            SELECT failure_code,COUNT(*) AS row_count,
                   TIMESTAMPDIFF(SECOND,MIN(intaken_at),UTC_TIMESTAMP(6)) AS oldest_age
              FROM event_kafka_intake_records
             WHERE consumer_id=? AND disposition='QUARANTINED'
             GROUP BY failure_code
            """, (row, n) -> new QuarantineCount(row.getString("failure_code"), row.getLong("row_count"),
            row.getLong("oldest_age")), consumerId);
    }

    private Outcome intakeInTransaction(ConsumerRecord<byte[], byte[]> record, ConsumerDefinition consumer,
                                        long expectedEpoch, byte[] hash) {
        db.update("""
            INSERT INTO event_kafka_consumer_positions
                (consumer_id,topic,partition_id,last_durable_offset)
            VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE consumer_id=consumer_id
            """, consumer.consumerId(), record.topic(), record.partition(), record.offset() - 1);
        long position = db.queryForObject("""
            SELECT last_durable_offset FROM event_kafka_consumer_positions
             WHERE consumer_id=? AND topic=? AND partition_id=? FOR UPDATE
            """, Long.class, consumer.consumerId(), record.topic(), record.partition());
        if (record.offset() > position + 1)
            throw new IllegalStateException("Kafka intake partition gap");
        List<String> existing = db.query("""
            SELECT disposition,record_sha256 FROM event_kafka_intake_records
             WHERE consumer_id=? AND topic=? AND partition_id=? AND record_offset=? FOR UPDATE
            """, (row, n) -> {
            if (!Arrays.equals(hash, row.getBytes("record_sha256")))
                throw new IllegalStateException("Kafka coordinate content changed");
            return row.getString("disposition");
        }, consumer.consumerId(), record.topic(), record.partition(), record.offset());
        if (!existing.isEmpty()) {
            if (record.offset() > position) throw new IllegalStateException("Intake coordinate without durable prefix");
            return existing.getFirst().equals("TARGET") ? Outcome.REUSED_TARGET : Outcome.valueOf(existing.getFirst());
        }
        if (record.offset() <= position) throw new IllegalStateException("Durable prefix lacks intake coordinate");

        String key;
        String body;
        try {
            if (record.headers().toArray().length != 0 || record.key() == null || record.value() == null
                || record.value().length > MAX_WIRE_BYTES) {
                return finish(record, consumer, hash, Outcome.QUARANTINED, null, null, null, null, "MALFORMED_WIRE");
            }
            key = utf8(record.key());
            body = utf8(record.value());
        } catch (CharacterCodingException failure) {
            return finish(record, consumer, hash, Outcome.QUARANTINED, null, null, null, null, "MALFORMED_WIRE");
        }
        KafkaIntakeWire.Reference reference;
        try {
            reference = wire.reference(body);
        } catch (IllegalArgumentException failure) {
            return finish(record, consumer, hash, Outcome.QUARANTINED, null, null, null, null, "MALFORMED_WIRE");
        }
        List<Original> originals = db.query("""
            SELECT e.* FROM event_records e WHERE e.event_id=?
            """, (row, n) -> original(row), bytes(reference.eventId()));
        if (originals.isEmpty())
            return finish(record, consumer, hash, Outcome.QUARANTINED, null, null, null, null, "UNKNOWN_ORIGINAL");
        Original original = originals.getFirst();
        EventEnvelope event = original.stored().envelope();
        if (!event.tenantId().value().equals(reference.tenantId()))
            return finish(record, consumer, hash, Outcome.QUARANTINED, null, null, null, null, "IDENTITY_CORRUPTION");
        try {
            wire.verify(original.stored(), original.origin(), key, body);
        } catch (IllegalArgumentException failure) {
            return finish(record, consumer, hash, Outcome.QUARANTINED, null, null, null, null, "CANONICAL_CORRUPTION");
        }
        List<Registration> registrations = db.query("""
            SELECT r.registration_id, a.transport, a.authority_epoch
              FROM event_registrations r
              LEFT JOIN event_transport_assignments a ON a.registration_id=r.registration_id
             WHERE r.consumer_id=? AND r.event_type=? AND r.schema_version=?
               AND r.activation_boundary < ?
               AND (r.deactivation_boundary IS NULL OR ? < r.deactivation_boundary)
             FOR UPDATE
            """, (row, n) -> new Registration(uuid(row.getBytes("registration_id")),
                row.getString("transport"), row.getObject("authority_epoch", Long.class)),
            consumer.consumerId(), event.eventType(), event.schemaVersion(), original.stored().boundarySequence(),
            original.stored().boundarySequence());
        if (registrations.isEmpty())
            return finish(record, consumer, hash, Outcome.NON_TARGET, null, null, null, null, "NO_REGISTRATION");
        if (registrations.size() != 1)
            return finish(record, consumer, hash, Outcome.QUARANTINED, null, null, null, null, "REGISTRATION_CORRUPTION");
        Registration registration = registrations.getFirst();
        if (registration.transport() == null || registration.epoch() == null)
            throw new IllegalStateException("Original registration lacks transport assignment");
        if (registration.transport().equals("DB_DIRECT"))
            return finish(record, consumer, hash, Outcome.NON_TARGET, null, null, null, null, "DB_DIRECT_AUTHORITY");
        if (!registration.transport().equals("KAFKA") || registration.epoch() != expectedEpoch)
            throw new IllegalStateException("Incompatible Kafka transport authority epoch");
        UUID tenant = event.tenantId().value();
        UUID eventId = event.eventId().value();
        UUID registrationId = registration.id();
        boolean newTarget = db.queryForList("""
            SELECT 1 FROM event_deliveries
             WHERE tenant_id=? AND event_id=? AND registration_id=? FOR UPDATE
            """, Integer.class, bytes(tenant), bytes(eventId), bytes(registrationId)).isEmpty();
        db.update("""
            INSERT INTO event_deliveries (tenant_id,event_id,registration_id,state,next_attempt_at)
            VALUES (?,?,?,'PENDING',UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE event_id=event_id
            """, bytes(tenant), bytes(eventId), bytes(registrationId));
        db.update("""
            INSERT INTO event_kafka_target_intakes
                (tenant_id,event_id,registration_id,consumer_id,topic,partition_id,first_offset,
                 authority_epoch,first_materialization)
            VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id=event_id
            """, bytes(tenant), bytes(eventId), bytes(registrationId), consumer.consumerId(), record.topic(),
            record.partition(), record.offset(), expectedEpoch, newTarget);
        return finish(record, consumer, hash, Outcome.TARGET, tenant, eventId, registrationId,
            expectedEpoch, null);
    }

    private Outcome finish(ConsumerRecord<byte[], byte[]> record, ConsumerDefinition consumer, byte[] hash,
                           Outcome disposition, UUID tenant, UUID event, UUID registration, Long epoch, String failure) {
        db.update("""
            INSERT INTO event_kafka_intake_records
                (consumer_id,topic,partition_id,record_offset,record_sha256,disposition,
                 tenant_id,event_id,registration_id,authority_epoch,failure_code)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, consumer.consumerId(), record.topic(), record.partition(), record.offset(), hash,
            disposition.name(), tenant == null ? null : bytes(tenant), event == null ? null : bytes(event),
            registration == null ? null : bytes(registration), epoch, failure);
        int advanced = db.update("""
            UPDATE event_kafka_consumer_positions SET last_durable_offset=?,updated_at=UTC_TIMESTAMP(6)
             WHERE consumer_id=? AND topic=? AND partition_id=? AND last_durable_offset=?
            """, record.offset(), consumer.consumerId(), record.topic(), record.partition(), record.offset() - 1);
        if (advanced != 1) throw new IllegalStateException("Kafka intake prefix was not advanced");
        return disposition;
    }

    private Original original(ResultSet row) throws SQLException {
        EventEnvelope envelope = new EventEnvelope(new EventId(uuid(row.getBytes("event_id"))),
            new TenantId(uuid(row.getBytes("tenant_id"))), row.getString("aggregate_type"),
            uuid(row.getBytes("aggregate_id")), row.getString("event_type"), row.getInt("schema_version"),
            instant(row, "occurred_at"), row.getString("payload"));
        var origin = new ProductTelemetry.Origin(row.getString("origin_request_id"),
            row.getString("origin_trace_id"), row.getString("origin_span_id"));
        return new Original(new StoredEvent(envelope, row.getLong("boundary_sequence"), instant(row, "recorded_at")), origin);
    }

    private static Instant instant(ResultSet row, String field) throws SQLException {
        return row.getObject(field, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static String utf8(byte[] value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
    }

    private static byte[] digest(ConsumerRecord<byte[], byte[]> record) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            add(hash, record.key()); add(hash, record.value());
            for (var header : record.headers()) {
                add(hash, header.key().getBytes(StandardCharsets.UTF_8)); add(hash, header.value());
            }
            return hash.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void add(MessageDigest hash, byte[] value) {
        hash.update(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value == null ? -1 : value.length).array());
        if (value != null) hash.update(value);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer data = ByteBuffer.wrap(value);
        return new UUID(data.getLong(), data.getLong());
    }

    public enum Outcome { TARGET, REUSED_TARGET, NON_TARGET, QUARANTINED }
    public record DelayObservation(Duration duration) { }
    public record QuarantineCount(String failureCode, long count, long oldestAgeSeconds) { }
    private record Registration(UUID id, String transport, Long epoch) { }
    private record Original(StoredEvent stored, ProductTelemetry.Origin origin) { }
}
