package com.slotq.booking.application;

import java.util.Optional;

import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.tenancy.domain.TenantStatus;
import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import com.slotq.venue.domain.VenueStatus;

/** Current-read eligibility and policy view for a locked promotional target Slot. */
public interface PromotionalReservationContextQuery {

    Optional<Context> findCurrent(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId
    );

    record Context(
        TenantStatus tenantStatus,
        VenueStatus venueStatus,
        Resource resource,
        BookingPolicy currentPolicy
    ) { }
}
