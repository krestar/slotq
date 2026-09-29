package com.slotq.observability;

import java.util.List;

import com.slotq.events.application.KafkaConsumerCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationMeterFilterTests {
    @Test void bothContractedKafkaConsumersAreAllowedButArbitraryConsumersFailClosed() {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new ObservationMeterFilter());
            for (String consumer : new String[]{"waitlist.promotion", "operations.event-observation"}) {
                registry.counter("slotq.kafka.intake", "transport", "kafka", "runtime_role", "consumer",
                    "logical_consumer", consumer, "outcome", "success").increment();
            }
            for (String consumer : new String[]{"unknown.consumer", "operations.event-observation.extra",
                    "customer@example.invalid", java.util.UUID.randomUUID().toString(), ""}) {
                registry.counter("slotq.kafka.intake", "transport", "kafka", "runtime_role", "consumer",
                    "logical_consumer", consumer, "outcome", "success").increment();
            }
            assertThat(registry.getMeters()).hasSize(2);
            assertThat(registry.getMeters()).extracting(meter -> meter.getId().getTag("logical_consumer"))
                .containsExactlyInAnyOrder("waitlist.promotion", "operations.event-observation");
        } finally { registry.close(); }
    }

    @Test void businessIdentitiesPayloadsRawUrlsAndUnboundedStateCannotBecomeMetricLabels() {
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new ObservationMeterFilter());
            for (String key : new String[]{"tenant_id","principal_id","event_id","request_id","reservation_id","entry_id","offer_id","idempotency_key","url","query","exception","token","payload","email"}) {
                registry.counter("slotq.db.forbidden", key, "private-value").increment();
            }
            registry.counter("slotq.db.forbidden", "delivery_state", "secret-exception-message").increment();
            registry.counter("slotq.db.allowed", "delivery_state", "DEAD").increment();
            assertThat(registry.getMeters()).hasSize(1);
            assertThat(registry.getMeters().getFirst().getId().getName()).isEqualTo("slotq.db.allowed");
        } finally { registry.close(); }
    }

    @Test void lagDimensionsRequireConfiguredConsumerGroupTopicAndPartitionTuple() {
        KafkaConsumerCatalog catalog = () -> List.of(
            new KafkaConsumerCatalog.ConsumerDefinition("waitlist.promotion", "slotq.waitlist.promotion.v1", List.of()),
            new KafkaConsumerCatalog.ConsumerDefinition("operations.event-observation",
                "slotq.operations.event-observation.v1", List.of()));
        var registry = new SimpleMeterRegistry();
        try {
            registry.config().meterFilter(new ObservationMeterFilter(catalog, "slotq.waitlist.events.v1", "0,1,2"));
            lag(registry, "waitlist.promotion", "slotq.waitlist.promotion.v1", "slotq.waitlist.events.v1", "0");
            lag(registry, "operations.event-observation", "slotq.operations.event-observation.v1",
                "slotq.waitlist.events.v1", "2");
            lag(registry, "waitlist.promotion", "slotq.operations.event-observation.v1",
                "slotq.waitlist.events.v1", "0");
            lag(registry, "waitlist.promotion", "slotq.waitlist.promotion.v1", "unapproved.topic", "0");
            lag(registry, "waitlist.promotion", "slotq.waitlist.promotion.v1",
                "slotq.waitlist.events.v1", "3");
            assertThat(registry.getMeters()).hasSize(2);
        } finally { registry.close(); }
        assertThatThrownBy(() -> new ObservationMeterFilter(catalog, "slotq.waitlist.events.v1",
            java.util.stream.IntStream.range(0, 65).mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(","))))
            .hasMessageContaining("tuple cap");
    }

    private static void lag(SimpleMeterRegistry registry, String consumer, String group, String topic,
                            String partition) {
        registry.gauge("slotq.kafka.consumer.lag.records",
            io.micrometer.core.instrument.Tags.of("transport", "kafka", "runtime_role", "consumer",
                "logical_consumer", consumer, "consumer_group", group, "topic", topic,
                "partition", partition), new java.util.concurrent.atomic.AtomicLong());
    }
}
