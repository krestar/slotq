package com.slotq.auth.web;

import java.util.Optional;

import com.slotq.auth.domain.AuthenticatedPrincipal;

public interface BearerCredentialResolver {

    Optional<AuthenticatedPrincipal> resolve(String credential);
}
