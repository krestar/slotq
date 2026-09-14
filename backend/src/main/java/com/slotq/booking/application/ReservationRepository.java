package com.slotq.booking.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public interface ReservationRepository {

    void save(Reservation reservation);

    Optional<Reservation> find(VenueId venueId, ReservationId reservationId);

    Optional<Reservation> findForUpdate(VenueId venueId, ReservationId reservationId);

    /**
     * Uses a current locking read so a duplicate transaction can observe the Reservation
     * committed by the transaction that won the idempotency-key insert race.
     */
    Optional<Reservation> findCurrent(VenueId venueId, ReservationId reservationId);

    Optional<Reservation> findPromotionalCurrent(TenantId tenantId, UUID promotionalRequestId);

    List<Reservation> findAll(TenantId tenantId, VenueId venueId, Instant startsAt, Instant endsAt);

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

    /**
     * Uses the same effective predicate, excluding the Reservation attempting CONFIRM.
     * The command must own the Slot lock before any consistent read in its transaction.
     */
    boolean existsOtherEffectiveCapacityConsumer(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId,
        ReservationId excludedReservationId,
        Instant now
    );

    boolean existsEffectiveCapacityConsumerCurrent(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId,
        ReservationId excludedReservationId,
        Instant now
    );
}
