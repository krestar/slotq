package com.slotq.venue.domain;

import java.util.Objects;
import java.util.UUID;

public record ResourceId(UUID value) {

    public ResourceId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static ResourceId newId() {
        return new ResourceId(UUID.randomUUID());
    }
}
