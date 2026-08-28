package com.slotq.venue.domain;

public record BookingPolicyTerms(
    int slotDurationMinutes,
    int holdDurationMinutes,
    int cancellationCutoffMinutes,
    int noShowGraceMinutes
) {

    public BookingPolicyTerms {
        if (slotDurationMinutes <= 0) {
            throw new IllegalArgumentException("slotDurationMinutes must be positive");
        }
        if (holdDurationMinutes <= 0) {
            throw new IllegalArgumentException("holdDurationMinutes must be positive");
        }
        if (cancellationCutoffMinutes < 0) {
            throw new IllegalArgumentException("cancellationCutoffMinutes must not be negative");
        }
        if (noShowGraceMinutes < 0) {
            throw new IllegalArgumentException("noShowGraceMinutes must not be negative");
        }
    }
}
