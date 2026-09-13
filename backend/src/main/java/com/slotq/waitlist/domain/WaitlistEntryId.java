package com.slotq.waitlist.domain;

import java.util.Objects;
import java.util.UUID;

public record WaitlistEntryId(UUID value) {

    public WaitlistEntryId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static WaitlistEntryId newId() {
        return new WaitlistEntryId(UUID.randomUUID());
    }
}
