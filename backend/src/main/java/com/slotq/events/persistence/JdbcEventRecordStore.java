package com.slotq.events.persistence;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRecordStore;
import com.slotq.events.application.StoredEvent;
import com.slotq.tenancy.domain.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEventRecordStore implements EventRecordStore {

    private final JdbcTemplate jdbc;

    public JdbcEventRecordStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long lockBoundary() {
        return jdbc.queryForObject(
            "SELECT sequence_value FROM event_boundary WHERE singleton_id = 1 FOR UPDATE", Long.class
        );
    }

    @Override
    public void setBoundary(long sequence) {
        requireOne(jdbc.update("UPDATE event_boundary SET sequence_value = ? WHERE singleton_id = 1", sequence));
    }

    @Override
    public Optional<StoredEvent> findEventForAppend(EventId eventId) {
        return jdbc.query("SELECT * FROM event_records WHERE event_id = ? FOR UPDATE",
            JdbcEventRecordStore::storedEvent, bytes(eventId.value())).stream().findFirst();
    }

    @Override
    public StoredEvent insertEvent(EventEnvelope envelope, long boundarySequence) {
        requireOne(jdbc.update("""
            INSERT INTO event_records (
                event_id, tenant_id, aggregate_type, aggregate_id, event_type, schema_version,
                occurred_at, payload, boundary_sequence, recorded_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
            """, bytes(envelope.eventId().value()), bytes(envelope.tenantId().value()), envelope.aggregateType(),
            bytes(envelope.aggregateId()), envelope.eventType(), envelope.schemaVersion(),
            LocalDateTime.ofInstant(envelope.occurredAt(), ZoneOffset.UTC), envelope.payload(), boundarySequence));
        return findEventForAppend(envelope.eventId()).orElseThrow();
    }

    @Override
    public boolean hasActiveRegistration(ConsumerRoute route) {
        return !jdbc.queryForList("""
            SELECT registration_id FROM event_registrations
            WHERE consumer_id = ? AND event_type = ? AND schema_version = ?
              AND deactivation_boundary IS NULL FOR UPDATE
            """, route.consumerId(), route.eventType(), route.schemaVersion()).isEmpty();
    }

    @Override
    public void insertRegistration(UUID registrationId, ConsumerRoute route, long activationBoundary) {
        requireOne(jdbc.update("""
            INSERT INTO event_registrations (
                registration_id, consumer_id, event_type, schema_version, activation_boundary, created_at
            ) VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6))
            """, bytes(registrationId), route.consumerId(), route.eventType(), route.schemaVersion(), activationBoundary));
    }

    @Override
    public boolean isRegistrationActive(UUID registrationId) {
        return !jdbc.queryForList("""
            SELECT registration_id FROM event_registrations
            WHERE registration_id = ? AND deactivation_boundary IS NULL FOR UPDATE
            """, bytes(registrationId)).isEmpty();
    }

    @Override
    public void deactivateRegistration(UUID registrationId, long deactivationBoundary) {
        requireOne(jdbc.update("""
            UPDATE event_registrations SET deactivation_boundary = ?
            WHERE registration_id = ? AND deactivation_boundary IS NULL
            """, deactivationBoundary, bytes(registrationId)));
    }

    private static StoredEvent storedEvent(ResultSet row, int rowNumber) throws SQLException {
        return new StoredEvent(new EventEnvelope(
            new EventId(uuid(row.getBytes("event_id"))), new TenantId(uuid(row.getBytes("tenant_id"))),
            row.getString("aggregate_type"), uuid(row.getBytes("aggregate_id")), row.getString("event_type"),
            row.getInt("schema_version"), row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            row.getString("payload")
        ), row.getLong("boundary_sequence"), row.getObject("recorded_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static void requireOne(int count) {
        if (count != 1) throw new IllegalStateException("event persistence update must affect exactly one row");
    }
}
