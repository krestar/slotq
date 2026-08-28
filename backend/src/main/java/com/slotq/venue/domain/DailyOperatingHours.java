package com.slotq.venue.domain;

import java.time.LocalTime;
import java.util.Objects;

public record DailyOperatingHours(LocalTime opensAt, LocalTime closesAt) {

    public DailyOperatingHours {
        Objects.requireNonNull(opensAt, "opensAt must not be null");
        Objects.requireNonNull(closesAt, "closesAt must not be null");
        if (!opensAt.isBefore(closesAt)) {
            throw new IllegalArgumentException("Operating hours must satisfy opensAt < closesAt");
        }
    }
}
