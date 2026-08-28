package com.slotq.auth.domain;

import java.util.Objects;

public record AuthenticatedPrincipal(PrincipalId principalId) {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(principalId, "principalId must not be null");
    }
}
