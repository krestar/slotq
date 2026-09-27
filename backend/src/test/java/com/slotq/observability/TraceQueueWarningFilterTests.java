package com.slotq.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceQueueWarningFilterTests {
    private static final String WARNING = "BatchSpanProcessor dropped 2048 span(s) since the last export "
        + "because the queue is full (maxQueueSize=2048)";
    private final TraceQueueWarningFilter filter = new TraceQueueWarningFilter();

    @Test void onlyNumericQueueLossWarningIsForwardedAndProductTelemetryIsUnchanged() {
        assertThat(filter.decide(event(WARNING))).isEqualTo(FilterReply.NEUTRAL);
        var product = event("Product request completed", Level.INFO);
        product.setLoggerName("slotq.telemetry");
        assertThat(filter.decide(product)).isEqualTo(FilterReply.NEUTRAL);
    }

    @Test void exporterThrowableAndRawFieldsCannotEnterTelemetryThroughSdkLogger() {
        for (String message : new String[]{"Exporter threw an Exception", WARNING + " Bearer private-secret",
                "private-email@example.invalid SQL-bind-secret", "https://private-endpoint/v1/traces"}) {
            assertThat(filter.decide(event(message))).isEqualTo(FilterReply.DENY);
        }
        var throwable = event(WARNING);
        throwable.setThrowableProxy(new ThrowableProxy(new IllegalStateException("private-authorization")));
        assertThat(filter.decide(throwable)).isEqualTo(FilterReply.DENY);
        var wrongLevel = event(WARNING, Level.INFO);
        assertThat(filter.decide(wrongLevel)).isEqualTo(FilterReply.DENY);
    }

    private static LoggingEvent event(String message) {
        return event(message, Level.WARN);
    }

    private static LoggingEvent event(String message, Level level) {
        var event = new LoggingEvent();
        event.setLoggerName("io.opentelemetry.sdk.trace.export.BatchSpanProcessor");
        event.setLevel(level);
        event.setMessage(message);
        return event;
    }
}
