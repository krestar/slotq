package com.slotq.booking.application;

import java.time.Instant;
import java.util.Optional;

import com.slotq.booking.domain.SlotInventory;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public interface SlotInventoryRepository {

    void save(SlotInventory slotInventory);

    Optional<SlotInventory> find(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId
    );

    boolean overlaps(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        Instant startsAt,
        Instant endsAt
    );
}
