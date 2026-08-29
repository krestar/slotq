package com.slotq.booking.domain;

import java.util.Objects;
import java.util.UUID;

public record SlotInventoryId(UUID value) {

    public SlotInventoryId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static SlotInventoryId newId() {
        return new SlotInventoryId(UUID.randomUUID());
    }
}
