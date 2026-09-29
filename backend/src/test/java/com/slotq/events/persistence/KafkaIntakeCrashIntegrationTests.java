package com.slotq.events.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryExecutionScope;
import com.slotq.events.application.DeliveryPolicy;
import com.slotq.events.application.DeliveryTransactions;
import com.slotq.events.application.EventAppendService;
import com.slotq.events.application.EventCanonicalizer;
import com.slotq.events.application.EventDeliveryStore;
import com.slotq.events.application.EventDeliveryWorker;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventHandlers;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.events.application.EventRecordStore;
import com.slotq.events.application.KafkaConsumerCatalog;
import com.slotq.events.application.KafkaPublicationPolicy;
import com.slotq.events.application.KafkaRelayConfiguration;
import com.slotq.events.application.KafkaRelayWorker;
import com.slotq.integration.waitlist.WaitlistKafkaMessage;
import com.slotq.integration.waitlist.WaitlistPromotionRequestedHandler;
import com.slotq.observability.ProductTelemetry;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.WeeklyOperatingHours;
import com.slotq.booking.application.SlotInventoryUseCase;
import jakarta.persistence.EntityManagerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = {"slotq.events.delivery.scheduler-enabled=false",
    "slotq.waitlist.promotion.enabled=false"})
class KafkaIntakeCrashIntegrationTests {
    private static final String TOPIC = "slotq.waitlist.events.v1";
    private static final ConsumerRoute REQUEST = WaitlistPromotionRequestedHandler.ROUTE;

    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_kafka_intake_crash");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired EventRegistrationService registrations;
    @Autowired EventTransportCutover cutover;
    @Autowired EventAppendService append;
    @Autowired EventRecordStore records;
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired KafkaConsumerCatalog catalog;
    @Autowired JdbcKafkaIntakeStore intakes;
    @Autowired JdbcKafkaPublicationLedger publications;
    @Autowired MeterRegistry meters;
    @Autowired WaitlistKafkaMessage wire;
    @Autowired WaitlistPromotionRequestedHandler requestHandler;
    @Autowired EventDeliveryStore deliveries;
    @Autowired EventCanonicalizer canonicalizer;
    @Autowired DeliveryPolicy policy;
    @Autowired EntityManagerFactory entities;

    @Test
    void threeRealProcessCrashWindowsCommitOnlyIntakeAndDbExecutorFinishesWithoutRedelivery() throws Exception {
        String topicId;
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
            topicId = admin.describeTopics(List.of(TOPIC)).allTopicNames().get(10, TimeUnit.SECONDS)
                .get(TOPIC).topicId().toString();
        }
        db.update("INSERT INTO event_kafka_topic_state (destination,topic_id) VALUES (?,?)", TOPIC, topicId);
        for (var consumer : catalog.consumers())
            for (var route : consumer.routes()) registrations.activate(route);
        assertThat(cutover.complete("KAFKA", 10).phase()).isEqualTo("READY");

        var tenant = tenants.createTenant();
        Instant start = java.time.LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
            .atTime(11, 0).toInstant(ZoneOffset.UTC);
        var venue = venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(), "Intake crash", "UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,
                new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(14, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10)));
        var resource = resources.createResource(new ResourceUseCase.CreateResource(
            tenant.id(), venue.id(), "Table", 4));
        var slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), start.toString()));
        String payload = new JsonMapper().writeValueAsString(Map.of(
            "venueId", venue.id().value().toString(), "resourceId", resource.id().value().toString(),
            "slotInventoryId", slot.id().value().toString()));
        var event = new EventEnvelope(new EventId(UUID.nameUUIDFromBytes(
            "slotq-109-intake-109".getBytes(StandardCharsets.UTF_8))), tenant.id(), "SlotInventory", slot.id().value(),
            REQUEST.eventType(), 1, Instant.now(), payload);
        new TransactionTemplate(manager).execute(status -> append.appendForActiveRoute(event, REQUEST));
        var stored = new TransactionTemplate(manager).execute(status ->
            records.findEventForAppend(event.eventId()).orElseThrow());
        var originRow = db.queryForMap("""
            SELECT origin_request_id,origin_trace_id,origin_span_id FROM event_records WHERE event_id=?
            """, bytes(event.eventId().value()));
        var origin = new ProductTelemetry.Origin((String) originRow.get("origin_request_id"),
            (String) originRow.get("origin_trace_id"), (String) originRow.get("origin_span_id"));
        var message = wire.encode(stored, origin);
        int partition;
        long offset;
        Properties producer = new Properties();
        producer.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        producer.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producer.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> publisher = new KafkaProducer<>(producer)) {
            var sent = publisher.send(new ProducerRecord<>(TOPIC, message.key(), message.body()))
                .get(15, TimeUnit.SECONDS);
            partition = sent.partition(); offset = sent.offset();
        }
        TopicPartition location = new TopicPartition(TOPIC, partition);
        var consumer = catalog.definition("waitlist.promotion");
        List<Map<String, Object>> windows = new ArrayList<>();
        List<Map<String, Object>> faultCases = new ArrayList<>();
        for (String mode : List.of("BEFORE_INTAKE", "AFTER_INTAKE", "AFTER_OFFSET")) {
            Map<String, Object> before = windowSnapshot(event.eventId().value(), consumer.groupId(), location);
            CrashResult crash = crashChild(mode, event.eventId().value());
            int exit = crash.exit();
            assertThat(exit).isEqualTo(mode.equals("BEFORE_INTAKE") ? 81 : mode.equals("AFTER_INTAKE") ? 82 : 83);
            assertThat(crash.entrypoint().get("stage")).isEqualTo(mode);
            assertThat(((Number) crash.entrypoint().get("partition")).intValue()).isEqualTo(partition);
            assertThat(((Number) crash.entrypoint().get("offset")).longValue()).isEqualTo(offset);
            assertThat(((List<?>) crash.entrypoint().get("assignment")).stream().map(Object::toString).toList())
                .contains(location.toString());
            Long brokerNext = committed(consumer.groupId(), location);
            long targets = db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE event_id=?", Long.class,
                bytes(event.eventId().value()));
            long coordinates = db.queryForObject("""
                SELECT COUNT(*) FROM event_kafka_intake_records WHERE consumer_id=? AND topic=?
                  AND partition_id=? AND record_offset=?
                """, Long.class, consumer.consumerId(), TOPIC, partition, offset);
            windows.add(Map.of("window", mode, "exit", exit, "targets", targets,
                "coordinateRows", coordinates, "brokerCommittedNext", brokerNext == null ? -1 : brokerNext));
            Map<String, Object> caseEvidence = new LinkedHashMap<>();
            caseEvidence.put("fault", mode);
            caseEvidence.put("before", before);
            caseEvidence.put("entrypoint", crash.entrypoint());
            caseEvidence.put("exit", exit);
            caseEvidence.put("during", windowSnapshot(event.eventId().value(), consumer.groupId(), location));
            caseEvidence.put("firstRecoveryOpportunity", Map.of("at", Instant.now().toString(),
                "authority", mode.equals("AFTER_OFFSET") ? "DB_EXECUTOR" : "KAFKA_REDELIVERY"));
            faultCases.add(caseEvidence);
            if (mode.equals("BEFORE_INTAKE")) {
                assertThat(targets).isZero(); assertThat(coordinates).isZero(); assertThat(brokerNext).isNull();
            } else if (mode.equals("AFTER_INTAKE")) {
                assertThat(targets).as("durable decision %s", db.queryForList("""
                    SELECT disposition,failure_code FROM event_kafka_intake_records WHERE consumer_id=?
                    """, consumer.consumerId())).isEqualTo(1);
                assertThat(coordinates).isEqualTo(1); assertThat(brokerNext).isNull();
            } else {
                assertThat(targets).isEqualTo(1); assertThat(coordinates).isEqualTo(1);
                assertThat(brokerNext).isEqualTo(offset + 1);
            }
        }
        assertThat(db.queryForObject("SELECT state FROM event_deliveries WHERE event_id=?", String.class,
            bytes(event.eventId().value()))).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT cycle_attempts FROM event_deliveries WHERE event_id=?", Integer.class,
            bytes(event.eventId().value()))).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM waitlist_promotion_receipts WHERE event_id=?", Long.class,
            bytes(event.eventId().value()))).isZero();
        try (KafkaConsumer<byte[], byte[]> reader = reader(consumer.groupId())) {
            reader.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            int redelivered = 0;
            while (System.nanoTime() < deadline)
                for (var record : reader.poll(Duration.ofMillis(250)))
                    if (record.offset() == offset && record.partition() == partition) redelivered++;
            assertThat(redelivered).isZero();
        }
        var scope = new DeliveryExecutionScope(consumer.consumerId(), "KAFKA", 2);
        var executor = new EventDeliveryWorker(deliveries, new DeliveryTransactions(manager, deliveries, policy),
            policy, new EventHandlers(List.of(requestHandler)), canonicalizer, entities, scope);
        assertThat(executor.runCycle()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT state FROM event_deliveries WHERE event_id=?", String.class,
            bytes(event.eventId().value()))).isEqualTo("DONE");
        assertThat(db.queryForObject("SELECT cycle_attempts FROM event_deliveries WHERE event_id=?", Integer.class,
            bytes(event.eventId().value()))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT outcome FROM waitlist_promotion_receipts WHERE event_id=?", String.class,
            bytes(event.eventId().value()))).isEqualTo("NO_CANDIDATE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM waitlist_offers WHERE venue_id=?", Long.class,
            bytes(venue.id().value()))).isZero();
        for (Map<String, Object> caseEvidence : faultCases)
            caseEvidence.put("finalConvergence",
                windowSnapshot(event.eventId().value(), consumer.groupId(), location));

        // The ACK itself is durable at the broker. A different JVM dies inside the production
        // KafkaRelayWorker.publish path before its MySQL PUBLISHED transition can commit.
        assertThat(publications.discover(TOPIC, 10)).isEqualTo(1);
        Map<String, Object> beforeRelayCrash = windowSnapshot(event.eventId().value(), consumer.groupId(), location);
        CrashResult relayCrash = relayAckCrashChild();
        assertThat(relayCrash.exit()).isEqualTo(91);
        assertThat(relayCrash.entrypoint().get("stage")).isEqualTo("ACK_AFTER_SEND_BEFORE_MARK");
        Map<String, Object> afterRelayCrash = windowSnapshot(event.eventId().value(), consumer.groupId(), location);
        assertThat(db.queryForObject("SELECT state FROM event_kafka_publications WHERE event_id=?",
            String.class, bytes(event.eventId().value()))).isEqualTo("PROCESSING");
        Thread.sleep(3200);
        var relayPolicy = new KafkaPublicationPolicy(3, Duration.ofSeconds(3), Duration.ofSeconds(2),
            100, List.of(Duration.ZERO, Duration.ZERO));
        KafkaTemplate<String, String> relayTemplate = new KafkaRelayConfiguration().publicationTemplate(
            new KafkaRelayConfiguration.ClientSettings(KAFKA.getBootstrapServers(),
                "PLAINTEXT", "", "", "", ""));
        try {
            new KafkaRelayWorker(publications, relayTemplate, wire, relayPolicy, meters, TOPIC).runCycle();
        } finally {
            ((org.springframework.kafka.core.DefaultKafkaProducerFactory<?, ?>)
                relayTemplate.getProducerFactory()).destroy();
        }
        assertThat(db.queryForObject("SELECT state FROM event_kafka_publications WHERE event_id=?",
            String.class, bytes(event.eventId().value()))).isEqualTo("PUBLISHED");
        assertThat(db.queryForObject("SELECT lifetime_attempts FROM event_kafka_publications WHERE event_id=?",
            Integer.class, bytes(event.eventId().value()))).isEqualTo(2);
        Map<String, Object> relayConverged = windowSnapshot(event.eventId().value(), consumer.groupId(), location);
        int extraPhysicalRecords = 0;
        try (KafkaConsumer<byte[], byte[]> reader = reader(consumer.groupId())) {
            reader.assign(List.of(location));
            reader.seek(location, offset + 1);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (extraPhysicalRecords < 2 && System.nanoTime() < deadline) {
                for (var record : reader.poll(Duration.ofMillis(250))) {
                    assertThat(intakes.intake(record, consumer, 2))
                        .isEqualTo(JdbcKafkaIntakeStore.Outcome.TARGET);
                    reader.commitSync(Map.of(location, new OffsetAndMetadata(record.offset() + 1)));
                    extraPhysicalRecords++;
                }
            }
        }
        assertThat(extraPhysicalRecords).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE event_id=?", Long.class,
            bytes(event.eventId().value()))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM waitlist_promotion_receipts WHERE event_id=?",
            Long.class, bytes(event.eventId().value()))).isEqualTo(1);
        Map<String, Object> relayDeduplicated = windowSnapshot(event.eventId().value(), consumer.groupId(), location);

        // Three different transport failures are durable, payload-free decisions, not business DEAD.
        var invalidBodies = List.of("{bad",
            message.body().replace(event.eventId().value().toString(), UUID.randomUUID().toString()),
            message.body().replace("\"eventType\":\"waitlist.promotion-requested\"",
                "\"eventType\":\"booking.capacity-released\""));
        long invalidStart = -1;
        try (KafkaProducer<String, String> publisher = new KafkaProducer<>(producer)) {
            for (String invalid : invalidBodies) {
                long sentOffset = publisher.send(new ProducerRecord<>(TOPIC, partition,
                    message.key(), invalid)).get(15, TimeUnit.SECONDS).offset();
                if (invalidStart < 0) invalidStart = sentOffset;
            }
        }
        try (KafkaConsumer<byte[], byte[]> reader = reader(consumer.groupId())) {
            reader.assign(List.of(location));
            reader.seek(location, invalidStart);
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            int processed = 0;
            while (processed < invalidBodies.size() && System.nanoTime() < deadline) {
                for (var record : reader.poll(Duration.ofMillis(250))) {
                    assertThat(intakes.intake(record, consumer, 2))
                        .isEqualTo(JdbcKafkaIntakeStore.Outcome.QUARANTINED);
                    reader.commitSync(Map.of(location, new OffsetAndMetadata(record.offset() + 1)));
                    processed++;
                }
            }
            assertThat(processed).isEqualTo(invalidBodies.size());
        }
        assertThat(db.queryForList("""
            SELECT failure_code FROM event_kafka_intake_records
             WHERE consumer_id=? AND topic=? AND partition_id=? AND disposition='QUARANTINED'
             ORDER BY record_offset
            """, String.class, consumer.consumerId(), TOPIC, partition))
            .containsExactly("MALFORMED_WIRE", "UNKNOWN_ORIGINAL", "CANONICAL_CORRUPTION");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE event_id=?", Long.class,
            bytes(event.eventId().value()))).isEqualTo(1);

        String evidence = System.getProperty("slotq.kafka.evidence.dir");
        if (evidence != null) {
            Path output = Path.of(evidence).resolve("intake-crash-raw.json");
            Files.createDirectories(output.getParent());
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("schemaVersion", "slotq-kafka-intake-crash/v2");
            raw.put("mysqlVersion", db.queryForObject("SELECT VERSION()", String.class));
            raw.put("mysqlIsolation", db.queryForObject("SELECT @@transaction_isolation", String.class));
            raw.put("brokerImage", "apache/kafka:4.1.1");
            raw.put("brokerProfile", "single broker development profile; intake crash windows only");
            raw.put("eventId", event.eventId().value().toString());
            raw.put("partition", partition); raw.put("offset", offset); raw.put("topicId", topicId);
            raw.put("windows", windows);
            raw.put("faultCases", faultCases);
            raw.put("relayAckCrash", Map.of("before", beforeRelayCrash,
                "entrypoint", relayCrash.entrypoint(), "exit", relayCrash.exit(),
                "during", afterRelayCrash, "firstRecoveryOpportunity", relayConverged,
                "finalConvergence", relayDeduplicated));
            raw.put("seed", 109);
            raw.put("revision", System.getProperty("slotq.kafka.evidence.revision", "unrecorded"));
            raw.put("workingTreeStatus", git("status", "--short"));
            raw.put("intakeClassSha256", sha256(JdbcKafkaIntakeStore.class));
            raw.put("childClassSha256", sha256(KafkaIntakeCrashChild.class));
            raw.put("clientVersion", org.apache.kafka.common.utils.AppInfoParser.getVersion());
            raw.put("parentPid", ProcessHandle.current().pid());
            raw.put("consumerGroup", consumer.groupId());
            raw.put("settings", Map.of("autoOffsetReset", "none", "autoCommit", false,
                "childSessionTimeoutMs", 8000, "childHeartbeatMs", 2000,
                "deliveryTransport", "KAFKA", "authorityEpoch", 2));
            raw.put("target", db.queryForMap("""
                SELECT state,cycle_attempts,lifetime_attempts,fencing_token,failure_code
                  FROM event_deliveries WHERE event_id=?
                """, bytes(event.eventId().value())));
            raw.put("intake", db.queryForList("""
                SELECT consumer_id,topic,partition_id,record_offset,disposition,failure_code
                  FROM event_kafka_intake_records WHERE event_id=?
                """, bytes(event.eventId().value())));
            raw.put("quarantine", db.queryForList("""
                SELECT consumer_id,topic,partition_id,record_offset,disposition,failure_code
                  FROM event_kafka_intake_records WHERE consumer_id=? AND disposition='QUARANTINED'
                 ORDER BY partition_id,record_offset
                """, consumer.consumerId()));
            raw.put("receipt", db.queryForMap("""
                SELECT consumer_id,outcome FROM waitlist_promotion_receipts WHERE event_id=?
                """, bytes(event.eventId().value())));
            raw.put("brokerCommittedNext", committed(consumer.groupId(), location));
            Files.writeString(output, new JsonMapper().writeValueAsString(raw));
        }
    }

    private CrashResult crashChild(String mode, UUID eventId) throws Exception {
        Path argumentFile = Files.createTempFile("slotq-intake-child-", ".args");
        Path log = Files.createTempFile("slotq-intake-child-", ".log");
        Path marker = Files.createTempFile("slotq-intake-child-", ".json");
        Files.writeString(argumentFile, "-cp\n\"" + System.getProperty("java.class.path").replace('\\', '/')
            + "\"\n" + KafkaIntakeCrashChild.class.getName() + "\n" + mode + "\n");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "@" + argumentFile).redirectErrorStream(true).redirectOutput(log.toFile());
        process.environment().put("SLOTQ_INTAKE_TEST_TOPIC", TOPIC);
        process.environment().put("SLOTQ_INTAKE_TEST_EVENT", eventId.toString());
        process.environment().put("SLOTQ_INTAKE_TEST_BROKER", KAFKA.getBootstrapServers());
        process.environment().put("SLOTQ_INTAKE_TEST_JDBC", MYSQL.getJdbcUrl());
        process.environment().put("SLOTQ_INTAKE_TEST_USER", MYSQL.getUsername());
        process.environment().put("SLOTQ_INTAKE_TEST_PASSWORD", MYSQL.getPassword());
        process.environment().put("SLOTQ_INTAKE_TEST_MARKER", marker.toString());
        var child = process.start();
        if (!child.waitFor(90, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("Kafka intake crash child timed out");
        }
        int exit = child.exitValue();
        if (exit < 81 || exit > 83)
            throw new AssertionError("Kafka intake child failed: " + Files.readString(log).lines().limit(30).toList());
        Map<String, Object> entrypoint = new JsonMapper().readValue(Files.readString(marker),
            new TypeReference<>() { });
        assertThat(((Number) entrypoint.get("pid")).longValue()).isEqualTo(child.pid());
        Files.deleteIfExists(argumentFile);
        Files.deleteIfExists(log);
        Files.deleteIfExists(marker);
        return new CrashResult(child.pid(), exit, entrypoint);
    }

    private CrashResult relayAckCrashChild() throws Exception {
        Path arguments = Files.createTempFile("slotq-relay-child-", ".args");
        Path log = Files.createTempFile("slotq-relay-child-", ".log");
        Path marker = Files.createTempFile("slotq-relay-child-", ".json");
        Files.writeString(arguments, "-cp\n\"" + System.getProperty("java.class.path").replace('\\', '/')
            + "\"\n" + KafkaRelayAckCrashChild.class.getName() + "\n");
        ProcessBuilder process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
            .toString(), "@" + arguments).redirectErrorStream(true).redirectOutput(log.toFile());
        process.environment().put("SLOTQ_RELAY_TEST_TOPIC", TOPIC);
        process.environment().put("SLOTQ_RELAY_TEST_BROKER", KAFKA.getBootstrapServers());
        process.environment().put("SLOTQ_RELAY_TEST_JDBC", MYSQL.getJdbcUrl());
        process.environment().put("SLOTQ_RELAY_TEST_USER", MYSQL.getUsername());
        process.environment().put("SLOTQ_RELAY_TEST_PASSWORD", MYSQL.getPassword());
        process.environment().put("SLOTQ_RELAY_TEST_MARKER", marker.toString());
        Process child = process.start();
        if (!child.waitFor(60, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("Relay ACK child timed out: " + Files.readString(log));
        }
        if (child.exitValue() != 91)
            throw new AssertionError("Relay ACK child failed: " + Files.readString(log).lines().limit(30).toList());
        Map<String, Object> entrypoint = new JsonMapper().readValue(Files.readString(marker),
            new TypeReference<>() { });
        assertThat(((Number) entrypoint.get("pid")).longValue()).isEqualTo(child.pid());
        Files.deleteIfExists(arguments);
        Files.deleteIfExists(log);
        Files.deleteIfExists(marker);
        return new CrashResult(child.pid(), child.exitValue(), entrypoint);
    }

    private Map<String, Object> windowSnapshot(UUID eventId, String group, TopicPartition partition) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("at", Instant.now().toString());
        state.put("original", db.queryForList("""
            SELECT HEX(tenant_id) tenantId,HEX(event_id) eventId,boundary_sequence boundarySequence
              FROM event_records WHERE event_id=?
            """, bytes(eventId)));
        state.put("publication", db.queryForList("""
            SELECT state,ack_partition ackPartition,ack_offset ackOffset
              FROM event_kafka_publications WHERE event_id=?
            """, bytes(eventId)));
        state.put("intake", db.queryForList("""
            SELECT consumer_id consumerId,partition_id partitionId,record_offset recordOffset,
                   disposition,HEX(record_sha256) recordSha256
              FROM event_kafka_intake_records WHERE event_id=?
            """, bytes(eventId)));
        state.put("targetIntake", db.queryForList("""
            SELECT consumer_id consumerId,partition_id partitionId,first_offset firstOffset,
                   first_materialization firstMaterialization
              FROM event_kafka_target_intakes WHERE event_id=?
            """, bytes(eventId)));
        state.put("targets", db.queryForList("""
            SELECT state,cycle_attempts cycleAttempts,lifetime_attempts lifetimeAttempts,
                   fencing_token fencingToken FROM event_deliveries WHERE event_id=?
            """, bytes(eventId)));
        state.put("receipt", db.queryForList("""
            SELECT consumer_id consumerId,outcome FROM waitlist_promotion_receipts WHERE event_id=?
            """, bytes(eventId)));
        state.put("durablePrefix", db.queryForList("""
            SELECT last_durable_offset FROM event_kafka_consumer_positions
             WHERE consumer_id='waitlist.promotion' AND topic=? AND partition_id=?
            """, Long.class, partition.topic(), partition.partition()));
        Long next = committed(group, partition);
        state.put("brokerCommittedNext", next == null ? -1 : next);
        return state;
    }

    private record CrashResult(long pid, int exit, Map<String, Object> entrypoint) { }

    private Long committed(String group, TopicPartition partition) {
        try (KafkaConsumer<byte[], byte[]> reader = reader(group)) {
            var position = reader.committed(Set.of(partition)).get(partition);
            return position == null ? null : position.offset();
        }
    }

    private KafkaConsumer<byte[], byte[]> reader(String group) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(config);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static String sha256(Class<?> type) throws Exception {
        String resource = type.getSimpleName() + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.readAllBytes()));
        }
    }

    private static String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "safe.directory=C:/dev/slotq"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(Path.of("..").toFile()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();
        return output.strip();
    }
}
