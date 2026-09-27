package com.slotq.observability;

import java.util.regex.Pattern;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/** Only the SDK's numeric queue-loss warning may enter the nonblocking telemetry appender. */
public final class TraceQueueWarningFilter extends Filter<ILoggingEvent> {
    private static final String PROCESSOR = "io.opentelemetry.sdk.trace.export.BatchSpanProcessor";
    private static final Pattern QUEUE_LOSS = Pattern.compile(
        "BatchSpanProcessor dropped [1-9][0-9]{0,9} span\\(s\\) since the last export because the queue is full"
            + " \\(maxQueueSize=[1-9][0-9]{0,9}\\)");

    @Override public FilterReply decide(ILoggingEvent event) {
        if (!PROCESSOR.equals(event.getLoggerName())) return FilterReply.NEUTRAL;
        return event.getLevel() == Level.WARN && event.getThrowableProxy() == null
            && event.getMessage() != null && QUEUE_LOSS.matcher(event.getMessage()).matches()
            ? FilterReply.NEUTRAL : FilterReply.DENY;
    }
}
