package com.slotq.events.application;

import java.util.UUID;

/** Stable opaque identity, allocated once by the server and retained on every retry. */
public record EventId(UUID value) {

    public static EventId newId() {
        return new EventId(UUID.randomUUID());
    }
}
