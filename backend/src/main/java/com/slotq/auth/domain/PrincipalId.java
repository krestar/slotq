package com.slotq.auth.domain;

import java.util.Objects;
import java.util.UUID;

public record PrincipalId(UUID value) {

    public PrincipalId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static PrincipalId newId() {
        return new PrincipalId(UUID.randomUUID());
    }
}
