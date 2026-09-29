package com.slotq.events;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryKey;
import com.slotq.events.application.EventAppendService;
import com.slotq.events.application.EventDeliveryWorker;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.events.persistence.EventTransportCutover;
import com.slotq.tenancy.domain.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class EventTransportCutoverIntegrationTests {
    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_cutover");

    @Autowired JdbcTemplate db;
    @Autowired EventRegistrationService registrations;
    @Autowired EventAppendService append;
    @Autowired EventDeliveryWorker directWorker;
    @Autowired EventTransportCutover cutover;
    @Autowired PlatformTransactionManager manager;

    @Test void quiescedBidirectionalCutoverRepairsOriginalTargetsBeyondTheSharedCursor() throws Exception {
        List<Map<String, Object>> timeline = new ArrayList<>();
        UUID tenant = UUID.randomUUID();
        db.update("INSERT INTO tenants (id,status) VALUES (?, 'ACTIVE')", bytes(tenant));
        var routes = List.of(
            new ConsumerRoute("waitlist.promotion", "booking.capacity-released", 1),
            new ConsumerRoute("waitlist.promotion", "waitlist.promotion-requested", 1),
            new ConsumerRoute("operations.event-observation", "booking.capacity-released", 1),
            new ConsumerRoute("operations.event-observation", "waitlist.promotion-requested", 1));
        UUID waitlistRegistration = null;
        for (var route : routes) {
            UUID id = registrations.activate(route);
            if (route.consumerId().equals("waitlist.promotion")
                && route.eventType().equals("waitlist.promotion-requested")) waitlistRegistration = id;
        }
        assertThat(waitlistRegistration).isNotNull();
        EventEnvelope before = event(tenant);
        new TransactionTemplate(manager).executeWithoutResult(status -> append.append(before));
        assertThat(directWorker.materialize()).isEqualTo(2);
        var key = new DeliveryKey(new TenantId(tenant), before.eventId(), waitlistRegistration);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries", Integer.class)).isEqualTo(2);
        timeline.add(snapshot("before-kafka-cutover"));

        var toKafka = finish("KAFKA");
        assertThat(toKafka.authorityEpoch()).isEqualTo(2);
        assertThat(cutover.inventory().missingTargets()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries", Integer.class)).isEqualTo(2);
        // A worker that started under DB_DIRECT epoch 1 cannot claim the transferred target.
        assertThat(directWorker.claim(key)).isEmpty();
        timeline.add(snapshot("kafka-authority-old-db-owner-rejected"));

        EventEnvelope duringKafka = event(tenant);
        new TransactionTemplate(manager).executeWithoutResult(status -> append.append(duringKafka));
        long sharedCursorBefore = db.queryForObject(
            "SELECT boundary_sequence FROM event_discovery WHERE singleton_id=1", Long.class);
        assertThat(directWorker.materialize()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries", Integer.class)).isEqualTo(2);
        long sharedCursorAfter = db.queryForObject(
            "SELECT boundary_sequence FROM event_discovery WHERE singleton_id=1", Long.class);
        assertThat(sharedCursorAfter).isGreaterThan(sharedCursorBefore);
        timeline.add(snapshot("kafka-original-without-db-direct-target"));

        var toDirect = finish("DB_DIRECT");
        assertThat(toDirect.authorityEpoch()).isEqualTo(3);
        assertThat(cutover.inventory().missingTargets()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_transport_assignments"
            + " WHERE transport='DB_DIRECT' AND authority_epoch=3", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries"
            + " WHERE cycle_attempts<>0 OR lifetime_attempts<>0", Integer.class)).isZero();
        assertThat(directWorker.claim(key)).isEmpty(); // stale epoch 1, even after rollback
        timeline.add(snapshot("rollback-epoch-three-old-owner-rejected"));
        String evidence = System.getProperty("slotq.kafka.evidence.dir");
        if (evidence != null) {
            Path output = Path.of(evidence).resolve("cutover-raw.json");
            Files.createDirectories(output.getParent());
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("schemaVersion", "slotq-kafka-cutover-fault/v1");
            raw.put("seed", 109);
            raw.put("mysqlVersion", db.queryForObject("SELECT VERSION()", String.class));
            raw.put("mysqlIsolation", db.queryForObject("SELECT @@transaction_isolation", String.class));
            raw.put("revision", System.getProperty("slotq.kafka.evidence.revision", "unrecorded"));
            raw.put("pid", ProcessHandle.current().pid());
            raw.put("originals", List.of(before.eventId().value().toString(),
                duringKafka.eventId().value().toString()));
            raw.put("timeline", timeline);
            Files.writeString(output, new JsonMapper().writeValueAsString(raw));
        }
    }

    private EventTransportCutover.CutoverState finish(String transport) {
        var state = cutover.prepare(transport);
        while (!state.phase().equals("READY")) state = cutover.scan(1).state();
        return state;
    }

    private Map<String, Object> snapshot(String phase) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("phase", phase);
        state.put("at", Instant.now().toString());
        state.put("cutover", db.queryForList("""
            SELECT to_transport transport,authority_epoch authorityEpoch,phase
              FROM event_transport_cutover WHERE singleton_id=1
            """));
        state.put("originals", db.queryForList("""
            SELECT HEX(event_id) eventId,boundary_sequence boundarySequence
              FROM event_records ORDER BY boundary_sequence
            """));
        state.put("assignments", db.queryForList("""
            SELECT r.consumer_id consumerId,r.event_type eventType,a.transport,
                   a.authority_epoch authorityEpoch
              FROM event_registrations r JOIN event_transport_assignments a
                ON a.registration_id=r.registration_id ORDER BY r.consumer_id,r.event_type
            """));
        state.put("deliveries", db.queryForList("""
            SELECT HEX(event_id) eventId,state,cycle_attempts cycleAttempts,
                   lifetime_attempts lifetimeAttempts,fencing_token fencingToken
              FROM event_deliveries ORDER BY event_id,registration_id
            """));
        state.put("receipts", db.queryForList("""
            SELECT HEX(event_id) eventId FROM waitlist_promotion_receipts ORDER BY event_id
            """));
        state.put("dbDeliveryCursor", db.queryForObject(
            "SELECT boundary_sequence FROM event_discovery WHERE singleton_id=1", Long.class));
        return state;
    }

    private static EventEnvelope event(UUID tenant) {
        return new EventEnvelope(EventId.newId(), new TenantId(tenant), "SlotInventory", UUID.randomUUID(),
            "waitlist.promotion-requested", 1, Instant.now(), "{}");
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
