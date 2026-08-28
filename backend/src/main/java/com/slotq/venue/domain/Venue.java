package com.slotq.venue.domain;

import java.time.ZoneId;
import java.util.Objects;

import com.slotq.tenancy.domain.TenantId;

public record Venue(
    VenueId id,
    TenantId tenantId,
    VenueStatus status,
    ZoneId timezone,
    WeeklyOperatingHours operatingHours,
    BookingPolicy currentPolicy
) {

    public Venue {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(timezone, "timezone must not be null");
        Objects.requireNonNull(operatingHours, "operatingHours must not be null");
        Objects.requireNonNull(currentPolicy, "currentPolicy must not be null");
    }

    public Venue withConfiguration(
        VenueStatus newStatus,
        ZoneId newTimezone,
        WeeklyOperatingHours newOperatingHours
    ) {
        return new Venue(id, tenantId, newStatus, newTimezone, newOperatingHours, currentPolicy);
    }

    public Venue withPolicy(BookingPolicy policy) {
        return new Venue(id, tenantId, status, timezone, operatingHours, policy);
    }
}
