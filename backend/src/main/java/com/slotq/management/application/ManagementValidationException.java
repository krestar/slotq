package com.slotq.management.application;

import java.util.Map;

public class ManagementValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public ManagementValidationException(Map<String, String> fieldErrors) {
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
