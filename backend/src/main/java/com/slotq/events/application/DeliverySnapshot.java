package com.slotq.events.application;

import java.time.Instant;

public record DeliverySnapshot(DeliveryKey key, State state, int cycleAttempts,
                               long lifetimeAttempts, long fencingToken, Instant leaseUntil,
                               Instant nextAttemptAt, String failureCode, String failureDetail) {
    public enum State { PENDING, PROCESSING, DONE, DEAD }

    public boolean ownedBy(DeliveryClaim claim, Instant now) {
        return key.equals(claim.key()) && state == State.PROCESSING
            && fencingToken == claim.fencingToken() && leaseUntil.isAfter(now);
    }
}
