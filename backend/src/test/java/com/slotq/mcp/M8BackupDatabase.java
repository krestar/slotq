package com.slotq.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.slotq.mcp.M8CapacityEvidence.*;

/** Independent JDBC oracle. Every cell and identity is compared before any service can mutate a restore. */
final class M8BackupDatabase {
    private M8BackupDatabase() { }

    static Map<String, Object> snapshot(JdbcTemplate db) {
        var result = new TreeMap<String, Object>();
        for (String table : db.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME", String.class)) {
            require(table.matches("[a-z_]+"), "Unexpected table identifier");
            var rows = db.query("SELECT * FROM `" + table + "`", (rs, n) -> {
                var row = new TreeMap<String, String>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    byte[] value = rs.getBytes(i);
                    row.put(rs.getMetaData().getColumnName(i), value == null ? null : HexFormat.of().formatHex(value));
                }
                return JSON.writeValueAsString(row);
            });
            Collections.sort(rows);
            result.put(table, Map.of("count", rows.size(), "sha256", hash(JSON.writeValueAsString(rows)), "rows", rows));
        }
        require(result.containsKey("flyway_schema_history") && result.size() >= 30, "Incomplete database inventory");
        return result;
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    static Map<String, Long> invariants(JdbcTemplate db) {
        var checks = new LinkedHashMap<String, Long>();
        checks.put("partialReceipt", count(db, "SELECT COUNT(*) FROM waitlist_promotion_receipts WHERE outcome IS NULL"));
        checks.put("doneWithoutReceipt", count(db, "SELECT COUNT(*) FROM event_deliveries d "
            + "JOIN event_registrations g ON g.registration_id=d.registration_id "
            + "LEFT JOIN waitlist_promotion_receipts p ON p.tenant_id=d.tenant_id AND p.event_id=d.event_id AND p.consumer_id=g.consumer_id "
            + "WHERE g.consumer_id='waitlist.promotion' AND d.state='DONE' AND p.event_id IS NULL"));
        checks.put("receiptEffect", count(db, "SELECT COUNT(*) FROM waitlist_promotion_receipts p "
            + "LEFT JOIN waitlist_offers o ON o.id=p.offer_id AND o.tenant_id=p.tenant_id AND o.venue_id=p.venue_id "
            + "AND o.resource_id=p.resource_id AND o.slot_inventory_id=p.slot_inventory_id "
            + "AND o.entry_id=p.entry_id AND o.reservation_id=p.reservation_id "
            + "LEFT JOIN waitlist_notification_requests n ON n.tenant_id=p.tenant_id AND n.offer_id=p.offer_id "
            + "AND n.venue_id=p.venue_id AND n.entry_id=p.entry_id AND n.reservation_id=p.reservation_id "
            + "WHERE p.outcome='PROMOTED' AND (o.id IS NULL OR n.offer_id IS NULL)"));
        checks.put("receiptWithoutDone", count(db, "SELECT COUNT(*) FROM waitlist_promotion_receipts p "
            + "LEFT JOIN event_records e ON e.tenant_id=p.tenant_id AND e.event_id=p.event_id "
            + "LEFT JOIN event_registrations r ON r.consumer_id=p.consumer_id AND r.event_type=e.event_type AND r.schema_version=e.schema_version "
            + "AND e.boundary_sequence>r.activation_boundary AND (r.deactivation_boundary IS NULL OR e.boundary_sequence<r.deactivation_boundary) "
            + "LEFT JOIN event_deliveries d ON d.tenant_id=p.tenant_id AND d.event_id=p.event_id AND d.registration_id=r.registration_id "
            + "WHERE d.state IS NULL OR d.state<>'DONE'"));
        checks.put("observerDoneWithoutEffect", count(db, "SELECT COUNT(*) FROM event_deliveries d "
            + "JOIN event_registrations g ON g.registration_id=d.registration_id "
            + "LEFT JOIN event_observation_projections p ON p.tenant_id=d.tenant_id AND p.event_id=d.event_id AND p.consumer_id=g.consumer_id "
            + "WHERE g.consumer_id='operations.event-observation' AND d.state='DONE' AND p.event_id IS NULL"));
        checks.put("observerEffectWithoutDone", count(db, "SELECT COUNT(*) FROM event_observation_projections p "
            + "LEFT JOIN event_deliveries d ON d.tenant_id=p.tenant_id AND d.event_id=p.event_id AND d.registration_id=p.registration_id "
            + "WHERE d.state IS NULL OR d.state<>'DONE'"));
        checks.put("allocationScope", count(db, "SELECT COUNT(*) FROM capacity_allocations a LEFT JOIN reservations r "
            + "ON r.id=a.reservation_id AND r.tenant_id=a.tenant_id AND r.venue_id=a.venue_id "
            + "AND r.slot_inventory_id=a.slot_inventory_id AND r.resource_id=a.resource_id WHERE r.id IS NULL"));
        checks.put("terminalAllocation", count(db, "SELECT COUNT(*) FROM capacity_allocations a JOIN reservations r ON r.id=a.reservation_id "
            + "WHERE a.active=TRUE AND r.state IN ('CANCELLED','EXPIRED','COMPLETED','NO_SHOW')"));
        checks.put("capacity", count(db, "SELECT COUNT(*) FROM (SELECT a.slot_inventory_id FROM capacity_allocations a "
            + "JOIN reservations r ON r.id=a.reservation_id WHERE a.active=TRUE AND (r.state IN ('CONFIRMED','CHECKED_IN') "
            + "OR (r.state='HELD' AND r.expires_at>UTC_TIMESTAMP(6))) GROUP BY a.slot_inventory_id HAVING SUM(a.units)>1) x"));
        checks.put("finiteAttempts", count(db, "SELECT COUNT(*) FROM event_deliveries WHERE cycle_attempts>5 OR cycle_attempts>lifetime_attempts OR fencing_token<0"));
        checks.put("transport", count(db, "SELECT COUNT(*) FROM event_transport_assignments WHERE transport<>'DB_DIRECT' OR authority_epoch<1"));
        checks.put("audit", count(db, "SELECT COUNT(*) FROM operations_recovery_operations o LEFT JOIN operations_recovery_audit a "
            + "ON a.operation_id=o.operation_id WHERE a.operation_id IS NULL OR a.result_json<>o.result_json"));
        checks.put("idempotency", count(db, "SELECT COUNT(*) FROM hold_idempotency_records i LEFT JOIN reservations r "
            + "ON r.id=i.reservation_id AND r.tenant_id=i.tenant_id AND r.venue_id=i.venue_id "
            + "WHERE i.state='COMPLETED' AND (r.id IS NULL OR r.slot_inventory_id<>i.slot_inventory_id "
            + "OR r.customer_principal_id<>i.customer_principal_id OR r.party_size<>i.party_size)"));
        checks.put("publication", count(db, "SELECT COUNT(*) FROM knowledge_documents d LEFT JOIN knowledge_versions v "
            + "ON v.tenant_id=d.tenant_id AND v.venue_id=d.venue_id AND v.document_id=d.document_id AND v.version_id=d.current_version_id "
            + "WHERE d.state='ACTIVE' AND (v.version_id IS NULL OR v.state<>'PUBLISHED' OR SHA2(v.content,256)<>v.content_digest)"));
        // mysqldump restores with FK checks disabled. Re-enabling them does not validate existing rows.
        var foreignKeys = db.queryForList("SELECT TABLE_NAME,CONSTRAINT_NAME,REFERENCED_TABLE_NAME,"
            + "GROUP_CONCAT(CONCAT('c.`',COLUMN_NAME,'`=p.`',REFERENCED_COLUMN_NAME,'`') ORDER BY ORDINAL_POSITION SEPARATOR ' AND ') join_sql,"
            + "GROUP_CONCAT(CONCAT('c.`',COLUMN_NAME,'` IS NOT NULL') ORDER BY ORDINAL_POSITION SEPARATOR ' AND ') nonnull_sql,"
            + "MIN(REFERENCED_COLUMN_NAME) parent_column FROM information_schema.KEY_COLUMN_USAGE "
            + "WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL GROUP BY TABLE_NAME,CONSTRAINT_NAME,REFERENCED_TABLE_NAME");
        for (var fk : foreignKeys) {
            checks.put("fk:" + fk.get("TABLE_NAME") + ":" + fk.get("CONSTRAINT_NAME"), count(db,
                "SELECT COUNT(*) FROM `" + fk.get("TABLE_NAME") + "` c LEFT JOIN `" + fk.get("REFERENCED_TABLE_NAME")
                + "` p ON " + fk.get("join_sql") + " WHERE " + fk.get("nonnull_sql") + " AND p.`" + fk.get("parent_column") + "` IS NULL"));
        }
        return checks;
    }

    static long count(JdbcTemplate db, String sql) { return db.queryForObject(sql, Long.class); }

    static Map<String, Long> history(tools.jackson.databind.JsonNode before, tools.jackson.databind.JsonNode after) {
        var violations = new TreeMap<String, Long>();
        for (String table : List.of("event_records", "event_registrations", "event_transport_assignments", "event_replay_audit",
                "operations_recovery_operations", "operations_recovery_audit", "mcp_hold_intents", "mcp_hold_confirmations", "hold_idempotency_records",
                "waitlist_promotion_receipts")) {
            var actual = new HashSet<String>();
            after.path(table).path("rows").forEach(row -> actual.add(row.asString()));
            long changed = 0;
            for (var row : before.path(table).path("rows")) if (!actual.contains(row.asString())) changed++;
            violations.put(table, changed);
        }
        var actualDeliveries = new HashMap<String, tools.jackson.databind.JsonNode>();
        for (var row : after.path("event_deliveries").path("rows")) {
            var value = JSON.readTree(row.asString());
            actualDeliveries.put(deliveryKey(value), value);
        }
        long terminalChanged = 0, fenceRegressed = 0;
        for (var row : before.path("event_deliveries").path("rows")) {
            var old = JSON.readTree(row.asString());
            var current = actualDeliveries.get(deliveryKey(old));
            String state = cell(old, "state");
            if (Set.of("DONE", "DEAD").contains(state) && !old.equals(current)) terminalChanged++;
            if (current == null || Long.parseLong(cell(current, "fencing_token")) < Long.parseLong(cell(old, "fencing_token"))
                    || Long.parseLong(cell(current, "lifetime_attempts")) < Long.parseLong(cell(old, "lifetime_attempts"))) fenceRegressed++;
        }
        violations.put("terminalDeliveryChanged", terminalChanged);
        violations.put("fenceOrLifetimeRegressed", fenceRegressed);
        return violations;
    }

    private static String deliveryKey(tools.jackson.databind.JsonNode row) {
        return row.path("tenant_id").asString() + ":" + row.path("event_id").asString() + ":" + row.path("registration_id").asString();
    }

    private static String cell(tools.jackson.databind.JsonNode row, String key) {
        return new String(HexFormat.of().parseHex(row.path(key).asString()), StandardCharsets.UTF_8);
    }
}
