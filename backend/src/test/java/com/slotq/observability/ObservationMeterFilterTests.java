package com.slotq.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ObservationMeterFilterTests {
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
