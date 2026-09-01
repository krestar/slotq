package com.slotq.booking.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HoldIdempotencyCleanup {

    private final HoldIdempotencyStore store;
    private final HoldIdempotencyPolicy policy;
    private final Clock clock;

    HoldIdempotencyCleanup(
        HoldIdempotencyStore store,
        HoldIdempotencyPolicy policy,
        Clock clock
    ) {
        this.store = store;
        this.policy = policy;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${slotq.booking.hold-idempotency.cleanup-interval:PT1H}")
    @Transactional
    public int cleanupExpired() {
        Instant retentionCutoff = clock.instant().minus(policy.retention());
        return store.deleteCompletedAtOrBefore(retentionCutoff, policy.cleanupBatchSize());
    }
}
