package com.slotq.booking.application;

public final class HoldExpiredException extends ProductApiException {
    public HoldExpiredException() {
        super(ProductError.HOLD_EXPIRED);
    }
}
