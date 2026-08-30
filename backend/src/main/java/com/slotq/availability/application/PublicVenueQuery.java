package com.slotq.availability.application;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;

/**
 * Public read-model port. Unlike aggregate repositories, this query is intentionally
 * cross-tenant and exposes only the fields needed to discover an active public Venue.
 */
public interface PublicVenueQuery {

    List<PublicVenue> findAllActive();

    Optional<PublicVenue> findActive(VenueId venueId);

    record PublicVenue(
        TenantId tenantId,
        VenueId venueId,
        String name,
        ZoneId timezone
    ) { }
}
