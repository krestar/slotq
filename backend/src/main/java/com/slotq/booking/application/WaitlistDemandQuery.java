package com.slotq.booking.application;

import java.time.Instant;
import java.util.Optional;

import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public interface WaitlistDemandQuery {

    Optional<RegistrationTarget> findRegistrationTargetForUpdate(
        VenueId venueId,
        SlotInventoryId slotInventoryId,
        int partySize
    );

    Optional<SlotTarget> findSlot(VenueId venueId, SlotInventoryId slotInventoryId);

    record RegistrationTarget(
        TenantId tenantId,
        VenueId venueId,
        ResourceId originalResourceId,
        SlotInventoryId originalSlotInventoryId,
        Instant startsAt,
        Instant endsAt,
        boolean tenantActive,
        boolean venueActive,
        boolean eligibleResourceExists
    ) { }

    record SlotTarget(
        TenantId tenantId,
        VenueId venueId,
        SlotInventoryId slotInventoryId,
        Instant startsAt,
        Instant endsAt,
        boolean resourceActive,
        int seatingCapacity
    ) {
        public boolean eligible(int partySize) {
            return resourceActive && partySize <= seatingCapacity;
        }
    }
}
