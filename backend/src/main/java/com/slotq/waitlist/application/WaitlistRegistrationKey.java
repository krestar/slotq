package com.slotq.waitlist.application;

import java.util.Map;
import java.util.UUID;

public record WaitlistRegistrationKey(UUID value) {

    public static WaitlistRegistrationKey fromHeader(String value) {
        if (value == null || value.isBlank()) {
            throw new WaitlistValidationException(Map.of(
                "Idempotency-Key", "Idempotency-Key is required and must be a UUID."
            ));
        }
        try {
            return new WaitlistRegistrationKey(UUID.fromString(value));
        } catch (IllegalArgumentException invalid) {
            throw new WaitlistValidationException(Map.of(
                "Idempotency-Key", "Idempotency-Key is required and must be a UUID."
            ));
        }
    }
}
