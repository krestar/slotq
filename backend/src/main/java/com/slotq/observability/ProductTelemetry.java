package com.slotq.observability;

import java.util.Set;
import java.util.UUID;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Explicit allowlist instrumentation. Neither request data nor exceptions are accepted by this API. */
@Component
public final class ProductTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger("slotq.telemetry");
    private static final ThreadLocal<Origin> ORIGIN = new ThreadLocal<>();
    private static final Set<String> OUTCOMES = Set.of("success", "client_error", "server_error", "committed",
        "rolled_back", "unknown", "ownership_lost");
    private final Tracer tracer;
    private io.micrometer.core.instrument.MeterRegistry meters;

    public ProductTelemetry(OpenTelemetry telemetry) {
        tracer = telemetry.getTracer("com.slotq.product", "1");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductTelemetry(OpenTelemetry telemetry, io.micrometer.core.instrument.MeterRegistry meters) {
        this(telemetry);
        this.meters = meters;
    }

    public static ProductTelemetry noop() { return new ProductTelemetry(OpenTelemetry.noop()); }

    public static Origin currentOrigin() {
        Origin origin = ORIGIN.get();
        if (origin == null) return Origin.EMPTY;
        try {
            SpanContext current = Span.current().getSpanContext();
            return new Origin(origin.requestId(), current.isValid() ? current.getTraceId() : origin.traceId(),
                current.isValid() ? current.getSpanId() : origin.spanId());
        } catch (RuntimeException ignored) { return origin; }
    }

    public Operation request(String requestId) {
        return start("product.request", SpanKind.SERVER, true, new Origin(requestId, null, null), null, 0, 0, 0);
    }

    public Operation append(UUID eventId) {
        return start("product.event.append", SpanKind.PRODUCER, false, currentOrigin(), eventId, 0, 0, 0);
    }

    /** Each attempt is a new root linked to the original producer, including retries and replays. */
    public Operation delivery(Origin original, UUID eventId, long fencingToken, int cycleAttempt, long lifetimeAttempt) {
        return start("product.event.effect", SpanKind.CONSUMER, true, original, eventId,
            fencingToken, cycleAttempt, lifetimeAttempt);
    }

    public Operation maintenance() {
        return start("product.maintenance", SpanKind.INTERNAL, true, Origin.EMPTY, null, 0, 0, 0);
    }

    public Operation background(String name) {
        String safe = name != null && Set.of("booking_expiry", "waitlist_offer_expiry", "waitlist_promotion_discovery", "event_delivery").contains(name)
            ? name : "maintenance";
        return start("product." + safe, SpanKind.INTERNAL, true, Origin.EMPTY, null, 0, 0, 0);
    }

    public static void promotionOutcome(String outcome) {
        if (outcome == null || !Set.of("PROMOTED", "NO_CAPACITY", "NO_CANDIDATE", "NOT_ELIGIBLE", "SLOT_PAST", "DEFERRED").contains(outcome)) return;
        try { Span.current().setAttribute("slotq.promotion.outcome", outcome); } catch (RuntimeException ignored) { }
    }

    private Operation start(String name, SpanKind kind, boolean root, Origin origin, UUID eventId,
                            long fencingToken, int cycleAttempt, long lifetimeAttempt) {
        Span span = Span.getInvalid();
        Scope scope = null;
        Origin previous = ORIGIN.get();
        Origin safeOrigin = origin == null ? Origin.EMPTY : origin;
        try {
            var builder = tracer.spanBuilder(name).setSpanKind(kind);
            if (root) builder.setNoParent();
            if (kind == SpanKind.CONSUMER && safeOrigin.hasTrace()) {
                builder.addLink(SpanContext.create(safeOrigin.traceId(), safeOrigin.spanId(),
                    TraceFlags.getDefault(), TraceState.getDefault()));
            }
            span = builder.startSpan();
            if (safeOrigin.requestId() != null) span.setAttribute("slotq.request.id", safeOrigin.requestId());
            if (eventId != null) span.setAttribute("slotq.event.id", eventId.toString());
            if (fencingToken > 0) {
                span.setAttribute("slotq.delivery.fencing_token", fencingToken);
                span.setAttribute("slotq.delivery.cycle_attempt", cycleAttempt);
                span.setAttribute("slotq.delivery.lifetime_attempt", lifetimeAttempt);
            }
            scope = (root ? Context.root() : Context.current()).with(span).makeCurrent();
        } catch (RuntimeException ignored) {
            // A broken instrumentation provider must not change Product behavior.
            // Even on this path a new request/attempt must not inherit an unrelated thread context.
            if (root && scope == null) {
                try { scope = Context.root().makeCurrent(); } catch (RuntimeException ignoredScope) { }
            }
        }
        SpanContext context;
        try { context = span.getSpanContext(); } catch (RuntimeException ignored) { context = SpanContext.getInvalid(); }
        ORIGIN.set(new Origin(safeOrigin.requestId(), context.isValid() ? context.getTraceId() : null,
            context.isValid() ? context.getSpanId() : null));
        return new Operation(name, span, scope, previous, safeOrigin.requestId(), eventId, fencingToken, meters);
    }

    /** Nullable migration metadata, deliberately outside EventEnvelope and canonical/deduplication identity. */
    public record Origin(String requestId, String traceId, String spanId) {
        public static final Origin EMPTY = new Origin(null, null, null);
        public Origin {
            if (requestId != null && !requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) requestId = null;
            if (traceId == null || !traceId.matches("[0-9a-f]{32}") || traceId.equals("0".repeat(32))
                || spanId == null || !spanId.matches("[0-9a-f]{16}") || spanId.equals("0".repeat(16))) {
                traceId = null;
                spanId = null;
            }
        }
        public boolean hasTrace() { return traceId != null && spanId != null; }
    }

    public static final class Operation implements AutoCloseable {
        private final String name;
        private final Span span;
        private final Scope scope;
        private final Origin previous;
        private final String requestId;
        private final UUID eventId;
        private final long fencingToken;
        private boolean detached;
        private boolean finished;
        private final long started = System.nanoTime();
        private final io.micrometer.core.instrument.MeterRegistry meters;

        private Operation(String name, Span span, Scope scope, Origin previous, String requestId,
                          UUID eventId, long fencingToken, io.micrometer.core.instrument.MeterRegistry meters) {
            this.name = name; this.span = span; this.scope = scope; this.previous = previous;
            this.requestId = requestId; this.eventId = eventId; this.fencingToken = fencingToken;
            this.meters = meters;
        }

        public void target(UUID registrationId) {
            try { span.setAttribute("slotq.registration.id", registrationId.toString()); } catch (RuntimeException ignored) { }
        }

        public void failureCode(String code) {
            if (code == null || !Set.of("TARGET_HANDLER_MISSING", "UNSUPPORTED_VERSION", "TARGET_ROUTE_CORRUPTION",
                "TENANT_MISMATCH", "IDENTITY_CORRUPTION", "PAYLOAD_INVALID", "DB_LOCK_TRANSIENT", "DB_RESOURCE_TRANSIENT",
                "EFFECT_TIMEOUT", "UNCLASSIFIED_FAILURE", "TRANSIENT_HANDLER", "CRASH_EXHAUSTED").contains(code)) return;
            try { span.setAttribute("slotq.failure.code", code); } catch (RuntimeException ignored) { }
        }

        public String traceId() {
            try { return span.getSpanContext().getTraceId(); } catch (RuntimeException ignored) { return "0".repeat(32); }
        }

        public void requestFinished(String route, String method, int status) {
            try {
                // Caller supplies a framework route template; never accept arbitrary paths here.
                if (route != null && route.length() <= 160 && route.matches("/[A-Za-z0-9_/{}/.-]*"))
                    span.setAttribute("http.route", route);
                span.setAttribute("http.request.method", Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method) ? method : "OTHER");
                span.setAttribute("http.response.status_code", status);
            } catch (RuntimeException ignored) { }
            finish(status >= 500 ? "server_error" : status >= 400 ? "client_error" : "success");
        }

        public void finish(String outcome) {
            if (finished) return;
            finished = true;
            String safe = outcome != null && OUTCOMES.contains(outcome) ? outcome : "unknown";
            try {
                span.setAttribute("slotq.outcome", safe);
                if (Set.of("server_error", "rolled_back", "unknown").contains(safe)) span.setStatus(StatusCode.ERROR);
                span.end();
                LOG.info("operation={} outcome={} request_id={} trace_id={} span_id={} event_id={} fencing_token={}",
                    name, safe, requestId, span.getSpanContext().getTraceId(), span.getSpanContext().getSpanId(), eventId, fencingToken);
            } catch (RuntimeException ignored) { }
            try {
                if (meters != null && name.equals("product.event.effect"))
                    meters.timer("slotq.delivery.effect.duration", "outcome", safe)
                        .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (RuntimeException ignored) { }
        }

        /** Detach from the thread while a transaction synchronization retains the pending span. */
        public void detach() {
            if (detached) return;
            detached = true;
            try { if (scope != null) scope.close(); } catch (RuntimeException ignored) { }
            if (previous == null) ORIGIN.remove(); else ORIGIN.set(previous);
        }

        @Override public void close() { finish("unknown"); detach(); }
    }
}
