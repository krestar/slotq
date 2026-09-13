package com.slotq.waitlist.application;

import java.util.Map;

public class WaitlistValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public WaitlistValidationException(Map<String, String> fieldErrors) {
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
