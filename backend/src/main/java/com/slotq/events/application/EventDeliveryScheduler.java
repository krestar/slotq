package com.slotq.events.application;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "slotq.events.delivery.scheduler-enabled", havingValue = "true")
public final class EventDeliveryScheduler {
    private com.slotq.observability.ProductTelemetry telemetry = com.slotq.observability.ProductTelemetry.noop();
    private io.micrometer.core.instrument.MeterRegistry meters;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureTelemetry(com.slotq.observability.ProductTelemetry telemetry, io.micrometer.core.instrument.MeterRegistry meters) {
        this.telemetry = telemetry;
        this.meters = meters;
    }
    private final EventDeliveryWorker worker;
    private final Optional<EventDeliveryReadiness> readiness;

    public EventDeliveryScheduler(EventDeliveryWorker worker, Optional<EventDeliveryReadiness> readiness,
        @Value("${slotq.events.delivery.poll-interval:PT1S}") Duration interval) {
        if (interval.compareTo(Duration.ofMillis(1)) < 0 || interval.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Event poll interval must be positive and bounded");
        }
        this.worker = worker;
        this.readiness = readiness;
    }

    @Scheduled(fixedDelayString = "${slotq.events.delivery.poll-interval:PT1S}")
    public void tick() {
        if (!readiness.map(EventDeliveryReadiness::isReady).orElse(false)) return;
        long started = System.nanoTime();
        String outcome = "failure";
        try (var observation = telemetry.background("event_delivery")) {
            worker.runCycle();
            outcome = "success";
            observation.finish("success");
        } catch (RuntimeException failure) {
            // The scheduler will try a later cycle. Never forward SQL/driver exception text to logs.
            try { org.slf4j.LoggerFactory.getLogger("slotq.telemetry").warn("operation=event_delivery outcome=failure"); }
            catch (RuntimeException ignored) { }
        } finally {
            try {
                if (meters != null) {
                    meters.counter("slotq.delivery.cycles", "outcome", outcome).increment();
                    meters.timer("slotq.delivery.cycle.duration", "outcome", outcome)
                        .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
                }
            } catch (RuntimeException ignored) { }
        }
    }
}
