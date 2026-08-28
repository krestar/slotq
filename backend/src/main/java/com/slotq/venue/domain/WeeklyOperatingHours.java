package com.slotq.venue.domain;

import java.time.DayOfWeek;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record WeeklyOperatingHours(Map<DayOfWeek, DailyOperatingHours> openDays) {

    public WeeklyOperatingHours {
        Objects.requireNonNull(openDays, "openDays must not be null");
        EnumMap<DayOfWeek, DailyOperatingHours> copy = new EnumMap<>(DayOfWeek.class);
        openDays.forEach((day, hours) -> copy.put(
            Objects.requireNonNull(day, "day must not be null"),
            Objects.requireNonNull(hours, "hours must not be null")
        ));
        openDays = Collections.unmodifiableMap(copy);
    }

    public static WeeklyOperatingHours closedAllWeek() {
        return new WeeklyOperatingHours(Map.of());
    }

    public Optional<DailyOperatingHours> hoursOn(DayOfWeek day) {
        return Optional.ofNullable(openDays.get(Objects.requireNonNull(day, "day must not be null")));
    }
}
