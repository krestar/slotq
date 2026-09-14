package com.slotq.waitlist.domain;

import java.util.Objects;
import java.util.UUID;

public record WaitlistOfferId(UUID value) {
    public WaitlistOfferId { Objects.requireNonNull(value, "value must not be null"); }
    public static WaitlistOfferId newId() { return new WaitlistOfferId(UUID.randomUUID()); }
}
