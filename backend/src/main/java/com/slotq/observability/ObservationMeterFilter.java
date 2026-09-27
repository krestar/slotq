package com.slotq.observability;

import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.springframework.stereotype.Component;

/** Fail closed for application metric dimensions; HTTP route values are server mapping templates. */
@Component
public final class ObservationMeterFilter implements MeterFilter {
    private static final Map<String, Set<String>> VALUES = Map.ofEntries(
            Map.entry("transport", Set.of("db", "kafka")),
            Map.entry("runtime_role", Set.of("observer", "delivery", "request", "maintenance", "relay", "consumer")),
            Map.entry("logical_consumer", Set.of("waitlist.promotion")),
            Map.entry("delivery_state", DatabaseObservation.STATES),
            Map.entry("promotion_outcome", DatabaseObservation.OUTCOMES),
            Map.entry("sample", Set.of("events", "locks", "receipts", "requests", "deliveries_pending", "deliveries_processing", "deliveries_done", "deliveries_dead")),
            Map.entry("kind", Set.of("hold", "offer", "entry", "request")),
            Map.entry("method", Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "OTHER")),
            Map.entry("status_class", Set.of("1xx", "2xx", "3xx", "4xx", "5xx")));
    @Override public MeterFilterReply accept(Meter.Id id) {
        if (!id.getName().startsWith("slotq.")) return MeterFilterReply.NEUTRAL;
        for (var tag : id.getTags()) {
            if (tag.getKey().equals("route") || tag.getKey().equals("outcome")) {
                // The request filter owns finite route/status/error classification.
                if (id.getName().equals("slotq.http.requests")) continue;
                if (id.getName().equals("slotq.delivery.effect.duration") && tag.getKey().equals("outcome")
                    && Set.of("committed", "rolled_back", "unknown", "ownership_lost").contains(tag.getValue())) continue;
                if (tag.getKey().equals("outcome") && Set.of("success", "failure", "disabled").contains(tag.getValue())) continue;
                return MeterFilterReply.DENY;
            }
            if (!VALUES.getOrDefault(tag.getKey(), Set.of()).contains(tag.getValue())) return MeterFilterReply.DENY;
        }
        return MeterFilterReply.NEUTRAL;
    }
}
