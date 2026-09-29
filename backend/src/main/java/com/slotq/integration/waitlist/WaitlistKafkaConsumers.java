package com.slotq.integration.waitlist;

import java.util.List;
import java.util.UUID;

import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.KafkaConsumerCatalog;
import com.slotq.events.application.KafkaIntakeWire;
import com.slotq.events.application.StoredEvent;
import com.slotq.observability.ProductTelemetry;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M4 owns its two routes and exact #107 wire meaning, not the events foundation. */
@Component
public final class WaitlistKafkaConsumers implements KafkaConsumerCatalog, KafkaIntakeWire {
    public static final String OBSERVER = "operations.event-observation";
    private static final String WAITLIST = "waitlist.promotion";
    private static final List<String> TYPES = List.of("booking.capacity-released", "waitlist.promotion-requested");
    private final WaitlistKafkaMessage messages;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public WaitlistKafkaConsumers(WaitlistKafkaMessage messages) { this.messages = messages; }

    @Override public List<ConsumerDefinition> consumers() {
        return List.of(
            new ConsumerDefinition(WAITLIST, "slotq.waitlist.promotion.v1", routes(WAITLIST)),
            new ConsumerDefinition(OBSERVER, "slotq.operations.event-observation.v1", routes(OBSERVER)));
    }

    private List<ConsumerRoute> routes(String consumerId) {
        return TYPES.stream().map(type -> new ConsumerRoute(consumerId, type, 1)).toList();
    }

    @Override public Reference reference(String body) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("Expected Kafka object");
            return new Reference(uuid(root, "tenantId"), uuid(root, "eventId"));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Malformed Kafka original reference", failure);
        }
    }

    @Override public void verify(StoredEvent original, ProductTelemetry.Origin origin, String key, String body) {
        var expected = messages.encode(original, origin);
        if (!expected.key().equals(key) || !expected.body().equals(body)) {
            throw new IllegalArgumentException("Kafka wire differs from canonical original");
        }
    }

    private UUID uuid(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString()) throw new IllegalArgumentException("Missing UUID");
        UUID parsed = UUID.fromString(value.asString());
        if (!parsed.toString().equals(value.asString())) throw new IllegalArgumentException("Noncanonical UUID");
        return parsed;
    }
}
