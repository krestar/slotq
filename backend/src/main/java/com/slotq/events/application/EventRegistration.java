package com.slotq.events.application;

import java.util.UUID;

/** Durable target provenance; registration identity is the generation. */
public record EventRegistration(UUID id, ConsumerRoute route, long activationBoundary, Long deactivationBoundary) {
    public boolean active() { return deactivationBoundary == null; }
}
