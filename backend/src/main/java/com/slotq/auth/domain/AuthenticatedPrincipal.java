package com.slotq.auth.domain;

import java.util.Objects;

public record AuthenticatedPrincipal(PrincipalId principalId, ConsumerRestriction restriction) {

    public AuthenticatedPrincipal(PrincipalId principalId) {
        this(principalId, null);
    }

    public AuthenticatedPrincipal {
        Objects.requireNonNull(principalId, "principalId must not be null");
    }
}
