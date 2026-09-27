package com.slotq.observability;

import java.nio.ByteBuffer;
import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class DatabaseObservationIntegrationTests {
    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
            new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_observation");
    @Autowired JdbcTemplate jdbc;
    private final DatabaseObservation observation = new DatabaseObservation();
    private final byte[] tenant = bytes(UUID.randomUUID());
    private final byte[] registration = bytes(UUID.randomUUID());

    @BeforeEach void prepare() {
        jdbc.update("DELETE FROM waitlist_promotion_requests");
        jdbc.update("DELETE FROM waitlist_promotion_receipts");
        jdbc.update("DELETE FROM event_deliveries");
        jdbc.update("DELETE FROM event_records");
        jdbc.update("DELETE FROM event_registrations");
        jdbc.update("UPDATE event_discovery SET boundary_sequence=0");
        jdbc.update("UPDATE event_boundary SET sequence_value=0");
        jdbc.update("INSERT INTO tenants(id,status) VALUES (?, 'ACTIVE')", tenant);
        jdbc.update("INSERT INTO event_registrations(registration_id,consumer_id,event_type,schema_version,activation_boundary) VALUES (?, 'waitlist.promotion', 'waitlist.promotion-requested', 1, 1)", registration);
    }

    @Test void routeLifecycleGapsAreNotUndiscoveredEventsAndLegacyPayloadNeverBecomesDimensions() throws Exception {
        jdbc.update("UPDATE event_boundary SET sequence_value=47");
        insertEvent(11);
        insertEvent(42);
        var snapshot = read();
        assertThat(value(snapshot, "event.undiscovered")).isEqualTo(2);
        assertThat(snapshot.values().keySet().toString()).doesNotContain("customer@example.invalid", "secret", "payload", "tenant_id", "event_id");
        jdbc.update("UPDATE event_discovery SET boundary_sequence=11");
        assertThat(value(read(), "event.undiscovered")).isEqualTo(1);
    }

    @Test void pendingDeadDoneAndSuccessfulNoOpHaveDistinctAuthoritativeInventory() throws Exception {
        byte[] pending = insertEvent(1);
        byte[] dead = insertEvent(2);
        byte[] done = insertEvent(3);
        delivery(pending, "PENDING"); delivery(dead, "DEAD"); delivery(done, "DONE");
        jdbc.update("INSERT INTO waitlist_promotion_requests(tenant_id,slot_inventory_id,last_event_id) VALUES (?,?,?)", tenant, pending, pending);
        jdbc.update("INSERT INTO waitlist_promotion_receipts(tenant_id,consumer_id,event_id,signal_type,source_id,occurred_at,venue_id,resource_id,slot_inventory_id,outcome) VALUES (?, 'waitlist.promotion', ?, 'PROMOTION_REQUESTED', ?, UTC_TIMESTAMP(6), ?, ?, ?, 'NO_CAPACITY')", tenant, done, done, done, done, done);
        var snapshot = read();
        for (String state : new String[] {"PENDING", "DEAD", "DONE"}) {
            assertThat(value(snapshot, "delivery.targets", "delivery_state", state)).isEqualTo(1);
        }
        assertThat(value(snapshot, "business.outstanding.targets", "delivery_state", "DONE")).isZero();
        assertThat(value(snapshot, "business.outstanding.targets", "delivery_state", "DEAD")).isEqualTo(1);
        assertThat(value(snapshot, "promotion.receipts", "promotion_outcome", "NO_CAPACITY")).isEqualTo(1);
        assertThat(value(snapshot, "promotion.receipts", "promotion_outcome", "PROMOTED")).isZero();
        assertThat(value(snapshot, "promotion.requests.outstanding")).isEqualTo(1);
        assertThat(value(snapshot, "delivery.retry.due")).isEqualTo(1);
        assertThat(value(snapshot, "delivery.oldest.created.age.seconds", "delivery_state", "DEAD")).isGreaterThanOrEqualTo(120);
    }

    @Test void cappedInventoryNeverClaimsTheFullCount() throws Exception {
        jdbc.execute("INSERT INTO event_records(event_id,tenant_id,aggregate_type,aggregate_id,event_type,schema_version,occurred_at,payload,boundary_sequence) "
                + "SELECT UUID_TO_BIN(UUID()), (SELECT id FROM tenants LIMIT 1), 'Slot', UUID_TO_BIN(UUID()), 'waitlist.promotion-requested', 1, UTC_TIMESTAMP(6), '{}', "
                + "a.n+b.n*10+c.n*100+d.n*1000+e.n*10000+1 FROM "
                + digits("a") + " CROSS JOIN " + digits("b") + " CROSS JOIN " + digits("c") + " CROSS JOIN " + digits("d") + " CROSS JOIN " + digits("e") + " LIMIT 10001");
        var snapshot = read();
        assertThat(value(snapshot, "event.undiscovered")).isEqualTo(DatabaseObservation.LIMIT);
        assertThat(value(snapshot, "sample.truncated", "sample", "events")).isEqualTo(1);
    }

    @Test void nonlockingReadDoesNotObserveUncommittedDeliveryOrChangeRollback() throws Exception {
        byte[] event = insertEvent(1); delivery(event, "PENDING");
        try (var writer = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            writer.setAutoCommit(false);
            try (var update = writer.prepareStatement("UPDATE event_deliveries SET state='DEAD', next_attempt_at=NULL WHERE event_id=?")) {
                update.setBytes(1, event); update.executeUpdate();
            }
            assertThat(value(read(), "delivery.targets", "delivery_state", "PENDING")).isEqualTo(1);
            writer.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT state FROM event_deliveries WHERE event_id=?", String.class, event)).isEqualTo("PENDING");
    }

    private DatabaseObservation.Snapshot read() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.setReadOnly(true);
            return observation.read(connection);
        }
    }
    private byte[] insertEvent(long sequence) {
        byte[] id = bytes(UUID.randomUUID());
        jdbc.update("INSERT INTO event_records(event_id,tenant_id,aggregate_type,aggregate_id,event_type,schema_version,occurred_at,payload,boundary_sequence) VALUES (?,?,'Slot',?,'waitlist.promotion-requested',1,UTC_TIMESTAMP(6),?,?)", id, tenant, id, "{\"secret\":\"customer@example.invalid\"}", sequence);
        return id;
    }
    private void delivery(byte[] event, String state) {
        jdbc.update("INSERT INTO event_deliveries(tenant_id,event_id,registration_id,state,cycle_attempts,lifetime_attempts,fencing_token,next_attempt_at,created_at) VALUES (?,?,?, ?,1,1,1,IF(?='PENDING',UTC_TIMESTAMP(6),NULL),UTC_TIMESTAMP(6)-INTERVAL 120 SECOND)", tenant,event,registration,state,state);
    }
    private static String digits(String alias) { return "(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) " + alias; }
    private static double value(DatabaseObservation.Snapshot s, String name) { return value(s,name,"",""); }
    private static double value(DatabaseObservation.Snapshot s, String name, String key, String val) { return s.values().get(new DatabaseObservation.Key(name,key,val)); }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
}
