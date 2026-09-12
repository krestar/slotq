package com.slotq.events.application;

import java.time.Instant;

/** Boundary sequence is cutover/discovery metadata, never business or transport ordering. */
public record StoredEvent(EventEnvelope envelope, long boundarySequence, Instant recordedAt) {
}
