package com.slotq.auth.application;

public final class AccessDeniedException extends RuntimeException {

    public AccessDeniedException() {
        super("Action is not allowed");
    }
}
