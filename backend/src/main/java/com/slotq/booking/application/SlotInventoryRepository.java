package com.slotq.booking.application;

import java.time.Instant;
import java.util.List;
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

    Optional<SlotInventory> find(VenueId venueId, SlotInventoryId slotInventoryId);

    Optional<SlotInventory> findForUpdate(VenueId venueId, SlotInventoryId slotInventoryId);

    List<SlotInventory> findAll(TenantId tenantId, VenueId venueId, Instant startsAt, Instant endsAt);

    boolean overlaps(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        Instant startsAt,
        Instant endsAt
    );
}
