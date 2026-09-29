package com.slotq.events.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.slotq.events.application.KafkaPublicationPolicy;
import com.slotq.events.application.KafkaRelayConfiguration;
import com.slotq.events.application.KafkaRelayWorker;
import com.slotq.integration.waitlist.WaitlistKafkaMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Test-only coordinator; the production ledger, wire, and relay publish method are unchanged. */
public final class KafkaRelayAckCrashChild {
    private KafkaRelayAckCrashChild() { }

    public static void main(String[] args) throws Exception {
        String topic = System.getenv("SLOTQ_RELAY_TEST_TOPIC");
        String broker = System.getenv("SLOTQ_RELAY_TEST_BROKER");
        var source = new DriverManagerDataSource(System.getenv("SLOTQ_RELAY_TEST_JDBC"),
            System.getenv("SLOTQ_RELAY_TEST_USER"), System.getenv("SLOTQ_RELAY_TEST_PASSWORD"));
        var manager = new DataSourceTransactionManager(source);
        var ledger = new JdbcKafkaPublicationLedger(new JdbcTemplate(source), manager,
            new WaitlistKafkaMessage(true, false));
        var policy = new KafkaPublicationPolicy(3, Duration.ofSeconds(3), Duration.ofSeconds(2),
            100, List.of(Duration.ZERO, Duration.ZERO));
        var claim = ledger.claim(ledger.candidates(10).getFirst(), policy).orElseThrow();
        KafkaTemplate<String, String> physical = new KafkaRelayConfiguration().publicationTemplate(
            new KafkaRelayConfiguration.ClientSettings(broker, "PLAINTEXT", "", "", "", ""));
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> fault = mock(KafkaTemplate.class);
        when(fault.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            var ack = physical.send(call.getArgument(0), call.getArgument(1), call.getArgument(2))
                .get(20, TimeUnit.SECONDS).getRecordMetadata();
            Files.writeString(Path.of(System.getenv("SLOTQ_RELAY_TEST_MARKER")),
                new JsonMapper().writeValueAsString(java.util.Map.of(
                    "stage", "ACK_AFTER_SEND_BEFORE_MARK", "at", Instant.now().toString(),
                    "pid", ProcessHandle.current().pid(), "topic", topic,
                    "partition", ack.partition(), "offset", ack.offset(),
                    "claimToken", claim.token(), "claimAttempt", claim.attempt())));
            Runtime.getRuntime().halt(91);
            throw new AssertionError("unreachable");
        });
        try {
            new KafkaRelayWorker(ledger, fault, new WaitlistKafkaMessage(true, false), policy,
                new SimpleMeterRegistry(), topic).publish(claim);
        } finally {
            ((org.springframework.kafka.core.DefaultKafkaProducerFactory<?, ?>)
                physical.getProducerFactory()).destroy();
        }
        throw new AssertionError("Relay child did not stop at the ACK boundary");
    }
}
