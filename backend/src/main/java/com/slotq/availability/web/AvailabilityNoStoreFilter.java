package com.slotq.availability.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class AvailabilityNoStoreFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (HttpMethod.GET.matches(request.getMethod())
            && request.getRequestURI().matches("^/api/v1/venues/[^/]+/availability$")) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        filterChain.doFilter(request, response);
    }
}
