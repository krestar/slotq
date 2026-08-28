package com.slotq.venue.domain;

import java.util.Objects;
import java.util.UUID;

public record VenueId(UUID value) {

    public VenueId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static VenueId newId() {
        return new VenueId(UUID.randomUUID());
    }
}
