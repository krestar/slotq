package com.slotq.venue.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record BookingPolicy(long version, BookingPolicyTerms terms, Instant createdAt) {

    public BookingPolicy {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        Objects.requireNonNull(terms, "terms must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public PolicyDeadlines applyTo(Instant startsAt, Clock clock) {
        Objects.requireNonNull(startsAt, "startsAt must not be null");
        Instant appliedAt = Objects.requireNonNull(clock, "clock must not be null").instant();
        Instant holdLimit = appliedAt.plus(Duration.ofMinutes(terms.holdDurationMinutes()));
        Instant expiresAt = holdLimit.isBefore(startsAt) ? holdLimit : startsAt;
        return new PolicyDeadlines(
            version,
            expiresAt,
            startsAt.minus(Duration.ofMinutes(terms.cancellationCutoffMinutes())),
            startsAt.plus(Duration.ofMinutes(terms.noShowGraceMinutes()))
        );
    }
}
