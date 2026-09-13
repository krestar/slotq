package com.slotq.venue.application;

import java.time.ZoneId;
import java.util.Optional;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;

public interface WaitlistVenueQuery {

    Optional<VenueScope> find(VenueId venueId);

    record VenueScope(TenantId tenantId, VenueId venueId, ZoneId timezone) { }
}
