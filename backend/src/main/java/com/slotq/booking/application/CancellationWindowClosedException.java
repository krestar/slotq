package com.slotq.booking.application;

public final class CancellationWindowClosedException extends ProductApiException {
    public CancellationWindowClosedException() {
        super(ProductError.CANCELLATION_WINDOW_CLOSED);
    }
}
