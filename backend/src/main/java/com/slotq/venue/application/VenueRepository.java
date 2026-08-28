package com.slotq.venue.application;

import java.util.Optional;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueId;

public interface VenueRepository {

    void create(Venue venue);

    Optional<Venue> find(TenantId tenantId, VenueId venueId);

    Optional<Venue> findForUpdate(TenantId tenantId, VenueId venueId);

    void updateConfiguration(Venue venue);

    void appendPolicy(TenantId tenantId, VenueId venueId, BookingPolicy policy);
}
