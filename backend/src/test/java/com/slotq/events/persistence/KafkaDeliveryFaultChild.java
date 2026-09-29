package com.slotq.events.persistence;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.slotq.SlotqApplication;
import com.slotq.events.application.DeliveryExecutionScope;
import com.slotq.events.application.DeliveryKey;
import com.slotq.events.application.DeliveryPolicy;
import com.slotq.events.application.DeliveryTransactions;
import com.slotq.events.application.EventCanonicalizer;
import com.slotq.events.application.EventDeliveryStore;
import com.slotq.events.application.EventDeliveryWorker;
import com.slotq.events.application.EventHandler;
import com.slotq.events.application.EventHandlers;
import com.slotq.events.application.EventId;
import com.slotq.events.application.StoredEvent;
import com.slotq.integration.waitlist.WaitlistPromotionRequestedHandler;
import com.slotq.tenancy.domain.TenantId;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Test-only crash timing around the unchanged Kafka-scoped DB executor and M4 handler. */
public final class KafkaDeliveryFaultChild {
    private KafkaDeliveryFaultChild() { }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        UUID eventId = UUID.fromString(args[1]);
        try (var context = new SpringApplicationBuilder(SlotqApplication.class)
            .web(WebApplicationType.NONE).run(
                "--spring.main.web-application-type=none", "--slotq.events.runtime-role=product",
                "--slotq.waitlist.promotion.enabled=false", "--slotq.events.delivery.scheduler-enabled=false",
                "--slotq.events.kafka.consumer-enabled=false", "--slotq.events.kafka.business-enabled=false",
                "--slotq.events.delivery.transport=DB_DIRECT", "--slotq.events.delivery.consumer-id=waitlist.promotion",
                "--slotq.events.delivery.authority-epoch=1",
                "--spring.datasource.hikari.connection-timeout=3000")) {
            JdbcTemplate db = context.getBean(JdbcTemplate.class);
            Map<String, Object> identity = db.queryForMap("""
                SELECT e.tenant_id,d.registration_id FROM event_deliveries d
                  JOIN event_records e ON e.event_id=d.event_id
                 WHERE d.event_id=?
                """, bytes(eventId));
            DeliveryKey key = new DeliveryKey(new TenantId(uuid((byte[]) identity.get("tenant_id"))),
                new EventId(eventId), uuid((byte[]) identity.get("registration_id")));
            EventDeliveryStore store = context.getBean(EventDeliveryStore.class);
            DeliveryPolicy policy = new DeliveryPolicy(3, Duration.ofSeconds(4), Duration.ofSeconds(2),
                Duration.ofSeconds(1), 10, List.of(Duration.ZERO, Duration.ZERO));
            EventHandler actual = context.getBean(WaitlistPromotionRequestedHandler.class);
            EventHandler fault = new EventHandler() {
                @Override public com.slotq.events.application.ConsumerRoute route() { return actual.route(); }
                @Override public void handle(StoredEvent event) {
                    actual.handle(event);
                    if (mode.equals("EFFECT_HALT")) {
                        marker(mode, eventId);
                        Runtime.getRuntime().halt(92);
                    }
                    if (mode.equals("COMMIT_UNKNOWN")) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override public void afterCommit() {
                                marker(mode, eventId);
                                Runtime.getRuntime().halt(93);
                            }
                        });
                    }
                }
            };
            EventDeliveryWorker worker = new EventDeliveryWorker(store,
                new DeliveryTransactions(context.getBean(PlatformTransactionManager.class), store, policy),
                policy, new EventHandlers(List.of(fault)), context.getBean(EventCanonicalizer.class),
                context.getBean(EntityManagerFactory.class),
                new DeliveryExecutionScope("waitlist.promotion", "KAFKA", 2));
            if (mode.equals("WRONG_SCOPE")) {
                EventDeliveryWorker wrong = new EventDeliveryWorker(store,
                    new DeliveryTransactions(context.getBean(PlatformTransactionManager.class), store, policy),
                    policy, new EventHandlers(List.of(fault)), context.getBean(EventCanonicalizer.class),
                    context.getBean(EntityManagerFactory.class),
                    new DeliveryExecutionScope("operations.event-observation", "KAFKA", 2));
                if (wrong.claim(key).isPresent()) throw new AssertionError("Wrong consumer claimed Waitlist target");
                marker(mode, eventId);
                return;
            }
            var claim = worker.claim(key);
            if (mode.equals("CLAIM_ONLY")) {
                if (claim.isPresent()) throw new AssertionError("Exhausted claim became executable");
                marker(mode, eventId);
                return;
            }
            if (claim.isEmpty()) throw new AssertionError("Expected due Kafka-scoped target");
            if (mode.equals("CLAIM_HALT")) {
                marker(mode, eventId);
                Runtime.getRuntime().halt(91);
            }
            worker.process(claim.get());
            throw new AssertionError("Fault did not halt the child: " + mode);
        }
    }

    private static void marker(String stage, UUID eventId) {
        try {
            Files.writeString(Path.of(System.getenv("SLOTQ_DELIVERY_TEST_MARKER")),
                new JsonMapper().writeValueAsString(Map.of("stage", stage,
                    "at", Instant.now().toString(), "pid", ProcessHandle.current().pid(),
                    "eventId", eventId.toString())));
        } catch (Exception failure) { throw new IllegalStateException("Cannot write fault marker", failure); }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
