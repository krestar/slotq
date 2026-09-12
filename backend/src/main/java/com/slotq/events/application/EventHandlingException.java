package com.slotq.events.application;

import java.util.Objects;

/** A consumer may explicitly classify a transient failure or a rejected payload/ownership. */
public final class EventHandlingException extends RuntimeException {
    private final DeliveryFailure failure;

    public EventHandlingException(DeliveryFailure failure) {
        super(Objects.requireNonNull(failure, "failure").name());
        this.failure = failure;
    }

    public DeliveryFailure failure() {
        return failure;
    }
}
