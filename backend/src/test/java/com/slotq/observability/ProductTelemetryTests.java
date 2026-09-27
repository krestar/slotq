package com.slotq.observability;

import java.util.UUID;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductTelemetryTests {
    @Test
    void brokenProviderCannotChangeRequestOutcomeAndThreadContextIsAlwaysRestored() {
        OpenTelemetry provider = mock(OpenTelemetry.class);
        Tracer tracer = mock(Tracer.class);
        when(provider.getTracer("com.slotq.product", "1")).thenReturn(tracer);
        when(tracer.spanBuilder("product.request")).thenThrow(new IllegalStateException("private-exporter-secret"));
        var telemetry = new ProductTelemetry(provider);
        String requestId = UUID.randomUUID().toString();
        assertThatCode(() -> {
            try (var request = telemetry.request(requestId)) {
                assertThat(ProductTelemetry.currentOrigin().requestId()).isEqualTo(requestId);
                request.requestFinished("/api/reservations/{id}", "GET", 200);
            }
        }).doesNotThrowAnyException();
        assertThat(ProductTelemetry.currentOrigin()).isEqualTo(ProductTelemetry.Origin.EMPTY);
    }

    @Test
    void unknownOutcomeAndOriginFieldsNeverBecomeRawTraceAttributes() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var telemetry = new ProductTelemetry(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
            var unsafe = new ProductTelemetry.Origin("customer@example.test", "bearer-secret", "sql-bind-secret");
            assertThat(unsafe).isEqualTo(ProductTelemetry.Origin.EMPTY);
            try (var effect = telemetry.delivery(unsafe, UUID.randomUUID(), 1, 1, 1)) {
                ProductTelemetry.promotionOutcome("private-promotion-error");
                effect.finish("raw-exception-message");
            }
            assertThat(exporter.getFinishedSpanItems()).singleElement().satisfies(span -> {
                assertThat(span.getAttributes().get(AttributeKey.stringKey("slotq.outcome"))).isEqualTo("unknown");
                assertThat(span.getAttributes().get(AttributeKey.stringKey("slotq.promotion.outcome"))).isNull();
                assertThat(span.getLinks()).isEmpty();
                assertThat(span.getEvents()).isEmpty();
                assertThat(span.toString()).doesNotContain("customer@example.test", "bearer-secret", "sql-bind-secret",
                    "private-promotion-error", "raw-exception-message");
            });
        }
    }
}
