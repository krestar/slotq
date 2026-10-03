package com.slotq.observability;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.slotq.events.application.*;
import com.slotq.events.persistence.EventTransportCutover;
import com.slotq.events.persistence.JdbcKafkaIntakeStore;
import com.slotq.events.persistence.KafkaIntakeRuntime;
import com.slotq.integration.waitlist.WaitlistKafkaMessage;
import com.slotq.tenancy.domain.TenantId;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers @SpringBootTest
class KafkaQuarantineObservationIntegrationTests {
    private static final String CONSUMER = "waitlist.promotion";
    private static final String TOPIC = "slotq.waitlist.events.v1";
    @Container @ServiceConnection static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4")
            .withCommand("--log-bin-trust-function-creators=1").withDatabaseName("slotq_quarantine_observation");
    @Autowired JdbcTemplate db;
    @Autowired JdbcKafkaIntakeStore store;
    @Autowired KafkaConsumerCatalog catalog;
    @Autowired EventRegistrationService registrations;
    @Autowired EventAppendService append;
    @Autowired PlatformTransactionManager manager;
    @Autowired EventTransportCutover cutover;
    @Autowired EventDeliveryWorker directWorker;
    @Autowired WaitlistKafkaMessage mapping;
    private SimpleMeterRegistry meters;
    private DatabaseObservationSampler sampler;

    @BeforeEach void prepare() throws Exception {
        db.update("DELETE FROM event_kafka_intake_records WHERE disposition='QUARANTINED'");
        admin("DROP USER IF EXISTS 'quarantine_observer'@'%'");
        admin("CREATE USER 'quarantine_observer'@'%' IDENTIFIED BY 'test-observation'");
        for (String table : List.of("event_deliveries", "event_registrations", "event_kafka_intake_records"))
            admin("GRANT SELECT ON slotq_quarantine_observation." + table + " TO 'quarantine_observer'@'%'");
        meters = new SimpleMeterRegistry();
        sampler = new DatabaseObservationSampler(meters, MYSQL.getJdbcUrl(), "quarantine_observer", "test-observation",
            Duration.ofSeconds(1), Duration.ofSeconds(2), "consumer", CONSUMER, "KAFKA");
        // Control the existing production sample method deterministically after its startup task finishes.
        var scheduler = (ScheduledExecutorService) ReflectionTestUtils.getField(sampler, "scheduler");
        scheduler.shutdown();
        assertThat(scheduler.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach void close() { sampler.close(); meters.close(); }

    @Test void successFailureRecoveryReplacesOnlyCompleteSnapshotAndHealthIsIndependent() throws Exception {
        quarantine(CONSUMER, 0, "MALFORMED_WIRE");
        quarantine(CONSUMER, 1, "MALFORMED_WIRE");
        quarantine("operations.event-observation", 0, "UNKNOWN_ORIGINAL");
        sampler.sample();
        assertThat(gauge("sample.healthy")).isEqualTo(1);
        assertThat(count("MALFORMED_WIRE")).isEqualTo(2);
        assertThat(count("UNKNOWN_ORIGINAL")).isZero();
        assertThat(gauge("oldest.age.seconds")).isGreaterThanOrEqualTo(60);
        var snapshot = retainedSnapshot();
        var dataSource = source();
        assertThat(dataSource.isReadOnly()).isTrue();
        assertThatThrownBy(() -> {
            try (var c = dataSource.getConnection(); var s = c.createStatement()) {
                s.executeUpdate("DELETE FROM event_kafka_intake_records");
            }
        }).isInstanceOf(java.sql.SQLException.class);
        admin("REVOKE SELECT ON slotq_quarantine_observation.event_kafka_intake_records FROM 'quarantine_observer'@'%'");
        sampler.sample();
        assertUnavailable();
        assertThat(retainedSnapshot()).isSameAs(snapshot);
        assertThat(gauge("sample.age.seconds")).isGreaterThanOrEqualTo(0);
        assertThat(meters.get("slotq.kafka.delivery.sample.healthy").gauge().value()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_kafka_intake_records WHERE disposition='QUARANTINED'", Integer.class)).isEqualTo(3);
        admin("GRANT SELECT ON slotq_quarantine_observation.event_kafka_intake_records TO 'quarantine_observer'@'%'");
        db.update("DELETE FROM event_kafka_intake_records WHERE disposition='QUARANTINED'");
        sampler.sample();
        assertThat(gauge("sample.healthy")).isEqualTo(1);
        assertThat(count("MALFORMED_WIRE")).isZero();
        assertThat(gauge("oldest.age.seconds")).isZero();
    }

    @Test void firstFailedSampleIsUnknownAndCannotCreateSensitiveOrUnboundedLabels() throws Exception {
        admin("REVOKE SELECT ON slotq_quarantine_observation.event_kafka_intake_records FROM 'quarantine_observer'@'%'");
        // Explicitly discard the startup sample to test first-observation failure.
        Object initial = ReflectionTestUtils.getField(sampler, "quarantine");
        var constructor = initial.getClass().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        ReflectionTestUtils.setField(sampler, "quarantine", constructor.newInstance(null, false));
        sampler.sample();
        assertUnavailable();
        assertThat(gauge("sample.age.seconds")).isNaN();
        var ids = meters.getMeters().stream().filter(m -> m.getId().getName().contains("quarantine")).map(m -> m.getId()).toList();
        assertThat(ids).hasSize(8);
        assertThat(ids.toString()).doesNotContain("test-observation", "quarantine_observer", "SELECT", "tenant", "payload");
        assertThat(ids.stream().flatMap(id -> id.getTags().stream()).map(tag -> tag.getKey()).distinct())
            .containsExactlyInAnyOrder("transport", "runtime_role", "logical_consumer", "failure_code");
        var filter = new ObservationMeterFilter();
        assertThat(ids).allMatch(id -> filter.accept(id) != io.micrometer.core.instrument.config.MeterFilterReply.DENY);
    }

    @Test void staleSnapshotIsUnknownAndScrapePerformsNoDatabaseWork() throws Exception {
        quarantine(CONSUMER, 0, "MALFORMED_WIRE");
        sampler.sample();
        assertThat(count("MALFORMED_WIRE")).isEqualTo(1);
        var snapshot = retainedSnapshot();
        var stale = new DatabaseObservation.Snapshot(snapshot.values(), Instant.now().minusSeconds(3));
        var state = ReflectionTestUtils.getField(sampler, "quarantine");
        var constructor = state.getClass().getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        ReflectionTestUtils.setField(sampler, "quarantine", constructor.newInstance(stale, true));
        source().close(); // Every subsequent gauge read must use memory only.
        assertUnavailable();
        assertThat(gauge("sample.age.seconds")).isGreaterThanOrEqualTo(3);
        assertThat(retainedSnapshot().values()).isEqualTo(snapshot.values());
    }

    @Test void unapprovedFailureCodeCannotPublishPartialSnapshotOrGrowMetricCardinality() {
        quarantine(CONSUMER, 0, "MALFORMED_WIRE");
        sampler.sample();
        var snapshot = retainedSnapshot();
        var ids = meters.getMeters().stream().map(m -> m.getId()).toList();
        quarantine(CONSUMER, 1, "unapproved-sensitive-code");
        sampler.sample();
        assertUnavailable();
        assertThat(retainedSnapshot()).isSameAs(snapshot);
        assertThat(meters.getMeters().stream().map(m -> m.getId()).toList()).isEqualTo(ids);
        assertThat(ids.toString()).doesNotContain("unapproved-sensitive-code");
    }

    @Test void nonlockingReadSeesCommittedQuarantineAndPreservesWriterRollback() throws Exception {
        quarantine(CONSUMER, 0, "MALFORMED_WIRE");
        try (var writer = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            writer.setAutoCommit(false);
            try (var sql = writer.createStatement()) {
                sql.executeUpdate("DELETE FROM event_kafka_intake_records WHERE disposition='QUARANTINED'");
                long start = System.nanoTime();
                sampler.sample();
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
                assertThat(count("MALFORMED_WIRE")).isEqualTo(1);
                writer.rollback();
            }
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_kafka_intake_records WHERE disposition='QUARANTINED'", Integer.class)).isEqualTo(1);
    }

    @Test void blockedProductionQuarantineQueryTimesOutWithoutReportingZero() throws Exception {
        quarantine(CONSUMER, 0, "MALFORMED_WIRE");
        sampler.sample();
        var snapshot = retainedSnapshot();
        try (var locker = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); var sql = locker.createStatement()) {
            sql.execute("LOCK TABLES event_kafka_intake_records WRITE");
            try {
                long start = System.nanoTime();
                ReflectionTestUtils.invokeMethod(sampler, "sampleQuarantine");
                var elapsed = Duration.ofNanos(System.nanoTime() - start);
                assertThat(elapsed).isGreaterThan(Duration.ofMillis(500)).isLessThan(Duration.ofSeconds(5));
                assertUnavailable();
                assertThat(retainedSnapshot()).isSameAs(snapshot);
            } finally { sql.execute("UNLOCK TABLES"); }
        }
        sampler.sample();
        assertThat(count("MALFORMED_WIRE")).isEqualTo(1);
    }

    @Test void blockedAdvisoryQueryRunsOutsideTheKafkaPollCycle() throws Exception {
        quarantine(CONSUMER, 0, "MALFORMED_WIRE");
        sampler.sample();
        var readyGuard = mock(KafkaRuntimeGuard.class);
        when(readyGuard.consumerReady()).thenReturn(true);
        var intake = new KafkaIntakeRuntime(store, readyGuard, catalog, meters, "consumer", CONSUMER, "KAFKA", 2,
            TOPIC, "localhost:9092", "PLAINTEXT", "", "", "", "", "0,1,2");
        @SuppressWarnings("unchecked") var broker = (KafkaConsumer<byte[], byte[]>) mock(KafkaConsumer.class);
        ReflectionTestUtils.setField(intake, "consumer", broker);
        when(broker.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        when(broker.assignment()).thenReturn(Set.of());
        try (var locker = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var sql = locker.createStatement(); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            sql.execute("LOCK TABLES event_kafka_intake_records WRITE");
            try {
                var pending = executor.submit(() -> ReflectionTestUtils.invokeMethod(sampler, "sampleQuarantine"));
                long until = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                int waiting;
                do {
                    try (var monitor = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                         var check = monitor.createStatement(); var rows = check.executeQuery("SELECT COUNT(*) FROM performance_schema.metadata_locks WHERE OBJECT_NAME='event_kafka_intake_records' AND LOCK_STATUS='PENDING'")) {
                        rows.next(); waiting = rows.getInt(1);
                    }
                    if (waiting == 0) Thread.sleep(10);
                } while (waiting == 0 && System.nanoTime() < until);
                assertThat(waiting).isPositive();
                assertThat(pending).isNotDone();
                long start = System.nanoTime();
                intake.runCycle();
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
                pending.get(5, TimeUnit.SECONDS);
                assertUnavailable();
                verify(broker).poll(any(Duration.class));
                verify(broker, never()).commitSync(anyMap(), any(Duration.class));
            } finally { sql.execute("UNLOCK TABLES"); }
        } finally { intake.close(); }
    }

    @Test void unavailableSocketAndExhaustedObservationPoolHaveFiniteBounds() throws Exception {
        sampler.sample();
        try (var held = source().getConnection()) {
            long start = System.nanoTime();
            ReflectionTestUtils.invokeMethod(sampler, "sampleQuarantine");
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                .isGreaterThan(Duration.ofMillis(500)).isLessThan(Duration.ofSeconds(3));
            assertUnavailable();
        }
        sampler.sample();
        MYSQL.getDockerClient().pauseContainerCmd(MYSQL.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            ReflectionTestUtils.invokeMethod(sampler, "sampleQuarantine");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            assertUnavailable();
        } finally { MYSQL.getDockerClient().unpauseContainerCmd(MYSQL.getContainerId()).exec(); }
        sampler.sample();
        assertThat(gauge("sample.healthy")).isEqualTo(1);
    }

    @Test void failedAdvisoryReadDoesNotChangeDurableIntakeOffsetOrExecutionAuthority() throws Exception {
        for (var consumer : catalog.consumers()) for (var route : consumer.routes()) registrations.activate(route);
        cutover.complete("KAFKA", 100);
        UUID tenant = UUID.randomUUID(), slot = UUID.randomUUID();
        db.update("INSERT INTO tenants(id,status) VALUES (?,'ACTIVE')", bytes(tenant));
        var event = new TransactionTemplate(manager).execute(s -> append.append(new EventEnvelope(EventId.newId(),
            new TenantId(tenant), "SlotInventory", slot, "waitlist.promotion-requested", 1, Instant.now(),
            "{\"venueId\":\"" + UUID.randomUUID() + "\",\"resourceId\":\"" + UUID.randomUUID() + "\",\"slotInventoryId\":\"" + slot + "\"}")));
        var origin = db.queryForObject("SELECT origin_request_id,origin_trace_id,origin_span_id FROM event_records WHERE event_id=?",
            (row, n) -> new ProductTelemetry.Origin(row.getString(1), row.getString(2), row.getString(3)), bytes(event.envelope().eventId().value()));
        var message = mapping.encode(event, origin);
        var record = new ConsumerRecord<byte[], byte[]>(TOPIC, 0, 0, message.key().getBytes(StandardCharsets.UTF_8), message.body().getBytes(StandardCharsets.UTF_8));
        var partition = new TopicPartition(TOPIC, 0);
        @SuppressWarnings("unchecked") var broker = (KafkaConsumer<byte[], byte[]>) mock(KafkaConsumer.class);
        var guard = new KafkaRuntimeGuard(db, new WaitlistKafkaMessage(true, false), catalog,
            new DeliveryExecutionScope(CONSUMER, "KAFKA", 2), "consumer", false, true, true, false, false, "none");
        guard.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
        var intake = new KafkaIntakeRuntime(store, guard, catalog, meters, "consumer", CONSUMER, "KAFKA", 2,
            TOPIC, "localhost:9092", "PLAINTEXT", "", "", "", "", "0,1,2");
        ReflectionTestUtils.setField(intake, "consumer", broker);
        when(broker.assignment()).thenReturn(Set.of(partition));
        when(broker.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Map.of(partition, List.of(record)), Map.of()));
        when(broker.endOffsets(Set.of(partition))).thenReturn(Map.of(partition, 1L));
        when(broker.committed(Set.of(partition))).thenReturn(Map.of(partition, new OffsetAndMetadata(1)));
        doAnswer(call -> {
            // The explicit broker commit observes a completed real MySQL intake transaction.
            assertThat(db.queryForObject("SELECT last_durable_offset FROM event_kafka_consumer_positions WHERE consumer_id=? AND topic=?", Long.class, CONSUMER, TOPIC)).isZero();
            assertPending(event.envelope().eventId());
            return null;
        }).when(broker).commitSync(anyMap(), any(Duration.class));
        try {
            admin("REVOKE SELECT ON slotq_quarantine_observation.event_kafka_intake_records FROM 'quarantine_observer'@'%'");
            sampler.sample();
            assertUnavailable();
            intake.runCycle();
            verify(broker).commitSync(Map.of(partition, new OffsetAndMetadata(1)), Duration.ofSeconds(5));
            assertPending(event.envelope().eventId());
            assertThat(meters.get("slotq.kafka.lag.sample.healthy").gauge().value()).isEqualTo(1);
            var authority = db.queryForList("SELECT HEX(registration_id),transport,authority_epoch FROM event_transport_assignments ORDER BY registration_id");
            sampler.sample();
            assertUnavailable();
            assertThat(db.queryForList("SELECT HEX(registration_id),transport,authority_epoch FROM event_transport_assignments ORDER BY registration_id")).isEqualTo(authority);
            assertThat(directWorker.claim(new DeliveryKey(new TenantId(tenant), event.envelope().eventId(),
                eventRegistration(event.envelope().eventId())))).isEmpty();
            assertPending(event.envelope().eventId());
            assertThat(db.queryForObject("SELECT COUNT(*) FROM waitlist_promotion_receipts WHERE event_id=?", Integer.class, bytes(event.envelope().eventId().value()))).isZero();
        } finally { intake.close(); }
    }

    private UUID eventRegistration(EventId id) {
        byte[] value = db.queryForObject("SELECT registration_id FROM event_deliveries WHERE event_id=?", byte[].class, bytes(id.value()));
        var buffer = ByteBuffer.wrap(value); return new UUID(buffer.getLong(), buffer.getLong());
    }
    private void assertPending(EventId id) {
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE event_id=? AND state='PENDING' AND lifetime_attempts=0 AND cycle_attempts=0 AND fencing_token=0", Integer.class, bytes(id.value()))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_kafka_target_intakes WHERE event_id=? AND consumer_id=? AND authority_epoch=2", Integer.class, bytes(id.value()), CONSUMER)).isEqualTo(1);
    }
    private HikariDataSource source() { return (HikariDataSource) ReflectionTestUtils.getField(sampler, "source"); }
    private DatabaseObservation.Snapshot retainedSnapshot() {
        return (DatabaseObservation.Snapshot) ReflectionTestUtils.invokeMethod(ReflectionTestUtils.getField(sampler, "quarantine"), "snapshot");
    }
    private void assertUnavailable() {
        assertThat(gauge("sample.healthy")).isZero();
        assertThat(gauge("oldest.age.seconds")).isNaN();
        for (String failure : DatabaseObservation.QUARANTINE_FAILURE_CODES) assertThat(count(failure)).isNaN();
    }
    private double gauge(String suffix) { return meters.get("slotq.kafka.quarantine." + suffix).gauge().value(); }
    private double count(String failure) { return meters.get("slotq.kafka.quarantine.records").tag("failure_code", failure).gauge().value(); }
    private void quarantine(String consumer, long offset, String failure) {
        db.update("INSERT INTO event_kafka_intake_records(consumer_id,topic,partition_id,record_offset,record_sha256,disposition,failure_code,intaken_at) VALUES (?,?,0,?,?,'QUARANTINED',?,UTC_TIMESTAMP(6)-INTERVAL 60 SECOND)",
            consumer, "slotq.quarantine126", offset, new byte[32], failure);
    }
    private void admin(String sql) throws Exception {
        try (var c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()); var s = c.createStatement()) { s.execute(sql); }
    }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
}
