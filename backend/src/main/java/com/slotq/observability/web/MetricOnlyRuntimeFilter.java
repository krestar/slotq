package com.slotq.observability.web;

import java.io.IOException;
import java.util.Set;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Isolated execution roles expose only the existing authenticated scrape endpoint. */
@Component
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class MetricOnlyRuntimeFilter extends OncePerRequestFilter {
    private final boolean isolated;
    public MetricOnlyRuntimeFilter(@Value("${slotq.events.runtime-role:product}") String role) {
        isolated=Set.of("relay","consumer").contains(role);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,
            FilterChain chain) throws IOException,ServletException {
        if(isolated && !(request.getMethod().equals("GET")
                && request.getRequestURI().equals(request.getContextPath()+"/actuator/prometheus"))) {
            response.setStatus(404);response.setHeader("Cache-Control","no-store");return;
        }
        chain.doFilter(request,response);
    }
}
