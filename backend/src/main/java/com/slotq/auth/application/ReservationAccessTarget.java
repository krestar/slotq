package com.slotq.auth.application;

import java.util.Objects;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;

public record ReservationAccessTarget(
    TenantId tenantId,
    VenueId venueId,
    PrincipalId customerPrincipalId
) {
    public ReservationAccessTarget {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(venueId, "venueId must not be null");
        Objects.requireNonNull(customerPrincipalId, "customerPrincipalId must not be null");
    }
}
