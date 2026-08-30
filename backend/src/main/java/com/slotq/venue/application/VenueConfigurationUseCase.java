package com.slotq.venue.application;

import java.time.Instant;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.PolicyDeadlines;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueId;
import com.slotq.venue.domain.VenueStatus;
import com.slotq.venue.domain.WeeklyOperatingHours;

public interface VenueConfigurationUseCase {

    Venue createVenue(CreateVenue command);

    Venue getVenue(TenantId tenantId, VenueId venueId);

    Venue updateVenue(UpdateVenue command);

    BookingPolicy updateBookingPolicy(UpdateBookingPolicy command);

    PolicyDeadlines applyCurrentPolicy(TenantId tenantId, VenueId venueId, Instant startsAt);

    record CreateVenue(
        TenantId tenantId,
        String name,
        String timezone,
        WeeklyOperatingHours operatingHours,
        BookingPolicyTerms initialPolicy
    ) {
    }

    record UpdateVenue(
        TenantId tenantId,
        VenueId venueId,
        String name,
        VenueStatus status,
        String timezone,
        WeeklyOperatingHours operatingHours
    ) {
    }

    record UpdateBookingPolicy(TenantId tenantId, VenueId venueId, BookingPolicyTerms terms) {
    }
}
