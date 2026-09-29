package com.slotq.events;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
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

    @Test void quiescedBidirectionalCutoverRepairsOriginalTargetsBeyondTheSharedCursor() {
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

        var toKafka = finish("KAFKA");
        assertThat(toKafka.authorityEpoch()).isEqualTo(2);
        assertThat(cutover.inventory().missingTargets()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries", Integer.class)).isEqualTo(2);
        // A worker that started under DB_DIRECT epoch 1 cannot claim the transferred target.
        assertThat(directWorker.claim(key)).isEmpty();

        EventEnvelope duringKafka = event(tenant);
        new TransactionTemplate(manager).executeWithoutResult(status -> append.append(duringKafka));
        long sharedCursorBefore = db.queryForObject(
            "SELECT boundary_sequence FROM event_discovery WHERE singleton_id=1", Long.class);
        assertThat(directWorker.materialize()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries", Integer.class)).isEqualTo(2);
        long sharedCursorAfter = db.queryForObject(
            "SELECT boundary_sequence FROM event_discovery WHERE singleton_id=1", Long.class);
        assertThat(sharedCursorAfter).isGreaterThan(sharedCursorBefore);

        var toDirect = finish("DB_DIRECT");
        assertThat(toDirect.authorityEpoch()).isEqualTo(3);
        assertThat(cutover.inventory().missingTargets()).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_transport_assignments"
            + " WHERE transport='DB_DIRECT' AND authority_epoch=3", Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries"
            + " WHERE cycle_attempts<>0 OR lifetime_attempts<>0", Integer.class)).isZero();
        assertThat(directWorker.claim(key)).isEmpty(); // stale epoch 1, even after rollback
    }

    private EventTransportCutover.CutoverState finish(String transport) {
        var state = cutover.prepare(transport);
        while (!state.phase().equals("READY")) state = cutover.scan(1).state();
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
