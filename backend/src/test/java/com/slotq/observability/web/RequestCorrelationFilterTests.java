package com.slotq.observability.web;

import java.sql.SQLException;
import java.util.UUID;

import com.slotq.observability.ProductTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class RequestCorrelationFilterTests {
    @Test
    void syntheticIdentitiesSecretsAndRawUrlsNeverBecomeTelemetry(CapturedOutput output) throws Exception {
        var exporter = InMemorySpanExporter.create();
        var meters = new SimpleMeterRegistry();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var telemetry = new ProductTelemetry(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            var filter = new RequestCorrelationFilter(telemetry, meters);
            int initialSeries = 0;
            for (int i = 0; i < 100; i++) {
                String identity = UUID.randomUUID().toString();
                var request = new MockHttpServletRequest("POST", "/api/v1/venues/" + identity + "/reservations/holds");
                request.setQueryString("contact=private-contact@example.test");
                request.setContent("private-request-body-and-SQL-parameter".getBytes());
                request.addHeader("Authorization", "Bearer private-product-token");
                request.addHeader("Idempotency-Key", identity);
                request.addHeader("X-Request-ID", "untrusted-request-id");
                request.addHeader("traceparent", "00-11111111111111111111111111111111-2222222222222222-01");
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> {
                    request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                        "/api/v1/venues/{venueId}/reservations/holds");
                    assertThat(ProductTelemetry.currentOrigin().requestId()).isEqualTo(response.getHeader("X-Request-ID"));
                    RequestOutcome.problem(request, "CAPACITY_UNAVAILABLE");
                    response.setStatus(409);
                });
                assertThat(response.getHeader("X-Request-ID")).isNotEqualTo(identity);
                assertThat(ProductTelemetry.currentOrigin()).isEqualTo(ProductTelemetry.Origin.EMPTY);
                assertThat(meters.getMeters().toString()).doesNotContain(identity);
                if (i == 0) initialSeries = meters.getMeters().size();
                assertThat(meters.getMeters()).hasSize(initialSeries);
            }
            assertThat(initialSeries).isLessThanOrEqualTo(6); // One timer and five fixed SLO buckets.
            assertThat(meters.get("slotq.http.requests").timer().count()).isEqualTo(100);
            assertThat(exporter.getFinishedSpanItems()).hasSize(100).allSatisfy(span -> {
                assertThat(span.getParentSpanId()).isEqualTo("0000000000000000");
                assertThat(span.getTraceId()).isNotEqualTo("11111111111111111111111111111111");
                assertThat(span.getAttributes().asMap().toString()).doesNotContain("private-", "untrusted-", "tenantId", "principalId");
                assertThat(span.getEvents()).isEmpty();
            });
            assertThat(output.getAll()).doesNotContain("private-product-token", "private-contact@example.test",
                "private-request-body-and-SQL-parameter", "untrusted-request-id");
        }
    }

    @Test
    void unhandledFailurePreservesTheExceptionAndCorrelation(CapturedOutput output) {
        var meters = new SimpleMeterRegistry();
        var filter = new RequestCorrelationFilter(ProductTelemetry.noop(), meters);
        var request = new MockHttpServletRequest("GET", "/private-unmatched-path");
        var response = new MockHttpServletResponse();
        var failure = new ServletException("sensitive-error-message");
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> { throw failure; }))
            .isSameAs(failure);
        assertThat(response.getHeader("X-Request-ID")).isNotNull();
        assertThat(meters.get("slotq.http.requests").tags("route", "UNMATCHED", "status_class", "5xx").timer().count())
            .isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("sensitive-error-message", "private-unmatched-path");
    }

    @Test
    void scrapeConfigurationIsFailClosedAndNeverAcceptsProductCredential() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer a-product-credential");
        assertThat(new ScrapeCredential("").matches(request)).isFalse();
        assertThat(new ScrapeCredential("short").matches(request)).isFalse();
        assertThat(new ScrapeCredential("machine-credential-with-at-least-32-chars").matches(request)).isFalse();
    }

    @Test
    void mysqlFailuresHaveFiniteCodesAndDoNotExposeTheMessage() {
        var request = new MockHttpServletRequest();
        RequestOutcome.failure(request, new IllegalStateException(new SQLException("secret SQL parameters", "40001", 1213)));
        RequestOutcome.problem(request, "INTERNAL_ERROR");
        assertThat(RequestOutcome.get(request, 500)).isEqualTo("DB_DEADLOCK");
        request = new MockHttpServletRequest();
        RequestOutcome.failure(request, new SQLException("secret", "HY000", 1205));
        assertThat(RequestOutcome.get(request, 500)).isEqualTo("DB_LOCK_TIMEOUT");
        request = new MockHttpServletRequest();
        RequestOutcome.failure(request, new SQLException("secret", "08001", 0));
        assertThat(RequestOutcome.get(request, 500)).isEqualTo("DB_CONNECTION");
    }
}
