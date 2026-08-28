package com.slotq.venue.domain;

import java.time.Instant;
import java.util.Objects;

public record PolicyDeadlines(
    long appliedPolicyVersion,
    Instant expiresAt,
    Instant cancelAllowedUntil,
    Instant noShowEligibleAt
) {

    public PolicyDeadlines {
        if (appliedPolicyVersion <= 0) {
            throw new IllegalArgumentException("appliedPolicyVersion must be positive");
        }
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Objects.requireNonNull(cancelAllowedUntil, "cancelAllowedUntil must not be null");
        Objects.requireNonNull(noShowEligibleAt, "noShowEligibleAt must not be null");
    }
}
