package com.slotq.observability;

import java.time.Duration;
import java.util.Collection;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.common.CompletableResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration(proxyBeanMethods = false)
public class TracingConfiguration {
    @Bean(destroyMethod = "close")
    SdkTracerProvider productTracerProvider(SpanExporter exporter, MeterRegistry registry,
            @Value("${slotq.telemetry.sampling-probability:1.0}") double probability) {
        if (!Double.isFinite(probability) || probability < 0 || probability > 1)
            throw new IllegalArgumentException("Telemetry sampling probability must be between zero and one");
        var builder = SdkTracerProvider.builder().setResource(Resource.create(
            Attributes.of(AttributeKey.stringKey("service.name"), "slotq-product")))
            .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(probability)));
        if (!(exporter instanceof DisabledExporter)) {
            builder.addSpanProcessor(BatchSpanProcessor.builder(new CountingExporter(exporter, registry))
                .setMaxQueueSize(2048).setMaxExportBatchSize(256)
                .setScheduleDelay(Duration.ofSeconds(1)).setExporterTimeout(Duration.ofSeconds(2)).build());
        }
        return builder.build();
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean(SpanExporter.class)
    SpanExporter productSpanExporter(@Value("${slotq.telemetry.otlp-endpoint:}") String endpoint,
            @Value("${slotq.telemetry.otlp-authorization:}") String authorization) {
        if (endpoint.isBlank()) return new DisabledExporter();
        var exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint).setTimeout(Duration.ofSeconds(2));
        if (!authorization.isBlank()) exporter.addHeader("Authorization", authorization);
        return exporter.build();
    }

    private static final class DisabledExporter implements SpanExporter {
        @Override public CompletableResultCode export(Collection<SpanData> spans) { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }

    static final class CountingExporter implements SpanExporter {
        private final SpanExporter delegate;
        private final MeterRegistry registry;
        CountingExporter(SpanExporter delegate, MeterRegistry registry) { this.delegate = delegate; this.registry = registry; }
        @Override public CompletableResultCode export(Collection<SpanData> spans) {
            int size = spans.size();
            try {
                var result = delegate.export(spans);
                result.whenComplete(() -> count(result.isSuccess() ? "success" : "failure", size));
                return result;
            } catch (RuntimeException ignored) {
                count("failure", size);
                return CompletableResultCode.ofFailure();
            }
        }
        private void count(String outcome, int count) {
            try { registry.counter("slotq.telemetry.export.spans", "outcome", outcome).increment(count); }
            catch (RuntimeException ignored) { }
        }
        @Override public CompletableResultCode flush() { return delegate.flush(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
    }

    @Bean
    OpenTelemetry productOpenTelemetry(SdkTracerProvider provider) {
        // No global registration, inbound extraction, auto instrumentation or environment resource capture.
        return OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    }
}
