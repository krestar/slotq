package com.slotq.booking.application;

import java.util.Optional;

public record HoldIdempotencyKey(String value) {

    public static final int MAX_LENGTH = 255;

    public HoldIdempotencyKey {
        if (!isValid(value)) {
            throw new HoldIdempotencyValidationException();
        }
    }

    public static Optional<HoldIdempotencyKey> fromHeader(String value) {
        return value == null ? Optional.empty() : Optional.of(new HoldIdempotencyKey(value));
    }

    private static boolean isValid(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            return false;
        }
        return value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e);
    }
}
