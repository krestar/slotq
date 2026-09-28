package com.slotq.events.application;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.slotq.events.persistence.JdbcKafkaPublicationLedger;
import com.slotq.integration.waitlist.WaitlistKafkaMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Each broker send runs after a short durable claim transaction has committed. */
@Component
@ConditionalOnProperty(name = "slotq.events.kafka.relay-enabled", havingValue = "true")
public final class KafkaRelayWorker {
    private final JdbcKafkaPublicationLedger ledger;
    private final KafkaTemplate<String, String> kafka;
    private final WaitlistKafkaMessage mapping;
    private final KafkaPublicationPolicy policy;
    private final MeterRegistry meters;
    private final KafkaRetentionProbe retention;
    private final KafkaRuntimeGuard runtime;
    private final com.slotq.observability.ProductTelemetry telemetry;
    private final String destination;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicReference<Double> oldestAge = new AtomicReference<>(Double.NaN);
    private final AtomicReference<String> relayState = new AtomicReference<>();
    private final AtomicReference<String> producerState = new AtomicReference<>();
    private boolean gaugesRegistered;

    @org.springframework.beans.factory.annotation.Autowired
    public KafkaRelayWorker(JdbcKafkaPublicationLedger ledger, KafkaTemplate<String, String> kafka,
        WaitlistKafkaMessage mapping, KafkaPublicationPolicy policy, MeterRegistry meters,
        KafkaRetentionProbe retention, KafkaRuntimeGuard runtime,
        com.slotq.observability.ProductTelemetry telemetry,
        @Value("${slotq.events.kafka.destination:slotq.waitlist.events.v1}") String destination) {
        EventCanonicalizer.requireIdentifier(destination, "Kafka destination");
        this.ledger = ledger; this.kafka = kafka; this.mapping = mapping; this.policy = policy;
        this.meters = meters; this.retention = retention; this.runtime = runtime; this.telemetry = telemetry;
        this.destination = destination;
    }

    public KafkaRelayWorker(JdbcKafkaPublicationLedger ledger, KafkaTemplate<String, String> kafka,
        WaitlistKafkaMessage mapping, KafkaPublicationPolicy policy, MeterRegistry meters, String destination) {
        this(ledger, kafka, mapping, policy, meters, null, null,
            com.slotq.observability.ProductTelemetry.noop(), destination);
    }

    @Scheduled(fixedDelayString = "${slotq.events.kafka.poll-interval:PT1S}")
    public void scheduled() {
        if (runtime != null && !runtime.relayReady()) return;
        try { runCycle(); }
        catch (RuntimeException failure) {
            state(relayState, "relay", "degraded");
            org.slf4j.LoggerFactory.getLogger("slotq.telemetry").warn("operation=kafka_relay outcome=degraded");
        }
    }

    public int runCycle() {
        if (runtime != null && !runtime.relayReady())
            throw new IllegalStateException("Kafka relay runtime is not ready");
        if (retention != null) retention.verify(destination);
        try {
            ledger.discover(destination, policy.batchSize());
            int claimed = 0;
            for (var key : ledger.candidates(policy.batchSize())) {
                var claim = ledger.claim(key, policy);
                if (claim.isEmpty()) continue;
                claimed++;
                publish(claim.get());
            }
            var inventory = ledger.inventory();
            registerGauges();
            pending.set(inventory.pending());
            oldestAge.set(inventory.oldestSeconds() == null ? 0.0 : inventory.oldestSeconds());
            state(relayState, "relay", "degraded".equals(producerState.get()) ? "degraded" : "ready");
            return claimed;
        } catch (RuntimeException failure) {
            state(relayState, "relay", "degraded");
            throw failure;
        }
    }

    public void publish(JdbcKafkaPublicationLedger.Claim claim) {
        if (claim.attempt() > 1) meters.counter("slotq.kafka.publication.retry", "transport", "kafka",
            "runtime_role", "relay").increment();
        try {
            var publication = ledger.load(claim);
            var message = mapping.encode(publication.event(), publication.origin());
            org.apache.kafka.clients.producer.RecordMetadata ack;
            try (var observation = telemetry.publication(publication.origin(), claim.key().eventId(),
                claim.token(), claim.attempt())) {
                ack = kafka.send(claim.key().destination(), message.key(), message.body())
                    .get(policy.ackTimeout().toMillis(), TimeUnit.MILLISECONDS).getRecordMetadata();
                observation.finish("success");
            }
            meters.counter("slotq.kafka.publication.ack", "transport", "kafka", "runtime_role", "relay",
                "outcome", "success").increment();
            state(producerState, "producer", "ready");
            ledger.published(claim, ack.partition(), ack.offset());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            recordFailure(claim, "OTHER", true);
        } catch (ExecutionException | TimeoutException failure) {
            Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
            String code = failureCode(cause);
            recordFailure(claim, code, !code.equals("AUTHORIZATION") && !code.equals("SERIALIZATION"));
        } catch (org.apache.kafka.common.KafkaException failure) {
            String code = failureCode(failure);
            recordFailure(claim, code, !code.equals("AUTHORIZATION") && !code.equals("SERIALIZATION"));
        } catch (IllegalArgumentException failure) {
            recordFailure(claim, "SERIALIZATION", false);
        }
    }

    private void recordFailure(JdbcKafkaPublicationLedger.Claim claim, String code, boolean retryable) {
        meters.counter("slotq.kafka.publication.ack", "transport", "kafka", "runtime_role", "relay",
            "outcome", "failure").increment();
        meters.counter("slotq.kafka.publication.failures", "transport", "kafka", "runtime_role", "relay",
            "failure_code", code).increment();
        state(producerState, "producer", "degraded");
        ledger.failed(claim, code, retryable, policy);
    }

    private String failureCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof AuthorizationException) return "AUTHORIZATION";
            if (cause instanceof SerializationException) return "SERIALIZATION";
            if (cause instanceof TimeoutException || cause instanceof org.apache.kafka.common.errors.TimeoutException)
                return "TIMEOUT";
            if (cause instanceof RetriableException) return "CONNECTION";
        }
        return "OTHER";
    }

    private synchronized void registerGauges() {
        if (gaugesRegistered) return;
        Gauge.builder("slotq.kafka.publication.pending.events", pending, AtomicLong::get)
            .tag("transport", "kafka").tag("runtime_role", "relay").register(meters);
        Gauge.builder("slotq.kafka.publication.oldest.recorded.age.seconds", oldestAge,
            value -> value.get())
            .tag("transport", "kafka").tag("runtime_role", "relay").register(meters);
        gaugesRegistered = true;
    }

    private synchronized void state(AtomicReference<String> holder, String role, String next) {
        if (holder.get() == null) {
            for (String label : java.util.List.of("ready", "degraded", "stopped")) {
                Gauge.builder("slotq.kafka.runtime.state", holder,
                    value -> label.equals(value.get()) ? 1.0 : 0.0)
                    .tag("transport", "kafka").tag("runtime_role", role).tag("state", label).register(meters);
            }
        }
        holder.set(next);
    }
}
