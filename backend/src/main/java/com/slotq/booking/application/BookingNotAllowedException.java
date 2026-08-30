package com.slotq.booking.application;

public final class BookingNotAllowedException extends ProductApiException {
    public BookingNotAllowedException() {
        super(ProductError.BOOKING_NOT_ALLOWED);
    }
}
