package com.slotq.availability.application;

import java.util.Map;

public final class AvailabilityValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public AvailabilityValidationException(Map<String, String> fieldErrors) {
        super("Availability request validation failed");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
