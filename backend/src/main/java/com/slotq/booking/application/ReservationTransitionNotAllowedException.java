package com.slotq.booking.application;

public final class ReservationTransitionNotAllowedException extends ProductApiException {
    public ReservationTransitionNotAllowedException() {
        super(ProductError.RESERVATION_TRANSITION_NOT_ALLOWED);
    }
}
