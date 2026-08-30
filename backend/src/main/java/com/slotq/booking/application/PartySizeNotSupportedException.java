package com.slotq.booking.application;

public final class PartySizeNotSupportedException extends ProductApiException {
    public PartySizeNotSupportedException() {
        super(ProductError.PARTY_SIZE_NOT_SUPPORTED);
    }
}
