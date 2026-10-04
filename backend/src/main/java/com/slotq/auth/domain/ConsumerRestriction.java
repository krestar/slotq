package com.slotq.auth.domain;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import java.util.Objects;

/** Auth-owned attenuation, never constructed from HTTP claims. */
public record ConsumerRestriction(TenantId tenantId, VenueId venueId, boolean ownReservationOnly) {
    public ConsumerRestriction {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(venueId);
    }
}
