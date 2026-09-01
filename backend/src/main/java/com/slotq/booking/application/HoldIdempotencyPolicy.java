package com.slotq.booking.application;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class HoldIdempotencyPolicy {

    private static final Duration RETENTION = Duration.ofHours(24);

    private final int cleanupBatchSize;

    HoldIdempotencyPolicy(
        @Value("${slotq.booking.hold-idempotency.cleanup-batch-size:1000}") int cleanupBatchSize
    ) {
        if (cleanupBatchSize < 1) {
            throw new IllegalArgumentException("hold idempotency cleanup batch size must be positive");
        }
        this.cleanupBatchSize = cleanupBatchSize;
    }

    Duration retention() {
        return RETENTION;
    }

    int cleanupBatchSize() {
        return cleanupBatchSize;
    }
}
