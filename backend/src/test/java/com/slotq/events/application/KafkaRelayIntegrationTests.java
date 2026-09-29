package com.slotq.events.application;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.slotq.events.persistence.JdbcKafkaPublicationLedger;
import com.slotq.integration.waitlist.WaitlistKafkaMessage;
import com.slotq.tenancy.domain.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(properties = {
    "slotq.events.delivery.scheduler-enabled=false",
    "spring.datasource.hikari.connection-timeout=3000",
    "spring.datasource.hikari.data-source-properties.socketTimeout=2000"
})
class KafkaRelayIntegrationTests {
    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_kafka_relay");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired EventRegistrationService registrations;
    @Autowired EventAppendService append;
    @Autowired JdbcKafkaPublicationLedger ledger;
    @Autowired EventDeliveryWorker directWorker;
    @Autowired EventDeliveryStore directStore;
    @Autowired WaitlistKafkaMessage mapping;
    @Autowired MeterRegistry meters;

    private static final String TOPIC = "slotq.waitlist.events.v1";
    private static final ConsumerRoute ROUTE = new ConsumerRoute("waitlist.promotion", "waitlist.promotion-requested", 1);

    @Test
    void realMysqlKafkaRollbackCrashDuplicateClaimFencingAndCanonicalWire() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        var policy = new KafkaPublicationPolicy(3, Duration.ofMillis(700), Duration.ofMillis(200),
            100, List.of(Duration.ZERO, Duration.ZERO));
        KafkaTemplate<String, String> template = new KafkaRelayConfiguration().publicationTemplate(
            new KafkaRelayConfiguration.ClientSettings(KAFKA.getBootstrapServers(), "PLAINTEXT", "", "", "", ""));
        var worker = new KafkaRelayWorker(ledger, template, mapping, policy, meters, TOPIC);
        UUID tenant = UUID.randomUUID();
        db.update("INSERT INTO tenants (id,status) VALUES (?, 'ACTIVE')", bytes(tenant));
        UUID waitlistRegistration = registrations.activate(ROUTE);
        TransactionTemplate tx = new TransactionTemplate(manager);
        EventEnvelope rolledBack = event(tenant);
        tx.executeWithoutResult(status -> { append.appendForActiveRoute(rolledBack, ROUTE); status.setRollbackOnly(); });
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_records WHERE event_id=?", Integer.class,
            bytes(rolledBack.eventId().value()))).isZero();

        EventEnvelope original = event(tenant);
        tx.executeWithoutResult(status -> append.appendForActiveRoute(original, ROUTE));
        // Event committed while relay was down: the independent cursor still starts at zero.
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_kafka_publications", Integer.class)).isZero();
        assertThat(ledger.discover(TOPIC, 100)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT boundary_sequence FROM event_discovery WHERE singleton_id=1", Long.class)).isZero();
        var key = ledger.candidates(100).getFirst();
        assertThat(key.eventId()).isEqualTo(original.eventId().value());

        // Two relay owners contend for one durable claim.
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> ledger.claim(key, policy));
            var second = pool.submit(() -> ledger.claim(key, policy));
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertThat((a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0)).isEqualTo(1);
            var stale = a.orElseGet(b::get);
            var published = ledger.load(stale);
            var wire = mapping.encode(published.event(), published.origin());
            assertThat(wire.key()).isEqualTo(tenant + ":" + slotId(original));
            assertThat(wire.body()).contains("\"eventId\":\"" + original.eventId().value() + "\"");

            // An ack is observed, but no MySQL mark is committed. Lease recovery republishes.
            template.send(TOPIC, wire.key(), wire.body()).get(20, TimeUnit.SECONDS);
            Thread.sleep(800);
            worker.runCycle();
            assertThatThrownBy(() -> ledger.published(stale, 0, 0))
                .isInstanceOf(JdbcKafkaPublicationLedger.OwnershipLost.class);
        }
        assertThat(db.queryForObject("SELECT state FROM event_kafka_publications WHERE event_id=?", String.class,
            bytes(original.eventId().value()))).isEqualTo("PUBLISHED");
        assertThat(db.queryForObject("SELECT lifetime_attempts FROM event_kafka_publications WHERE event_id=?", Integer.class,
            bytes(original.eventId().value()))).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_records WHERE event_id=?", Integer.class,
            bytes(original.eventId().value()))).isEqualTo(1);
        assertThatThrownBy(() -> ledger.discover("other.destination", 100))
            .hasMessageContaining("destination changed without cutover");

        Properties config = new Properties();
        List<Map<String, Object>> brokerRecords = new ArrayList<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "relay-evidence-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(config)) {
            reader.subscribe(List.of(TOPIC));
            int count = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (count < 2 && System.nanoTime() < deadline) {
                for (var record : reader.poll(Duration.ofMillis(250))) {
                    brokerRecords.add(Map.of("key", record.key(), "value", record.value(),
                        "partition", record.partition(), "offset", record.offset()));
                    assertThat(record.key()).isEqualTo(tenant + ":" + slotId(original));
                    assertThat(record.value()).contains("\"eventId\":\"" + original.eventId().value() + "\"");
                    count++;
                }
            }
            assertThat(count).isEqualTo(2);
        }
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var probe = new KafkaRetentionProbe(admin, ledger);
            probe.verify(TOPIC);
            int partition = db.queryForObject("SELECT ack_partition FROM event_kafka_publications WHERE event_id=?",
                Integer.class, bytes(original.eventId().value()));
            long ackOffset = db.queryForObject("SELECT ack_offset FROM event_kafka_publications WHERE event_id=?",
                Long.class, bytes(original.eventId().value()));
            admin.deleteRecords(Map.of(new TopicPartition(TOPIC, partition),
                RecordsToDelete.beforeOffset(ackOffset + 1))).all().get(20, TimeUnit.SECONDS);
            assertThatThrownBy(() -> probe.verify(TOPIC)).hasMessageContaining("log-start gap");
            assertThatThrownBy(() -> ledger.verifyTopic(TOPIC, "changed-topic-id", Map.of()))
                .hasMessageContaining("topic identity changed");
        }

        EventEnvelope ackLost = event(tenant);
        tx.executeWithoutResult(status -> append.appendForActiveRoute(ackLost, ROUTE));
        assertThat(ledger.discover(TOPIC, 100)).isEqualTo(1);
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> lostAck = mock(KafkaTemplate.class);
        when(lostAck.send(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            template.send(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))
                .get(20, TimeUnit.SECONDS);
            var lost = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
            lost.completeExceptionally(new java.util.concurrent.TimeoutException("injected ack loss"));
            return lost;
        });
        new KafkaRelayWorker(ledger, lostAck, mapping, policy, meters, TOPIC).runCycle();
        assertThat(db.queryForObject("SELECT state FROM event_kafka_publications WHERE event_id=?", String.class,
            bytes(ackLost.eventId().value()))).isEqualTo("PENDING");
        worker.runCycle();
        assertThat(db.queryForObject("SELECT state FROM event_kafka_publications WHERE event_id=?", String.class,
            bytes(ackLost.eventId().value()))).isEqualTo("PUBLISHED");
        Properties ackReaderConfig = new Properties();
        ackReaderConfig.putAll(config);
        ackReaderConfig.put(ConsumerConfig.GROUP_ID_CONFIG, "ack-loss-evidence-" + UUID.randomUUID());
        try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(ackReaderConfig)) {
            reader.subscribe(List.of(TOPIC));
            int duplicates = 0;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (duplicates < 2 && System.nanoTime() < deadline) {
                for (var record : reader.poll(Duration.ofMillis(250))) {
                    if (record.value().contains("\"eventId\":\"" + ackLost.eventId().value() + "\"")) {
                        brokerRecords.add(Map.of("key", record.key(), "value", record.value(),
                            "partition", record.partition(), "offset", record.offset()));
                        duplicates++;
                    }
                }
            }
            assertThat(duplicates).isEqualTo(2);
        }

        EventEnvelope duringOutage = event(tenant);
        tx.executeWithoutResult(status -> append.appendForActiveRoute(duringOutage, ROUTE));
        assertThat(ledger.discover(TOPIC, 100)).isEqualTo(1);
        var outagePolicy = new KafkaPublicationPolicy(5, Duration.ofSeconds(30), Duration.ofMillis(500),
            100, List.of(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO));
        var outageWorker = new KafkaRelayWorker(ledger, template, mapping, outagePolicy, meters, TOPIC);
        var docker = org.testcontainers.DockerClientFactory.instance().client();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            outageWorker.runCycle();
            assertThat(db.queryForObject("SELECT state FROM event_kafka_publications WHERE event_id=?", String.class,
                bytes(duringOutage.eventId().value()))).isEqualTo("PENDING");
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.describeCluster().nodes().get(20, TimeUnit.SECONDS);
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            outageWorker.runCycle();
            if ("PUBLISHED".equals(db.queryForObject("SELECT state FROM event_kafka_publications WHERE event_id=?",
                String.class, bytes(duringOutage.eventId().value())))) break;
        }
        assertThat(db.queryForObject("SELECT state FROM event_kafka_publications WHERE event_id=?", String.class,
            bytes(duringOutage.eventId().value()))).isEqualTo("PUBLISHED");

        EventEnvelope beforeDbOutage = event(tenant);
        tx.executeWithoutResult(status -> append.appendForActiveRoute(beforeDbOutage, ROUTE));
        docker.pauseContainerCmd(MYSQL.getContainerId()).exec();
        try {
            assertThatThrownBy(() -> ledger.discover(TOPIC, 100)).isInstanceOf(RuntimeException.class);
        } finally {
            docker.unpauseContainerCmd(MYSQL.getContainerId()).exec();
        }
        boolean databaseReady = false;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                databaseReady = db.queryForObject("SELECT 1", Integer.class) == 1;
                if (databaseReady) break;
            } catch (RuntimeException ignored) { }
            Thread.sleep(500);
        }
        assertThat(databaseReady).isTrue();
        outageWorker.runCycle();
        assertThat(db.queryForObject("SELECT state FROM event_kafka_publications WHERE event_id=?", String.class,
            bytes(beforeDbOutage.eventId().value()))).isEqualTo("PUBLISHED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_records WHERE event_id=?", Integer.class,
            bytes(beforeDbOutage.eventId().value()))).isEqualTo(1);
        var productGuard = new KafkaRuntimeGuard(db, new WaitlistKafkaMessage(true, false),
            "product", false, true, false, "none");
        productGuard.run(null);
        assertThat(directWorker.materialize()).isEqualTo(4);
        assertThat(directStore.candidates(new DeliveryExecutionScope(mapping.consumerId(), "DB_DIRECT", 1), 100))
            .hasSize(4);
        db.update("UPDATE event_transport_assignments SET transport='KAFKA',authority_epoch=2");
        assertThat(directStore.candidates(new DeliveryExecutionScope(mapping.consumerId(), "DB_DIRECT", 1), 100))
            .isEmpty();
        assertThatThrownBy(() -> productGuard.run(null)).hasMessageContaining("transport authority");
        assertThatThrownBy(() -> new KafkaRuntimeGuard(db, mapping,
            "relay", true, false, false, "none").run(null))
            .hasMessageContaining("exact durable publication routes");
        db.update("UPDATE event_transport_assignments SET transport='DB_DIRECT',authority_epoch=1");
        assertThat(directStore.candidates(new DeliveryExecutionScope(mapping.consumerId(), "DB_DIRECT", 1), 100))
            .hasSize(4);
        assertThat(meters.find("slotq.kafka.publication.ack").tag("outcome", "success").counter()).isNotNull();
        assertThat(meters.find("slotq.kafka.publication.ack").tag("outcome", "failure").counter()).isNotNull();
        assertThat(meters.find("slotq.kafka.publication.retry").counter()).isNotNull();
        assertThat(meters.find("slotq.kafka.publication.failures").tag("failure_code", "TIMEOUT").counter())
            .isNotNull();
        assertThat(meters.find("slotq.kafka.publication.pending.events").gauge()).isNotNull();
        assertThat(meters.find("slotq.kafka.publication.oldest.recorded.age.seconds").gauge()).isNotNull();
        String evidenceDirectory = System.getProperty("slotq.kafka.evidence.dir");
        if (evidenceDirectory != null) {
            Path output = Path.of(evidenceDirectory).resolve("fault-raw.json");
            Files.createDirectories(output.getParent());
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("schemaVersion", "slotq-kafka-relay-fault/v1");
            raw.put("mysqlVersion", db.queryForObject("SELECT VERSION()", String.class));
            raw.put("mysqlIsolation", db.queryForObject("SELECT @@transaction_isolation", String.class));
            raw.put("brokerImage", "apache/kafka:4.1.1");
            raw.put("javaVersion", System.getProperty("java.version"));
            raw.put("fixtureEventIds", Map.of("rolledBack", rolledBack.eventId().value().toString(),
                "markingCrash", original.eventId().value().toString(), "ackLost", ackLost.eventId().value().toString(),
                "brokerOutage", duringOutage.eventId().value().toString(),
                "dbOutage", beforeDbOutage.eventId().value().toString()));
            raw.put("eventRecords", db.queryForList("""
                SELECT HEX(tenant_id) AS tenant_id, HEX(event_id) AS event_id, event_type, schema_version,
                       boundary_sequence, payload FROM event_records ORDER BY boundary_sequence
                """));
            raw.put("publications", db.queryForList("""
                SELECT HEX(tenant_id) AS tenant_id, HEX(event_id) AS event_id, destination, state,
                       discovered_boundary, cycle_attempts, lifetime_attempts, fencing_token,
                       failure_code, ack_partition, ack_offset FROM event_kafka_publications ORDER BY discovered_boundary
                """));
            raw.put("assignments", db.queryForList("""
                SELECT HEX(registration_id) AS registration_id, transport, authority_epoch
                  FROM event_transport_assignments ORDER BY registration_id
                """));
            raw.put("cursors", Map.of("dbDelivery", db.queryForObject(
                "SELECT boundary_sequence FROM event_discovery WHERE singleton_id=1", Long.class),
                "kafkaPublication", db.queryForObject(
                    "SELECT boundary_sequence FROM event_kafka_discovery WHERE singleton_id=1", Long.class)));
            raw.put("brokerRecordsObservedBeforeAndAfterAckLoss", brokerRecords);
            raw.put("faultsVerified", List.of("business_rollback", "pre_relay_commit", "ack_loss",
                "post_ack_pre_mark_crash", "duplicate_physical_record", "two_relay_claim", "stale_mark",
                "broker_pause_recovery", "db_pause_recovery", "retention_gap", "topic_id_mismatch"));
            Files.writeString(output, new tools.jackson.databind.json.JsonMapper().writeValueAsString(raw));
        }
        assertThat(registrations.deactivate(waitlistRegistration)).isTrue();
        ConsumerRoute unrelated = new ConsumerRoute("operations.event-observation", ROUTE.eventType(), 1);
        registrations.activate(unrelated);
        EventEnvelope unrelatedEvent = event(tenant);
        tx.executeWithoutResult(status -> append.appendForActiveRoute(unrelatedEvent, unrelated));
        assertThat(ledger.discover(TOPIC, 100)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_kafka_publications WHERE event_id=?",
            Integer.class, bytes(unrelatedEvent.eventId().value()))).isZero();
        ((org.springframework.kafka.core.DefaultKafkaProducerFactory<?, ?>)
            template.getProducerFactory()).destroy();
    }

    private EventEnvelope event(UUID tenant) {
        UUID slot = UUID.randomUUID();
        return new EventEnvelope(EventId.newId(), new TenantId(tenant), "SlotInventory", slot,
            ROUTE.eventType(), 1, Instant.now(), "{\"venueId\":\"" + UUID.randomUUID()
                + "\",\"resourceId\":\"" + UUID.randomUUID() + "\",\"slotInventoryId\":\"" + slot + "\"}");
    }
    private UUID slotId(EventEnvelope event) { return event.aggregateId(); }
    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
}
