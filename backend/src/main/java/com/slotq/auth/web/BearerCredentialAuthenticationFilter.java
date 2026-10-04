package com.slotq.auth.web;

import java.io.IOException;
import com.slotq.auth.access.ProductCredentialAccess;
import com.slotq.auth.access.AccessFailure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
final class BearerCredentialAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ObjectProvider<BearerCredentialResolver> resolverProvider;
    private final ObjectProvider<ProductCredentialAccess> productAccess;

    BearerCredentialAuthenticationFilter(ObjectProvider<BearerCredentialResolver> resolverProvider,
            ObjectProvider<ProductCredentialAccess> productAccess) {
        this.resolverProvider = resolverProvider;
        this.productAccess = productAccess;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        BearerCredentialResolver resolver = resolverProvider.getIfAvailable();
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String credential = authorization.substring(BEARER_PREFIX.length());
            // Dev credentials remain a local/test-only alternative, never an MCP identity.
            var principal = resolver == null ? java.util.Optional.<com.slotq.auth.domain.AuthenticatedPrincipal>empty()
                : resolver.resolve(credential);
            try {
                if (principal.isEmpty() && productAccess.getIfAvailable() != null) {
                    principal = productAccess.getObject().authenticateProduct(credential, request.getMethod(),
                        request.getRequestURI().substring(request.getContextPath().length()));
                }
            } catch (AccessFailure failure) {
                response.setStatus(failure.reason() == AccessFailure.Reason.UNAVAILABLE ? 503 : 401);
                response.setHeader("Cache-Control", "no-store");
                return;
            }
            principal
                .map(AuthenticatedPrincipalAuthentication::new)
                .ifPresent(authentication -> SecurityContextHolder.getContext().setAuthentication(authentication));
        }
        filterChain.doFilter(request, response);
    }
}
