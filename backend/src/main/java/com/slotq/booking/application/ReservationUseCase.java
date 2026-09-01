package com.slotq.booking.application;

import java.time.Instant;
import java.util.Optional;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.ReservationState;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.domain.VenueId;

public interface ReservationUseCase {

    ReservationDetails createHold(CreateHold command);

    ReservationDetails getReservation(
        VenueId venueId,
        ReservationId reservationId,
        AuthenticatedPrincipal principal
    );

    ReservationDetails transition(
        VenueId venueId,
        ReservationId reservationId,
        AuthenticatedPrincipal principal,
        ReservationCommand command
    );

    record CreateHold(
        VenueId venueId,
        SlotInventoryId slotInventoryId,
        AuthenticatedPrincipal principal,
        int partySize,
        Optional<HoldIdempotencyKey> idempotencyKey
    ) {
        public CreateHold(
            VenueId venueId,
            SlotInventoryId slotInventoryId,
            AuthenticatedPrincipal principal,
            int partySize
        ) {
            this(venueId, slotInventoryId, principal, partySize, Optional.empty());
        }

        public CreateHold {
            idempotencyKey = idempotencyKey == null ? Optional.empty() : idempotencyKey;
        }
    }

    record ReservationDetails(
        Reservation reservation,
        Instant endsAt,
        ReservationState effectiveState
    ) { }
}
