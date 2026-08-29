package com.slotq.booking.application;

import java.time.Instant;
import java.util.Optional;

import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public interface ReservationRepository {

    void save(Reservation reservation);

    Optional<Reservation> find(TenantId tenantId, VenueId venueId, ReservationId reservationId);

    /**
     * Returns whether the slot has an active unit whose effective Reservation state consumes capacity.
     * Implementations count unexpired HELD, CONFIRMED and CHECKED_IN reservations only.
     */
    boolean existsEffectiveCapacityConsumer(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId,
        Instant now
    );
}
