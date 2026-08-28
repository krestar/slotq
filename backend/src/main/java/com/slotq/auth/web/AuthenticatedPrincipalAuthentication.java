package com.slotq.auth.web;

import java.util.List;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import org.springframework.security.authentication.AbstractAuthenticationToken;

final class AuthenticatedPrincipalAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedPrincipal principal;

    AuthenticatedPrincipalAuthentication(AuthenticatedPrincipal principal) {
        super(List.of());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public AuthenticatedPrincipal getPrincipal() {
        return principal;
    }
}
