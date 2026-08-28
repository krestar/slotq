package com.slotq.auth.application;

public final class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException() {
        super("Resource was not found");
    }
}
