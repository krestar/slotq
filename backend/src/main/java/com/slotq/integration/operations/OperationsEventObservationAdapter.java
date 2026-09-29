package com.slotq.integration.operations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryFailure;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventHandlingException;
import com.slotq.events.application.StoredEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Concrete M4 vocabulary and the observer receipt live outside the event foundation. */
@Component
class OperationsEventObservationAdapter {
    static final String CONSUMER = "operations.event-observation";
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    OperationsEventObservationAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void observe(StoredEvent stored, ConsumerRoute route) {
        Meaning meaning = decode(stored.envelope(), route);
        List<UUID> targets = jdbc.query("""
            SELECT d.registration_id FROM event_deliveries d
            JOIN event_registrations r ON r.registration_id = d.registration_id
            WHERE d.tenant_id = ? AND d.event_id = ? AND d.state = 'PROCESSING'
              AND r.consumer_id = ? AND r.event_type = ? AND r.schema_version = ?
            FOR UPDATE OF d
            """, (row, number) -> uuid(row.getBytes(1)), bytes(meaning.tenantId()),
            bytes(meaning.eventId()), CONSUMER, meaning.eventType(), meaning.schemaVersion());
        if (targets.size() != 1) throw new EventHandlingException(DeliveryFailure.IDENTITY_CORRUPTION);
        UUID registrationId = targets.getFirst();
        // DB-direct targets have no Kafka intake. Never substitute projection processing time.
        jdbc.update("""
            INSERT INTO event_observation_projections
                (tenant_id, event_id, registration_id, consumer_id, event_type, schema_version,
                 aggregate_type, aggregate_id, occurred_at, venue_id, resource_id, slot_inventory_id,
                 from_state, to_state, intaken_at, projected_at)
            SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, intake.intaken_at, UTC_TIMESTAMP(6)
            FROM (SELECT 1) seed
            LEFT JOIN event_kafka_target_intakes intake
              ON intake.tenant_id = ? AND intake.event_id = ? AND intake.registration_id = ?
            ON DUPLICATE KEY UPDATE event_id = event_observation_projections.event_id
            """, bytes(meaning.tenantId()), bytes(meaning.eventId()), bytes(registrationId), CONSUMER,
            meaning.eventType(), meaning.schemaVersion(), meaning.aggregateType(), bytes(meaning.aggregateId()),
            utc(meaning.occurredAt()), bytes(meaning.venueId()), bytes(meaning.resourceId()),
            bytes(meaning.slotInventoryId()), meaning.fromState(), meaning.toState(),
            bytes(meaning.tenantId()), bytes(meaning.eventId()), bytes(registrationId));
        Meaning saved = jdbc.queryForObject("""
            SELECT * FROM event_observation_projections
            WHERE tenant_id = ? AND event_id = ? AND registration_id = ? FOR UPDATE
            """, OperationsEventObservationAdapter::meaning, bytes(meaning.tenantId()),
            bytes(meaning.eventId()), bytes(registrationId));
        if (!meaning.equals(saved)) throw new EventHandlingException(DeliveryFailure.IDENTITY_CORRUPTION);
    }

    private Meaning decode(EventEnvelope event, ConsumerRoute route) {
        if (event.schemaVersion() != route.schemaVersion()) throw new EventHandlingException(DeliveryFailure.UNSUPPORTED_VERSION);
        if (!event.eventType().equals(route.eventType())) throw new EventHandlingException(DeliveryFailure.TARGET_ROUTE_CORRUPTION);
        boolean release = route.eventType().equals("booking.capacity-released");
        if (!event.aggregateType().equals(release ? "Reservation" : "SlotInventory"))
            throw new EventHandlingException(DeliveryFailure.PAYLOAD_INVALID);
        try {
            JsonNode payload = json.readTree(event.payload());
            Set<String> fields = release
                ? Set.of("venueId", "resourceId", "slotInventoryId", "fromState", "toState")
                : Set.of("venueId", "resourceId", "slotInventoryId");
            if (payload == null || !payload.isObject() || !Set.copyOf(payload.propertyNames()).equals(fields))
                throw new IllegalArgumentException("Unexpected event fields");
            UUID slotId = id(payload, "slotInventoryId");
            String from = release ? string(payload, "fromState") : null;
            String to = release ? string(payload, "toState") : null;
            if (release && !releasePair(from, to)) throw new IllegalArgumentException("Invalid release transition");
            if (!release && !event.aggregateId().equals(slotId))
                throw new EventHandlingException(DeliveryFailure.IDENTITY_CORRUPTION);
            return new Meaning(event.tenantId().value(), event.eventId().value(), event.eventType(),
                event.schemaVersion(), event.aggregateType(), event.aggregateId(), event.occurredAt(),
                id(payload, "venueId"), id(payload, "resourceId"), slotId, from, to);
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException invalid) {
            throw new EventHandlingException(DeliveryFailure.PAYLOAD_INVALID);
        }
    }

    private static boolean releasePair(String from, String to) {
        return switch (from) {
            case "HELD" -> Set.of("CANCELLED", "EXPIRED").contains(to);
            case "CONFIRMED" -> Set.of("CANCELLED", "NO_SHOW").contains(to);
            case "CHECKED_IN" -> "COMPLETED".equals(to);
            default -> false;
        };
    }

    private static Meaning meaning(ResultSet row, int number) throws SQLException {
        return new Meaning(uuid(row.getBytes("tenant_id")), uuid(row.getBytes("event_id")),
            row.getString("event_type"), row.getInt("schema_version"), row.getString("aggregate_type"),
            uuid(row.getBytes("aggregate_id")), instant(row, "occurred_at"), uuid(row.getBytes("venue_id")),
            uuid(row.getBytes("resource_id")), uuid(row.getBytes("slot_inventory_id")),
            row.getString("from_state"), row.getString("to_state"));
    }

    private static UUID id(JsonNode payload, String name) {
        String value = string(payload, name);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Noncanonical UUID");
        return id;
    }
    private static String string(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || !value.isString()) throw new IllegalArgumentException("Expected string");
        return value.asString();
    }
    static byte[] bytes(UUID value) {
        var buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
        return buffer.array();
    }
    static UUID uuid(byte[] value) {
        var buffer = java.nio.ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
    record Meaning(UUID tenantId, UUID eventId, String eventType, int schemaVersion,
                   String aggregateType, UUID aggregateId, Instant occurredAt,
                   UUID venueId, UUID resourceId, UUID slotInventoryId,
                   String fromState, String toState) { }
}
