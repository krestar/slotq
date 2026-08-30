package com.slotq.venue.domain;

import java.time.ZoneId;
import java.util.Objects;

import com.slotq.tenancy.domain.TenantId;

public record Venue(
    VenueId id,
    TenantId tenantId,
    String name,
    VenueStatus status,
    ZoneId timezone,
    WeeklyOperatingHours operatingHours,
    BookingPolicy currentPolicy
) {

    public Venue {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        name = Objects.requireNonNull(name, "name must not be null").strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException("name must not exceed 100 characters");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(timezone, "timezone must not be null");
        Objects.requireNonNull(operatingHours, "operatingHours must not be null");
        Objects.requireNonNull(currentPolicy, "currentPolicy must not be null");
    }

    public Venue withConfiguration(
        String newName,
        VenueStatus newStatus,
        ZoneId newTimezone,
        WeeklyOperatingHours newOperatingHours
    ) {
        return new Venue(id, tenantId, newName, newStatus, newTimezone, newOperatingHours, currentPolicy);
    }

    public Venue withPolicy(BookingPolicy policy) {
        return new Venue(id, tenantId, name, status, timezone, operatingHours, policy);
    }
}
