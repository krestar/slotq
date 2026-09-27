package com.slotq.observability;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TracingExporterIsolationTests {
    @Test
    void realOtlpExporterTimeoutAndQueueSaturationNeverWaitOnProductThread() throws Exception {
        CountDownLatch collectorEntered = new CountDownLatch(1);
        CountDownLatch collectorRelease = new CountDownLatch(1);
        HttpServer collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        collector.createContext("/v1/traces", exchange -> {
            collectorEntered.countDown();
            try {
                collectorRelease.await(15, TimeUnit.SECONDS);
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(503, -1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        collector.start();
        var registry = new SimpleMeterRegistry();
        var config = new TracingConfiguration();
        var exporter = config.productSpanExporter("http://127.0.0.1:" + collector.getAddress().getPort() + "/v1/traces",
            "Bearer synthetic-collector-secret");
        try (var provider = config.productTracerProvider(exporter, registry, 1.0);
             var executor = Executors.newSingleThreadExecutor()) {
            var telemetry = new ProductTelemetry(config.productOpenTelemetry(provider));
            try (var first = telemetry.request(UUID.randomUUID().toString())) { first.finish("success"); }
            assertThat(collectorEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var product = executor.submit(() -> {
                for (int index = 0; index < 4096; index++) {
                    provider.get("queue-saturation-fixture").spanBuilder("test.queued").startSpan().end();
                }
                try (var request = telemetry.request(UUID.randomUUID().toString())) { request.finish("success"); }
                return ProductTelemetry.currentOrigin();
            });
            try {
                assertThat(product.get(5, TimeUnit.SECONDS)).isEqualTo(ProductTelemetry.Origin.EMPTY);
                assertThat(collectorRelease.getCount()).isEqualTo(1L);
            } finally { collectorRelease.countDown(); }
            provider.forceFlush().join(15, TimeUnit.SECONDS);
            assertThat(registry.get("slotq.telemetry.export.spans").tag("outcome", "failure").counter().count())
                .isPositive();
        } finally {
            collectorRelease.countDown();
            collector.stop(0);
            registry.close();
        }
    }
}
