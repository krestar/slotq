package com.slotq.integration.waitlist;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.KafkaPublicationFamily;
import com.slotq.events.application.StoredEvent;
import com.slotq.observability.ProductTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The M4 integration boundary owns the two exact publication schemas and partition key. */
@Component
public final class WaitlistKafkaMessage implements KafkaPublicationFamily {
    private static final int MAX_MESSAGE_BYTES = 1_048_576;
    private static final List<ConsumerRoute> ROUTES = List.of(
        BookingCapacityReleasedHandler.ROUTE, WaitlistPromotionRequestedHandler.ROUTE);
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final boolean localConsumerEnabled;
    private final boolean localMaintenanceEnabled;

    public WaitlistKafkaMessage(
        @Value("${slotq.waitlist.promotion.enabled:false}") boolean localConsumerEnabled,
        @Value("${slotq.waitlist.promotion.maintenance-enabled:false}") boolean localMaintenanceEnabled) {
        this.localConsumerEnabled = localConsumerEnabled;
        this.localMaintenanceEnabled = localMaintenanceEnabled;
    }

    @Override public String consumerId() { return ROUTES.getFirst().consumerId(); }
    @Override public List<ConsumerRoute> routes() { return ROUTES; }
    @Override public boolean localConsumerEnabled() { return localConsumerEnabled; }
    @Override public boolean localMaintenanceEnabled() { return localMaintenanceEnabled; }

    @Override public Message encode(StoredEvent stored, ProductTelemetry.Origin origin) {
        EventEnvelope event = stored.envelope();
        boolean release = event.eventType().equals(BookingCapacityReleasedHandler.ROUTE.eventType());
        if (event.schemaVersion() != 1 || !(release
            || event.eventType().equals(WaitlistPromotionRequestedHandler.ROUTE.eventType()))) {
            throw new IllegalArgumentException("Unsupported Kafka publication route");
        }
        if (!event.aggregateType().equals(release ? "Reservation" : "SlotInventory")) {
            throw new IllegalArgumentException("Unexpected aggregate type");
        }
        JsonNode payload = json.readTree(event.payload());
        Set<String> expected = release
            ? Set.of("venueId", "resourceId", "slotInventoryId", "fromState", "toState")
            : Set.of("venueId", "resourceId", "slotInventoryId");
        if (payload == null || !payload.isObject() || !Set.copyOf(payload.propertyNames()).equals(expected)) {
            throw new IllegalArgumentException("Unexpected publication payload");
        }
        UUID slot = canonicalUuid(payload, "slotInventoryId");
        canonicalUuid(payload, "venueId");
        canonicalUuid(payload, "resourceId");
        if (!release && !slot.equals(event.aggregateId())) throw new IllegalArgumentException("Aggregate mismatch");
        if (release) {
            String from = string(payload, "fromState");
            String to = string(payload, "toState");
            if (!Set.of("HELD:CANCELLED", "HELD:EXPIRED", "CONFIRMED:CANCELLED",
                "CONFIRMED:NO_SHOW", "CHECKED_IN:COMPLETED").contains(from + ":" + to)) {
                throw new IllegalArgumentException("Not a capacity release");
            }
        }
        // The canonical payload is embedded verbatim. Correlation is optional and outside immutable meaning.
        String body = "{" +
            "\"eventId\":" + quote(event.eventId().value().toString()) + "," +
            "\"tenantId\":" + quote(event.tenantId().value().toString()) + "," +
            "\"aggregateType\":" + quote(event.aggregateType()) + "," +
            "\"aggregateId\":" + quote(event.aggregateId().toString()) + "," +
            "\"eventType\":" + quote(event.eventType()) + "," +
            "\"schemaVersion\":1," +
            "\"occurredAt\":" + quote(event.occurredAt().toString()) + "," +
            "\"boundarySequence\":" + stored.boundarySequence() + "," +
            "\"recordedAt\":" + quote(stored.recordedAt().toString()) + "," +
            "\"payload\":" + event.payload() +
            (origin.requestId() == null ? "" : ",\"originRequestId\":" + quote(origin.requestId())) +
            (origin.hasTrace() ? ",\"originTraceId\":" + quote(origin.traceId()) +
                ",\"originSpanId\":" + quote(origin.spanId()) : "") + "}";
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Kafka envelope exceeds one MiB");
        }
        return new Message(event.tenantId().value() + ":" + slot, body);
    }

    private UUID canonicalUuid(JsonNode payload, String name) {
        String value = string(payload, name);
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) throw new IllegalArgumentException("Noncanonical UUID");
        return uuid;
    }

    private String string(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || !value.isString()) throw new IllegalArgumentException("Expected string");
        return value.asString();
    }

    private String quote(String value) { return json.writeValueAsString(value); }

}
