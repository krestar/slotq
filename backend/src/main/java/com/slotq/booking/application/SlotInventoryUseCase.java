package com.slotq.booking.application;

import com.slotq.booking.domain.SlotInventory;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public interface SlotInventoryUseCase {

    SlotInventory createSlot(CreateSlot command);

    SlotInventory getSlot(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId
    );

    record CreateSlot(TenantId tenantId, VenueId venueId, ResourceId resourceId, String startsAt) {
    }
}
