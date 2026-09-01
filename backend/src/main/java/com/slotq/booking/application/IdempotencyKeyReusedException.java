package com.slotq.booking.application;

public final class IdempotencyKeyReusedException extends ProductApiException {
    public IdempotencyKeyReusedException() {
        super(ProductError.IDEMPOTENCY_KEY_REUSED);
    }
}
