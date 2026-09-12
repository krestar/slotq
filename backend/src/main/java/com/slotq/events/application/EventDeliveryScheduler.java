package com.slotq.events.application;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "slotq.events.delivery.scheduler-enabled", havingValue = "true")
public final class EventDeliveryScheduler {
    private final EventDeliveryWorker worker;

    public EventDeliveryScheduler(EventDeliveryWorker worker,
        @Value("${slotq.events.delivery.poll-interval:PT1S}") Duration interval) {
        if (interval.isZero() || interval.isNegative() || interval.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Event poll interval must be positive and bounded");
        }
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${slotq.events.delivery.poll-interval:PT1S}")
    public void tick() {
        worker.runCycle();
    }
}
