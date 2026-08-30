package com.slotq.booking.application;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.domain.ReservationId;
import com.slotq.venue.domain.VenueId;

public interface ReservationExpiryUseCase {

    ReservationUseCase.ReservationDetails expire(
        VenueId venueId,
        ReservationId reservationId,
        SystemPrincipal principal
    );
}
