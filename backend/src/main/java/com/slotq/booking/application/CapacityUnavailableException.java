package com.slotq.booking.application;

public final class CapacityUnavailableException extends ProductApiException {
    public CapacityUnavailableException() {
        super(ProductError.CAPACITY_UNAVAILABLE);
    }
}
