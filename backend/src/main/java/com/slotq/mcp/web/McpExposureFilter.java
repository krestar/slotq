package com.slotq.mcp.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public final class McpExposureFilter extends OncePerRequestFilter {
    private final boolean active;
    public McpExposureFilter(@Value("${slotq.mcp.enabled:false}") boolean enabled,
            @Value("${slotq.events.runtime-role:product}") String role) { active=enabled && role.equals("product"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if((path.equals("/mcp") || path.startsWith("/mcp/")) && (!active || !path.equals("/mcp"))) {
            response.setStatus(404);response.setHeader("Cache-Control","no-store");return;
        }
        chain.doFilter(request,response);
    }
}
