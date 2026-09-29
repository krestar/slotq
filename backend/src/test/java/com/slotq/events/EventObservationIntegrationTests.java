package com.slotq.events;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryKey;
import com.slotq.events.application.DeliveryExecutionScope;
import com.slotq.events.application.DeliveryPolicy;
import com.slotq.events.application.DeliverySnapshot;
import com.slotq.events.application.DeliveryTransactions;
import com.slotq.events.application.EventAppendService;
import com.slotq.events.application.EventCanonicalizer;
import com.slotq.events.application.EventDeliveryStore;
import com.slotq.events.application.EventDeliveryWorker;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventHandler;
import com.slotq.events.application.EventHandlers;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRecordStore;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.events.application.EventReplayService;
import com.slotq.events.application.StoredEvent;
import com.slotq.observability.ProductTelemetry;
import com.slotq.tenancy.domain.TenantId;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {"slotq.events.delivery.scheduler-enabled=false", "slotq.observability.database.enabled=false"})
class EventObservationIntegrationTests {
    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_event_observation");
    private static final ConsumerRoute ROUTE = new ConsumerRoute("ObservationFixture", "ObservationSignal", 1);
    private static DeliveryExecutionScope scope() {
        return new DeliveryExecutionScope(ROUTE.consumerId(), "DB_DIRECT", 1);
    }
    private static final DeliveryPolicy POLICY = new DeliveryPolicy(2, Duration.ofSeconds(6),
        Duration.ofSeconds(3), Duration.ofSeconds(1), 100, List.of(Duration.ZERO));
    @Autowired JdbcTemplate jdbc;
    @Autowired EventRecordStore records;
    @Autowired EventDeliveryStore deliveries;
    @Autowired EventCanonicalizer canonicalizer;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired EventRegistrationService registrations;
    private TransactionTemplate transaction;

    @BeforeEach
    void prepare() {
        transaction = new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE IF NOT EXISTS observation_effect (event_id BINARY(16) PRIMARY KEY)");
        jdbc.update("DELETE FROM observation_effect");
        jdbc.update("DELETE FROM event_replay_audit");
        jdbc.update("DELETE FROM event_deliveries");
        jdbc.update("DELETE FROM event_records");
        jdbc.update("DELETE FROM event_registrations");
        jdbc.update("UPDATE event_boundary SET sequence_value = 0");
        jdbc.update("UPDATE event_discovery SET boundary_sequence = 0");
    }

    @Test
    void metadataIsAtomicWithAppendAndDuplicateCannotOverwriteItsOriginalCorrelation() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = provider(exporter)) {
            var telemetry = telemetry(provider);
            var append = new EventAppendService(records, canonicalizer, manager, telemetry);
            var event = event("{\"contact\":\"private@example.test\",\"credential\":\"secret-payload\"}");
            String requestId = UUID.randomUUID().toString();
            StoredEvent stored;
            try (var request = telemetry.request(requestId)) {
                stored = transaction.execute(status -> append.append(event));
                request.finish("success");
            }
            var origin = origin(event.eventId());
            assertThat(origin.requestId()).isEqualTo(requestId);
            SpanData appendSpan = spans(exporter, "product.event.append").getFirst();
            SpanData requestSpan = spans(exporter, "product.request").getFirst();
            assertThat(origin.traceId()).isEqualTo(requestSpan.getTraceId());
            assertThat(origin.spanId()).isEqualTo(appendSpan.getSpanId());
            assertThat(appendSpan.getParentSpanId()).isEqualTo(requestSpan.getSpanId());
            assertThat(outcome(appendSpan)).isEqualTo("committed");

            try (var duplicate = telemetry.request(UUID.randomUUID().toString())) {
                StoredEvent repeated = transaction.execute(status -> append.append(event));
                assertThat(repeated).isEqualTo(stored);
                duplicate.finish("success");
            }
            assertThat(origin(event.eventId())).isEqualTo(origin);

            var rolledBack = event("{\"credential\":\"rollback-secret\"}");
            long boundary = jdbc.queryForObject("SELECT sequence_value FROM event_boundary", Long.class);
            try (var request = telemetry.request(UUID.randomUUID().toString())) {
                transaction.executeWithoutResult(status -> {
                    jdbc.update("INSERT INTO observation_effect VALUES (?)", bytes(rolledBack.eventId().value()));
                    append.append(rolledBack);
                    status.setRollbackOnly();
                });
                request.finish("server_error");
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_records WHERE event_id = ?", Integer.class,
                bytes(rolledBack.eventId().value()))).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observation_effect", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT sequence_value FROM event_boundary", Long.class)).isEqualTo(boundary);
            assertThat(outcome(spans(exporter, "product.event.append").getLast())).isEqualTo("rolled_back");
            assertThat(ProductTelemetry.currentOrigin()).isEqualTo(ProductTelemetry.Origin.EMPTY);
            assertNoSecrets(exporter, "private@example.test", "secret-payload", "rollback-secret");
        }
    }

    @Test
    void restartedAttemptsAndReplayKeepOriginalLinkAndSeparateFencingIdentities() {
        UUID registration = registrations.activate(ROUTE);
        var produced = InMemorySpanExporter.create();
        StoredEvent stored;
        String requestId = UUID.randomUUID().toString();
        try (var producerProvider = provider(produced)) {
            var producer = telemetry(producerProvider);
            var append = new EventAppendService(records, canonicalizer, manager, producer);
            try (var request = producer.request(requestId)) {
                var event = event("{\"credential\":\"private-event-payload\"}");
                stored = transaction.execute(status -> append.append(event));
                request.finish("success");
            }
        }
        var original = origin(stored.envelope().eventId());
        var key = new DeliveryKey(stored.envelope().tenantId(), stored.envelope().eventId(), registration);
        var consumed = InMemorySpanExporter.create();
        try (var restartedProvider = provider(consumed)) {
            var restarted = telemetry(restartedProvider);
            var attempts = new AtomicInteger();
            EventHandler handler = new EventHandler() {
                @Override public ConsumerRoute route() { return ROUTE; }
                @Override public void handle(StoredEvent event) {
                    assertThat(ProductTelemetry.currentOrigin().requestId()).isEqualTo(requestId);
                    jdbc.update("INSERT INTO observation_effect VALUES (?)", bytes(event.envelope().eventId().value()));
                    if (attempts.incrementAndGet() <= 2)
                        throw new TransientDataAccessResourceException("private-sql-bind-secret@example.test");
                }
            };
            EventDeliveryWorker worker = worker(restarted, handler);
            worker.runCycle();
            assertThat(snapshot(key).state()).isEqualTo(DeliverySnapshot.State.PENDING);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observation_effect", Integer.class)).isZero();
            worker.runCycle();
            assertThat(snapshot(key).state()).isEqualTo(DeliverySnapshot.State.DEAD);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observation_effect", Integer.class)).isZero();
            new EventReplayService(deliveries, transactions(), scope())
                .replay(SystemPrincipal.INSTANCE, key, "private-replay-reason");
            worker.runCycle();
            assertThat(snapshot(key).state()).isEqualTo(DeliverySnapshot.State.DONE);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observation_effect", Integer.class)).isEqualTo(1);
            assertThat(origin(stored.envelope().eventId())).isEqualTo(original);
            List<SpanData> effects = spans(consumed, "product.event.effect");
            assertThat(effects).hasSize(3);
            assertThat(effects).extracting(SpanData::getTraceId).doesNotHaveDuplicates().doesNotContain(original.traceId());
            assertThat(effects).extracting(EventObservationIntegrationTests::outcome)
                .containsExactly("rolled_back", "rolled_back", "committed");
            assertThat(effects).extracting(span -> number(span, "slotq.delivery.fencing_token")).containsExactly(1L, 2L, 4L);
            assertThat(effects).extracting(span -> number(span, "slotq.delivery.cycle_attempt")).containsExactly(1L, 2L, 1L);
            assertThat(effects).extracting(span -> number(span, "slotq.delivery.lifetime_attempt")).containsExactly(1L, 2L, 3L);
            for (SpanData effect : effects) {
                assertThat(effect.getParentSpanContext().isValid()).isFalse();
                assertThat(effect.getLinks()).singleElement().satisfies(link -> {
                    assertThat(link.getSpanContext().getTraceId()).isEqualTo(original.traceId());
                    assertThat(link.getSpanContext().getSpanId()).isEqualTo(original.spanId());
                });
                assertThat(effect.getAttributes().get(AttributeKey.stringKey("slotq.request.id"))).isEqualTo(requestId);
            }
            assertThat(ProductTelemetry.currentOrigin()).isEqualTo(ProductTelemetry.Origin.EMPTY);
            assertNoSecrets(consumed, "private-sql-bind-secret@example.test", "private-event-payload", "private-replay-reason");
        }
    }

    @Test
    void legacyNullMetadataStillCommitsEffectAndDoneWithoutInventingAnOriginalRequest() {
        UUID registration = registrations.activate(ROUTE);
        var event = event("{}");
        StoredEvent stored = transaction.execute(status -> new EventAppendService(records, canonicalizer, manager).append(event));
        assertThat(origin(event.eventId())).isEqualTo(ProductTelemetry.Origin.EMPTY);
        var exporter = InMemorySpanExporter.create();
        try (var provider = provider(exporter)) {
            worker(telemetry(provider), new EventHandler() {
                @Override public ConsumerRoute route() { return ROUTE; }
                @Override public void handle(StoredEvent target) {
                    assertThat(target).isEqualTo(stored);
                    jdbc.update("INSERT INTO observation_effect VALUES (?)", bytes(event.eventId().value()));
                }
            }).runCycle();
            assertThat(snapshot(new DeliveryKey(event.tenantId(), event.eventId(), registration)).state())
                .isEqualTo(DeliverySnapshot.State.DONE);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM observation_effect", Integer.class)).isEqualTo(1);
            assertThat(spans(exporter, "product.event.effect")).singleElement().satisfies(span -> {
                assertThat(span.getLinks()).isEmpty();
                assertThat(span.getAttributes().get(AttributeKey.stringKey("slotq.request.id"))).isNull();
                assertThat(outcome(span)).isEqualTo("committed");
            });
        }
    }

    private EventEnvelope event(String payload) {
        TenantId tenant = TenantId.newId();
        jdbc.update("INSERT INTO tenants (id, status) VALUES (?, 'ACTIVE')", bytes(tenant.value()));
        return new EventEnvelope(EventId.newId(), tenant, "ObservationOwner", UUID.randomUUID(),
            ROUTE.eventType(), 1, Instant.parse("2026-09-26T00:00:00Z"), payload);
    }
    private ProductTelemetry.Origin origin(EventId eventId) {
        return jdbc.queryForObject("SELECT origin_request_id, origin_trace_id, origin_span_id FROM event_records WHERE event_id = ?",
            (row, n) -> new ProductTelemetry.Origin(row.getString(1), row.getString(2), row.getString(3)), bytes(eventId.value()));
    }
    private DeliveryTransactions transactions() { return new DeliveryTransactions(manager, deliveries, POLICY); }
    private DeliverySnapshot snapshot(DeliveryKey key) {
        return transactions().execute(() -> deliveries.lock(scope(), key).orElseThrow());
    }
    private EventDeliveryWorker worker(ProductTelemetry telemetry, EventHandler handler) {
        return new EventDeliveryWorker(deliveries, transactions(), POLICY, new EventHandlers(List.of(handler)),
            canonicalizer, entityManagerFactory, telemetry, scope());
    }
    private static SdkTracerProvider provider(InMemorySpanExporter exporter) {
        return SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    }
    private static ProductTelemetry telemetry(SdkTracerProvider provider) {
        return new ProductTelemetry(OpenTelemetrySdk.builder().setTracerProvider(provider).build());
    }
    private static List<SpanData> spans(InMemorySpanExporter exporter, String name) {
        return exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals(name)).toList();
    }
    private static String outcome(SpanData span) { return span.getAttributes().get(AttributeKey.stringKey("slotq.outcome")); }
    private static Long number(SpanData span, String key) { return span.getAttributes().get(AttributeKey.longKey(key)); }
    private static void assertNoSecrets(InMemorySpanExporter exporter, String... secrets) {
        assertThat(exporter.getFinishedSpanItems().toString()).doesNotContain(secrets);
        assertThat(exporter.getFinishedSpanItems()).allSatisfy(span -> assertThat(span.getEvents()).isEmpty());
    }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
