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
        if (readiness.map(EventDeliveryReadiness::isReady).orElse(false)) worker.runCycle();
    }
}
