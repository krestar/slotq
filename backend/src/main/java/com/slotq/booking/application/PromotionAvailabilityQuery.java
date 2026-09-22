package com.slotq.booking.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

/** Nonlocking discovery hints only. Never an admission to Booking capacity. */
public interface PromotionAvailabilityQuery {
    List<Target> availableAfter(SlotInventoryId after, Instant observedAt, int limit);
    Optional<Target> observe(VenueId venueId, SlotInventoryId slotId, Instant observedAt);

    record Target(TenantId tenantId, VenueId venueId, ResourceId resourceId, SlotInventoryId slotId,
                  Instant startsAt, Instant endsAt, int seatingCapacity, boolean available) { }
}
