package com.slotq.events.application;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record KafkaPublicationPolicy(int maxAttempts, Duration lease, Duration ackTimeout,
                                     int batchSize, List<Duration> retryDelays) {
    public KafkaPublicationPolicy(
        @Value("${slotq.events.kafka.max-attempts:5}") int maxAttempts,
        @Value("${slotq.events.kafka.lease:PT30S}") Duration lease,
        @Value("${slotq.events.kafka.ack-timeout:PT20S}") Duration ackTimeout,
        @Value("${slotq.events.kafka.batch-size:100}") int batchSize,
        @Value("${slotq.events.kafka.retry-delays:PT1S,PT5S,PT30S,PT2M}") List<Duration> retryDelays) {
        if (maxAttempts < 1 || maxAttempts > 100 || batchSize < 1 || batchSize > 100
            || ackTimeout == null || ackTimeout.isZero() || ackTimeout.isNegative()
            || ackTimeout.compareTo(Duration.ofMinutes(1)) > 0
            || lease == null || lease.compareTo(ackTimeout) <= 0 || lease.compareTo(Duration.ofDays(1)) > 0
            || retryDelays == null || retryDelays.size() != maxAttempts - 1
            || retryDelays.stream().anyMatch(delay -> delay == null || delay.isNegative()
                || delay.compareTo(Duration.ofDays(1)) > 0)) {
            throw new IllegalArgumentException("Invalid Kafka publication budget");
        }
        this.maxAttempts = maxAttempts;
        this.lease = lease;
        this.ackTimeout = ackTimeout;
        this.batchSize = batchSize;
        this.retryDelays = List.copyOf(retryDelays);
    }
    public Duration retryDelay(int consumedAttempts) { return retryDelays.get(consumedAttempts - 1); }
}
