package com.slotq.observability.web;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.slotq.observability.ProductTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    private static final Logger LOG = LoggerFactory.getLogger("slotq.telemetry");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private final ProductTelemetry telemetry;
    private final MeterRegistry meters;

    public RequestCorrelationFilter(ProductTelemetry telemetry, MeterRegistry meters) {
        this.telemetry = telemetry;
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        // No incoming correlation header or remote parent is accepted, including on authentication failures.
        String requestId = UUID.randomUUID().toString();
        response.setHeader(HEADER, requestId);
        if (request.getRequestURI().equals(request.getContextPath() + "/mcp")) {
            // The async MCP boundary records its actual outcome; servlet return is not completion.
            chain.doFilter(request, response);
            return;
        }
        long start = System.nanoTime();
        boolean completed = false;
        try (ProductTelemetry.Operation operation = telemetry.request(requestId)) {
            try {
                chain.doFilter(request, response);
                completed = true;
            } finally {
                int status = completed ? response.getStatus() : 500;
                String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                String route = pattern instanceof String value ? value : "UNMATCHED";
                String outcome = RequestOutcome.get(request, status);
                operation.requestFinished(route, method, status);
                try {
                    Timer.builder("slotq.http.requests")
                        .serviceLevelObjectives(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(500),
                            java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(10))
                        .tags("route", route, "method", method, "status_class", status / 100 + "xx", "outcome", outcome)
                        .register(meters).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                    LOG.atInfo().addKeyValue("signal", "http_request")
                        .addKeyValue("request_id", requestId).addKeyValue("trace_id", operation.traceId())
                        .addKeyValue("route", route).addKeyValue("method", method)
                        .addKeyValue("status", status).addKeyValue("outcome", outcome)
                        .log("Product request completed");
                } catch (RuntimeException ignored) {
                    // Observability cannot change the already determined Product result.
                }
            }
        }
    }
}
