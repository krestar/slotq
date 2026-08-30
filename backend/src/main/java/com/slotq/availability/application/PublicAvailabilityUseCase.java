package com.slotq.availability.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public interface PublicAvailabilityUseCase {

    List<VenueSummary> getVenues();

    Availability getAvailability(VenueId venueId, LocalDate date, int partySize);

    record VenueSummary(VenueId venueId, String name, ZoneId timezone) { }

    record Availability(
        VenueId venueId,
        ZoneId timezone,
        LocalDate date,
        List<AvailabilityItem> items
    ) {
        public Availability {
            items = List.copyOf(items);
        }
    }

    record AvailabilityItem(
        SlotInventoryId slotInventoryId,
        ResourceId resourceId,
        String resourceName,
        Instant startsAt,
        Instant endsAt,
        int seatingCapacity,
        int capacity,
        int occupied,
        int available
    ) { }
}
