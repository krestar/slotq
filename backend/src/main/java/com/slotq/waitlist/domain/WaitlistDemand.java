package com.slotq.waitlist.domain;

import java.time.Instant;
import java.util.Objects;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;

public record WaitlistDemand(
    WaitlistDemandId id,
    TenantId tenantId,
    VenueId venueId,
    Instant startsAt,
    Instant endsAt,
    int partySize
) {

    public WaitlistDemand {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(venueId, "venueId must not be null");
        Objects.requireNonNull(startsAt, "startsAt must not be null");
        Objects.requireNonNull(endsAt, "endsAt must not be null");
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        if (partySize <= 0) {
            throw new IllegalArgumentException("partySize must be positive");
        }
    }

    public record Identity(
        TenantId tenantId,
        VenueId venueId,
        Instant startsAt,
        Instant endsAt,
        int partySize
    ) {
        public Identity {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(venueId, "venueId must not be null");
            Objects.requireNonNull(startsAt, "startsAt must not be null");
            Objects.requireNonNull(endsAt, "endsAt must not be null");
            if (!startsAt.isBefore(endsAt) || partySize <= 0) {
                throw new IllegalArgumentException("invalid waitlist demand identity");
            }
        }
    }
}
