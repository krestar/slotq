package com.slotq.events.application;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record DeliveryPolicy(int maxAttempts, Duration lease, Duration effectTimeout,
                             Duration lockWait, int batchSize, List<Duration> retryDelays) {

    public DeliveryPolicy(
        @Value("${slotq.events.delivery.max-attempts:5}") int maxAttempts,
        @Value("${slotq.events.delivery.lease:PT30S}") Duration lease,
        @Value("${slotq.events.delivery.effect-timeout:PT10S}") Duration effectTimeout,
        @Value("${slotq.events.delivery.lock-wait:PT5S}") Duration lockWait,
        @Value("${slotq.events.delivery.batch-size:100}") int batchSize,
        @Value("${slotq.events.delivery.retry-delays:PT1S,PT5S,PT30S,PT2M}") List<Duration> retryDelays
    ) {
        if (maxAttempts < 1 || maxAttempts > 100 || batchSize < 1 || batchSize > 100
            || lease == null || effectTimeout == null || lockWait == null
            || lockWait.isNegative() || lockWait.isZero() || lockWait.getNano() != 0
            || effectTimeout.getNano() != 0 || effectTimeout.compareTo(lockWait) <= 0
            || effectTimeout.compareTo(Duration.ofHours(1)) > 0
            || lease.compareTo(effectTimeout.multipliedBy(2)) < 0
            || lease.compareTo(Duration.ofDays(1)) > 0
            || retryDelays == null || retryDelays.size() != maxAttempts - 1
            || retryDelays.stream().anyMatch(delay -> delay == null || delay.isNegative()
                || delay.compareTo(Duration.ofDays(1)) > 0)) {
            throw new IllegalArgumentException("Invalid event delivery budget/timeout configuration");
        }
        this.maxAttempts = maxAttempts;
        this.lease = lease;
        this.effectTimeout = effectTimeout;
        this.lockWait = lockWait;
        this.batchSize = batchSize;
        this.retryDelays = List.copyOf(retryDelays);
    }

    public Duration retryDelay(int consumedAttempts) {
        return retryDelays.get(consumedAttempts - 1);
    }
}
