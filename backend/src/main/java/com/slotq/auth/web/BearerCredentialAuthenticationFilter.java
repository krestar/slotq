package com.slotq.auth.web;

import java.io.IOException;

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

    BearerCredentialAuthenticationFilter(ObjectProvider<BearerCredentialResolver> resolverProvider) {
        this.resolverProvider = resolverProvider;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        BearerCredentialResolver resolver = resolverProvider.getIfAvailable();
        if (resolver != null && authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String credential = authorization.substring(BEARER_PREFIX.length());
            resolver.resolve(credential)
                .map(AuthenticatedPrincipalAuthentication::new)
                .ifPresent(authentication -> SecurityContextHolder.getContext().setAuthentication(authentication));
        }
        filterChain.doFilter(request, response);
    }
}
