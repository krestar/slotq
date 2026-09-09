package com.slotq.experiments.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Deliberately isolated from Product: one synthetic owner, versioned projection and effect row. */
final class EventFixture implements AutoCloseable {
    enum Boundary { SYNCHRONOUS, DIRECT, DURABLE }
    record Event(String eventId, String tenantId, String aggregateId, String eventType,
                 int schemaVersion, Instant occurredAt, int sequence, String payload) { }

    final HikariDataSource pool;
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final ObjectMapper json = new ObjectMapper();

    EventFixture(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(30_000);
        config.setTransactionIsolation("TRANSACTION_REPEATABLE_READ");
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        pool = new HikariDataSource(config);
        db = new JdbcTemplate(pool);
        tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
    }

    void schema() {
        db.execute("""
            CREATE TABLE fixture_owner (
              aggregate_id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL,
              sequence_no INT NOT NULL, UNIQUE(tenant_id, aggregate_id)) ENGINE=InnoDB
            """);
        db.execute("""
            CREATE TABLE fixture_event (
              event_id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL,
              aggregate_id CHAR(36) NOT NULL, event_type VARCHAR(80) NOT NULL,
              schema_version INT NOT NULL CHECK(schema_version > 0), occurred_at TIMESTAMP(6) NOT NULL,
              sequence_no INT NOT NULL, payload JSON NOT NULL,
              FOREIGN KEY(tenant_id, aggregate_id) REFERENCES fixture_owner(tenant_id, aggregate_id)) ENGINE=InnoDB
            """);
        db.execute("""
            CREATE TABLE fixture_effect (
              event_id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL,
              aggregate_id CHAR(36) NOT NULL, meaning JSON NOT NULL,
              FOREIGN KEY(tenant_id, aggregate_id) REFERENCES fixture_owner(tenant_id, aggregate_id)) ENGINE=InnoDB
            """);
        db.execute("""
            CREATE TABLE fixture_projection (
              aggregate_id CHAR(36) PRIMARY KEY, tenant_id CHAR(36) NOT NULL, sequence_no INT NOT NULL,
              FOREIGN KEY(tenant_id, aggregate_id) REFERENCES fixture_owner(tenant_id, aggregate_id)) ENGINE=InnoDB
            """);
    }

    Event event(long seed, int sequence) {
        var random = new java.util.Random(seed);
        String aggregate = new UUID(random.nextLong(), random.nextLong()).toString();
        String tenant = new UUID(random.nextLong(), random.nextLong()).toString();
        // Independent opaque identities, reproducible only in this test fixture.
        String id = new UUID(random.nextLong(), random.nextLong() + sequence).toString();
        return new Event(id, tenant, aggregate, "FixtureChanged", 1,
            Instant.parse("2026-09-09T00:00:00Z").plusSeconds(sequence), sequence, "{\"valid\":true}");
    }

    void business(Event event) {
        db.update("""
            INSERT INTO fixture_owner VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE sequence_no = GREATEST(sequence_no, ?)
            """, event.aggregateId(), event.tenantId(), event.sequence(), event.sequence());
    }

    void append(Event event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("append requires caller transaction");
        }
        db.update("INSERT INTO fixture_event VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            event.eventId(), event.tenantId(), event.aggregateId(), event.eventType(),
            event.schemaVersion(), Timestamp.from(event.occurredAt()), event.sequence(), event.payload());
    }

    java.util.List<Event> storedEvents() {
        return db.query("SELECT * FROM fixture_event ORDER BY occurred_at", (row, n) -> new Event(
            row.getString("event_id"), row.getString("tenant_id"), row.getString("aggregate_id"),
            row.getString("event_type"), row.getInt("schema_version"),
            row.getTimestamp("occurred_at").toInstant(), row.getInt("sequence_no"), row.getString("payload")));
    }

    void produce(Boundary boundary, Event event, String fault) {
        tx.executeWithoutResult(status -> {
            business(event);
            if (boundary == Boundary.DURABLE) append(event);
            if (boundary == Boundary.SYNCHRONOUS) effect(event, fault);
            if (fault.equals("BEFORE_COMMIT")) throw new IllegalStateException("injected rollback");
        });
        crash(fault, "AFTER_COMMIT");
        if (boundary == Boundary.DIRECT) tx.executeWithoutResult(status -> effect(event, fault));
    }

    void effect(Event event, String fault) {
        String tenant = db.queryForObject(
            "SELECT tenant_id FROM fixture_owner WHERE aggregate_id=? FOR UPDATE",
            String.class, event.aggregateId());
        if (!event.tenantId().equals(tenant)) throw new IllegalArgumentException("TENANT_MISMATCH");
        if (!event.eventType().equals("FixtureChanged")) throw new IllegalArgumentException("UNKNOWN_TYPE");
        if (event.schemaVersion() != 1) throw new IllegalArgumentException("UNKNOWN_VERSION");
        if (!json.readTree(event.payload()).path("valid").asBoolean()) {
            throw new IllegalArgumentException("PAYLOAD_INVARIANT");
        }
        var meaningTree = (tools.jackson.databind.node.ObjectNode) json.valueToTree(event);
        meaningTree.set("payload", json.readTree(event.payload()));
        String meaning = json.writeValueAsString(meaningTree);
        var existing = db.queryForList("SELECT meaning FROM fixture_effect WHERE event_id=?", String.class,
            event.eventId());
        if (!existing.isEmpty()) {
            if (!json.readTree(existing.getFirst()).equals(json.readTree(meaning))) {
                throw new IllegalArgumentException("IDENTITY_CORRUPTION");
            }
            return;
        }
        if (fault.equals("HANDLER_FAILURE")) throw new IllegalStateException("TRANSIENT_FIXTURE");
        if (fault.equals("SLOW_HANDLER")) {
            try { Thread.sleep(100); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        }
        db.update("INSERT INTO fixture_effect VALUES (?, ?, ?, ?)", event.eventId(), event.tenantId(),
            event.aggregateId(), meaning);
        // The event is a wake-up: current authoritative state, never an instruction to restore old state.
        db.update("""
            INSERT INTO fixture_projection SELECT aggregate_id, tenant_id, sequence_no
            FROM fixture_owner WHERE aggregate_id=?
            ON DUPLICATE KEY UPDATE sequence_no = GREATEST(fixture_projection.sequence_no,
              (SELECT sequence_no FROM fixture_owner WHERE aggregate_id=?))
            """, event.aggregateId(), event.aggregateId());
    }

    static void crash(String fault, String point) {
        if (fault.equals(point)) Runtime.getRuntime().halt(80);
    }

    Map<String, Object> snapshot(Event event) {
        return Map.of("business", count("fixture_owner", "aggregate_id", event.aggregateId()),
            "durable", count("fixture_event", "event_id", event.eventId()),
            "effects", count("fixture_effect", "event_id", event.eventId()));
    }

    int count(String table, String key, String value) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + key + "=?", Integer.class, value);
    }

    @Override public void close() { pool.close(); }
}
