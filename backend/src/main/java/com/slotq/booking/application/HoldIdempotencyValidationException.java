package com.slotq.booking.application;

import java.util.Map;

public final class HoldIdempotencyValidationException extends RuntimeException {

    public Map<String, String> fieldErrors() {
        return Map.of(
            "Idempotency-Key",
            "must contain 1 to 255 visible ASCII characters"
        );
    }
}
