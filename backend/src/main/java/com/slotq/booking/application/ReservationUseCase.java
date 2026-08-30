package com.slotq.booking.application;

import java.time.Instant;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
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

    record CreateHold(
        VenueId venueId,
        SlotInventoryId slotInventoryId,
        PrincipalId customerPrincipalId,
        int partySize
    ) { }

    record ReservationDetails(
        Reservation reservation,
        Instant endsAt,
        ReservationState effectiveState
    ) { }
}
