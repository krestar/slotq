package com.slotq.waitlist.domain;

import java.util.Objects;
import java.util.UUID;

public record WaitlistDemandId(UUID value) {

    public WaitlistDemandId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static WaitlistDemandId newId() {
        return new WaitlistDemandId(UUID.randomUUID());
    }
}
