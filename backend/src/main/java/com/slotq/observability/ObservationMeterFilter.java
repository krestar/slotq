package com.slotq.observability;

import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.slotq.events.application.KafkaConsumerCatalog;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Fail closed for application metric dimensions; HTTP route values are server mapping templates. */
@Component
public final class ObservationMeterFilter implements MeterFilter {
    private static final Map<String, Set<String>> VALUES = Map.ofEntries(
            Map.entry("transport", Set.of("db", "kafka")),
            Map.entry("runtime_role", Set.of("observer", "delivery", "request", "maintenance", "relay", "consumer")),
            Map.entry("logical_consumer", Set.of("waitlist.promotion", "operations.event-observation")),
            Map.entry("delivery_state", DatabaseObservation.STATES),
            Map.entry("promotion_outcome", DatabaseObservation.OUTCOMES),
            Map.entry("sample", Set.of("events", "locks", "receipts", "requests", "deliveries_pending", "deliveries_processing", "deliveries_done", "deliveries_dead")),
            Map.entry("kind", Set.of("hold", "offer", "entry", "request")),
            Map.entry("failure_code", Set.of("TIMEOUT", "CONNECTION", "AUTHORIZATION", "SERIALIZATION", "OTHER",
                "MALFORMED_WIRE", "UNKNOWN_ORIGINAL", "IDENTITY_CORRUPTION", "CANONICAL_CORRUPTION",
                "REGISTRATION_CORRUPTION")),
            Map.entry("reason", Set.of("missing_timestamp", "clock_order")),
            Map.entry("event_type", Set.of("booking.capacity-released", "waitlist.promotion-requested")),
            Map.entry("schema_version", Set.of("1")),
            Map.entry("state", Set.of("ready", "degraded", "stopped")),
            Map.entry("method", Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "OTHER")),
            Map.entry("status_class", Set.of("1xx", "2xx", "3xx", "4xx", "5xx")));
    private final Map<String, String> consumerGroups;
    private final String topic;
    private final Set<String> partitions;

    @Autowired
    public ObservationMeterFilter(KafkaConsumerCatalog catalog,
        @Value("${slotq.events.kafka.destination:slotq.waitlist.events.v1}") String topic,
        @Value("${slotq.events.kafka.consumer.partitions:0,1,2}") String partitionCsv) {
        this(catalog.consumers().stream().collect(Collectors.toUnmodifiableMap(
            KafkaConsumerCatalog.ConsumerDefinition::consumerId,
            KafkaConsumerCatalog.ConsumerDefinition::groupId)), topic, parsePartitions(partitionCsv));
    }

    /** Direct unit construction has no configured broker tuple allowlist. */
    public ObservationMeterFilter() { this(Map.of(), "slotq.waitlist.events.v1", Set.of()); }

    private ObservationMeterFilter(Map<String, String> consumerGroups, String topic, Set<String> partitions) {
        if (!topic.matches("[A-Za-z0-9._-]{1,100}") || consumerGroups.size() > 2
            || (long) consumerGroups.size() * partitions.size() > 128) {
            throw new IllegalArgumentException("Invalid Kafka metric tuple allowlist");
        }
        this.consumerGroups = consumerGroups;
        this.topic = topic;
        this.partitions = partitions;
    }

    private static Set<String> parsePartitions(String csv) {
        Set<String> values = Arrays.stream(csv.split(",", -1)).map(String::trim)
            .map(value -> {
                if (!value.matches("0|[1-9][0-9]*") || Long.parseLong(value) > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("Invalid Kafka metric partition allowlist");
                return value;
            }).collect(Collectors.toUnmodifiableSet());
        if (values.isEmpty() || values.size() > 64) throw new IllegalArgumentException("Kafka metric tuple cap exceeded");
        return values;
    }
    @Override public MeterFilterReply accept(Meter.Id id) {
        if (!id.getName().startsWith("slotq.")) return MeterFilterReply.NEUTRAL;
        if (id.getName().equals("slotq.kafka.consumer.lag.records")) {
            String consumer = id.getTag("logical_consumer");
            String partition = id.getTag("partition");
            if (consumer == null || !consumerGroups.getOrDefault(consumer, "").equals(id.getTag("consumer_group"))
                || !topic.equals(id.getTag("topic")) || partition == null || !partitions.contains(partition)) {
                return MeterFilterReply.DENY;
            }
        }
        for (var tag : id.getTags()) {
            if (tag.getKey().equals("route") || tag.getKey().equals("outcome")) {
                // The request filter owns finite route/status/error classification.
                if (id.getName().equals("slotq.http.requests")) continue;
                if (id.getName().equals("slotq.delivery.effect.duration") && tag.getKey().equals("outcome")
                    && Set.of("committed", "rolled_back", "unknown", "ownership_lost").contains(tag.getValue())) continue;
                if (tag.getKey().equals("outcome") && Set.of("success", "failure", "disabled",
                    "assigned", "revoked").contains(tag.getValue())) continue;
                return MeterFilterReply.DENY;
            }
            if (Set.of("consumer_group", "topic", "partition").contains(tag.getKey())) {
                if (id.getName().equals("slotq.kafka.consumer.lag.records")) continue;
                return MeterFilterReply.DENY;
            }
            if (!VALUES.getOrDefault(tag.getKey(), Set.of()).contains(tag.getValue())) return MeterFilterReply.DENY;
        }
        return MeterFilterReply.NEUTRAL;
    }
}
