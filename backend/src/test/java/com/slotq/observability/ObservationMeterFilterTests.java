package com.slotq.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

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
}
