package com.slotq.auth.domain;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import java.util.Objects;
import java.util.UUID;

/** Auth-owned attenuation, never constructed from HTTP claims. */
public record ConsumerRestriction(TenantId tenantId, VenueId venueId, boolean ownReservationOnly,
        UUID productCredentialId) {
    public ConsumerRestriction(TenantId tenantId, VenueId venueId, boolean ownReservationOnly) {
        this(tenantId, venueId, ownReservationOnly, null);
    }
    public ConsumerRestriction {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(venueId);
    }
}
