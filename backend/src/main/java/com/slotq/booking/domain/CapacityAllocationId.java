package com.slotq.booking.domain;

import java.util.Objects;
import java.util.UUID;

public record CapacityAllocationId(UUID value) {

    public CapacityAllocationId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static CapacityAllocationId newId() {
        return new CapacityAllocationId(UUID.randomUUID());
    }
}
