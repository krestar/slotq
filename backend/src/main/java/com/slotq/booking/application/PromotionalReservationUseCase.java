package com.slotq.booking.application;

import java.time.Instant;
import java.util.UUID;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.ReservationState;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

/** Booking-owned boundary for a Waitlist promotional Reservation. */
public interface PromotionalReservationUseCase {

    CreateResult createHold(CreateCommand command, CreateGuard guard);

    AcceptResult accept(AcceptCommand command, AcceptGuard guard);

    ReleaseResult release(ReleaseCommand command);

    ReservationView get(VenueId venueId, ReservationId reservationId, Instant observedAt);

    enum GateDecision { PROCEED, EXISTING, NOT_WAITING, NOT_ELIGIBLE }

    enum CreateOutcome {
        CREATED, EXISTING, SKIPPED_EXISTING, NOT_WAITING, NOT_ELIGIBLE,
        CAPACITY_UNAVAILABLE, IDENTITY_CONFLICT
    }

    enum AcceptOutcome {
        ACCEPTED, ALREADY_ACCEPTED, EXPIRED, BACKING_CANCELLED,
        CAPACITY_UNAVAILABLE, TERMINAL
    }

    enum ReleaseOutcome {
        RELEASED, ALREADY_CANCELLED, EXPIRED, ALREADY_ACCEPTED, NOT_DUE, TERMINAL
    }

    @FunctionalInterface
    interface CreateGuard {
        GateDecision afterSlotLocked(SlotView slot);
    }

    @FunctionalInterface
    interface AcceptGuard {
        boolean afterSlotLocked();
    }

    record CreateCommand(
        SystemPrincipal principal,
        UUID promotionalRequestId,
        TenantId tenantId,
        VenueId venueId,
        SlotInventoryId slotInventoryId,
        PrincipalId customerPrincipalId,
        Instant startsAt,
        Instant endsAt,
        int partySize,
        Instant commandNow
    ) { }

    record AcceptCommand(
        UUID promotionalRequestId,
        VenueId venueId,
        SlotInventoryId slotInventoryId,
        ReservationId reservationId,
        Instant commandNow
    ) { }

    record ReleaseCommand(
        UUID promotionalRequestId,
        VenueId venueId,
        ReservationId reservationId,
        Instant commandNow,
        boolean expiryOnly
    ) { }

    record SlotView(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId,
        Instant startsAt,
        Instant endsAt
    ) { }

    record ReservationView(
        ReservationId id,
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId,
        PrincipalId customerPrincipalId,
        ReservationState state,
        Instant startsAt,
        Instant endsAt,
        Instant expiresAt,
        int partySize,
        int allocationQuantity,
        long appliedPolicyVersion,
        boolean allocationActive,
        boolean promotionalConfirmed
    ) { }

    record CreateResult(CreateOutcome outcome, ReservationView reservation) { }

    record AcceptResult(AcceptOutcome outcome, ReservationView reservation) { }

    record ReleaseResult(ReleaseOutcome outcome, ReservationView reservation) { }
}
