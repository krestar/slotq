package com.slotq.observability;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Read-only advisory samples. Never used by a Product decision or transaction. */
public final class DatabaseObservation {
    public static final int LIMIT = 10_000;
    public static final Set<String> STATES = Set.of("PENDING", "PROCESSING", "DONE", "DEAD");
    public static final Set<String> OUTCOMES = Set.of("PROMOTED", "NO_CAPACITY", "NO_CANDIDATE", "NOT_ELIGIBLE", "SLOT_PAST", "DEFERRED");
    public static final Set<String> QUARANTINE_FAILURE_CODES = Set.of("MALFORMED_WIRE", "UNKNOWN_ORIGINAL",
        "IDENTITY_CORRUPTION", "CANONICAL_CORRUPTION", "REGISTRATION_CORRUPTION");
    public record Key(String metric, String dimension, String value) { }
    public record Snapshot(Map<Key, Double> values, Instant observedAt) { }

    /** One nonlocking query; fixed groups, server/JDBC timeout and observer socket bounds. */
    public Snapshot readKafkaQuarantine(Connection connection, String consumerId) throws SQLException {
        if (!Set.of("waitlist.promotion", "operations.event-observation").contains(consumerId))
            throw new IllegalArgumentException("Unknown logical consumer");
        var values = new LinkedHashMap<Key, Double>();
        for (String failure : QUARANTINE_FAILURE_CODES)
            put(values, "quarantine.records", "failure_code", failure, 0);
        put(values, "quarantine.oldest.age.seconds", 0);
        query(connection, "SELECT failure_code,COUNT(*), "
            + "TIMESTAMPDIFF(SECOND,MIN(intaken_at),UTC_TIMESTAMP(6)) "
            + "FROM event_kafka_intake_records WHERE consumer_id='" + consumerId
            + "' AND disposition='QUARANTINED' GROUP BY failure_code", rows -> {
                String failure = rows.getString(1);
                if (!QUARANTINE_FAILURE_CODES.contains(failure))
                    throw new SQLException("Unknown quarantine failure code");
                put(values, "quarantine.records", "failure_code", failure, rows.getLong(2));
                values.merge(new Key("quarantine.oldest.age.seconds", "", ""),
                    Math.max(0, rows.getDouble(3)), Math::max);
            });
        return new Snapshot(Map.copyOf(values), Instant.now());
    }

    public Snapshot read(Connection connection) throws SQLException {
        var values = new LinkedHashMap<Key, Double>();
        // Boundary also advances for route lifecycle changes; a sequence difference is not an event count.
        int[] undiscovered = {0};
        query(connection, "SELECT e.event_id FROM event_records e "
                + "WHERE e.boundary_sequence > (SELECT boundary_sequence FROM event_discovery WHERE singleton_id=1) "
                + "ORDER BY e.boundary_sequence LIMIT " + (LIMIT + 1), rows -> undiscovered[0]++);
        put(values, "event.undiscovered", Math.min(undiscovered[0], LIMIT));
        put(values, "sample.truncated", "sample", "events", undiscovered[0] > LIMIT ? 1 : 0);
        readDeliveries(connection, values);
        readPublications(connection, values);
        for (String outcome : OUTCOMES) put(values, "promotion.receipts", "promotion_outcome", outcome, 0);
        int[] receiptCount = {0};
        query(connection, "SELECT outcome FROM waitlist_promotion_receipts ORDER BY occurred_at, tenant_id, consumer_id, event_id LIMIT " + (LIMIT + 1), rows -> {
            if (++receiptCount[0] <= LIMIT && OUTCOMES.contains(rows.getString(1))) {
                increment(values, "promotion.receipts", "promotion_outcome", rows.getString(1));
            }
        });
        put(values, "sample.truncated", "sample", "receipts", receiptCount[0] > LIMIT ? 1 : 0);
        int[] outstandingCount = {0};
        query(connection, "SELECT q.last_event_id FROM waitlist_promotion_requests q "
                + "LEFT JOIN waitlist_promotion_receipts p ON p.tenant_id=q.tenant_id AND p.event_id=q.last_event_id AND p.consumer_id='waitlist.promotion' "
                + "WHERE q.last_event_id IS NOT NULL AND p.outcome IS NULL LIMIT " + (LIMIT + 1), rows -> outstandingCount[0]++);
        put(values, "promotion.requests.outstanding", Math.min(outstandingCount[0], LIMIT));
        put(values, "sample.truncated", "sample", "requests", outstandingCount[0] > LIMIT ? 1 : 0);
        return new Snapshot(Map.copyOf(values), Instant.now());
    }

    private void readPublications(Connection connection, Map<Key,Double> values) throws SQLException {
        for(String state:Set.of("PENDING","PROCESSING","PUBLISHED","DEAD")) {
            put(values,"publication.targets","publication_state",state,0);
            put(values,"publication.oldest.recorded.age.seconds","publication_state",state,0);
            int[] count={0};
            query(connection,"SELECT TIMESTAMPDIFF(MICROSECOND,e.recorded_at,UTC_TIMESTAMP(6))/1000000.0 "
                + "FROM event_kafka_publications p JOIN event_records e ON e.tenant_id=p.tenant_id AND e.event_id=p.event_id "
                + "WHERE p.state='"+state+"' ORDER BY p.discovered_boundary LIMIT "+(LIMIT+1),rows->{
                    if(++count[0]>LIMIT)return;
                    increment(values,"publication.targets","publication_state",state);
                    values.merge(new Key("publication.oldest.recorded.age.seconds","publication_state",state),Math.max(0,rows.getDouble(1)),Math::max);
                });
            put(values,"sample.truncated","sample","publications_"+state.toLowerCase(java.util.Locale.ROOT),count[0]>LIMIT?1:0);
        }
        int[] expired={0};
        query(connection,"SELECT 1 FROM event_kafka_publications WHERE state='PROCESSING' AND lease_until<=UTC_TIMESTAMP(6) LIMIT "+(LIMIT+1),rows->expired[0]++);
        put(values,"publication.expired.claims",Math.min(expired[0],LIMIT));
        put(values,"sample.truncated","sample","publication_expired",expired[0]>LIMIT?1:0);
    }

    /** Scoped Kafka execution inventory; a broker offset never supplies this business state. */
    public Snapshot readKafkaDeliveries(Connection connection, String consumerId) throws SQLException {
        if (!Set.of("waitlist.promotion", "operations.event-observation").contains(consumerId))
            throw new IllegalArgumentException("Unknown logical consumer");
        var values = new LinkedHashMap<Key, Double>();
        for (String state : STATES) {
            put(values, "delivery.targets", "delivery_state", state, 0);
            put(values, "delivery.oldest.created.age.seconds", "delivery_state", state, 0);
        }
        put(values, "delivery.retry.due", 0);
        put(values, "delivery.retry.backoff", 0);
        for (String state : STATES) {
            int[] count = {0};
            query(connection, "SELECT d.lifetime_attempts, "
                + "TIMESTAMPDIFF(MICROSECOND,d.created_at,UTC_TIMESTAMP(6))/1000000.0, "
                + "d.next_attempt_at <= UTC_TIMESTAMP(6) "
                + "FROM event_deliveries d JOIN event_registrations r ON r.registration_id=d.registration_id "
                + "WHERE r.consumer_id='" + consumerId + "' AND d.state='" + state + "' "
                + "ORDER BY d.created_at,d.tenant_id,d.event_id,d.registration_id LIMIT " + (LIMIT + 1), rows -> {
                if (++count[0] > LIMIT) return;
                increment(values, "delivery.targets", "delivery_state", state);
                values.merge(new Key("delivery.oldest.created.age.seconds", "delivery_state", state),
                    Math.max(0, rows.getDouble(2)), Math::max);
                if ("PENDING".equals(state) && rows.getLong(1) > 0) {
                    increment(values, rows.getBoolean(3) ? "delivery.retry.due" : "delivery.retry.backoff", "", "");
                }
            });
            put(values, "sample.truncated", "sample", "deliveries_" + state.toLowerCase(java.util.Locale.ROOT),
                count[0] > LIMIT ? 1 : 0);
        }
        return new Snapshot(Map.copyOf(values), Instant.now());
    }

    /** Missing performance_schema grants do not erase the independent event sample. */
    public Snapshot readLocks(Connection connection) throws SQLException {
        var values = new LinkedHashMap<Key, Double>();
        int[] waits = {0};
        query(connection, "SELECT 1 FROM performance_schema.data_lock_waits LIMIT " + (LIMIT + 1), rows -> waits[0]++);
        put(values, "lock.waits", Math.min(waits[0], LIMIT));
        put(values, "sample.truncated", "sample", "locks", waits[0] > LIMIT ? 1 : 0);
        query(connection, "SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status "
                + "WHERE VARIABLE_NAME IN ('Innodb_row_lock_waits','Innodb_row_lock_time')", rows -> {
            String name = switch (rows.getString(1).toLowerCase(java.util.Locale.ROOT)) {
                case "innodb_row_lock_waits" -> "lock.waits.cumulative";
                case "innodb_row_lock_time" -> "lock.wait.milliseconds.cumulative";
                default -> "lock.deadlocks.cumulative";
            };
            put(values, name, rows.getDouble(2));
        });
        query(connection, "SELECT COUNT FROM information_schema.innodb_metrics WHERE NAME='lock_deadlocks' AND STATUS='enabled'",
                rows -> put(values, "lock.deadlocks.cumulative", rows.getDouble(1)));
        return new Snapshot(Map.copyOf(values), Instant.now());
    }

    private void readDeliveries(Connection connection, Map<Key, Double> values) throws SQLException {
        for (String state : STATES) {
            put(values, "delivery.targets", "delivery_state", state, 0);
            put(values, "delivery.oldest.created.age.seconds", "delivery_state", state, 0);
            put(values, "business.outstanding.targets", "delivery_state", state, 0);
        }
        put(values, "delivery.retry.due", 0);
        put(values, "delivery.lifetime.attempts", 0);
        put(values, "delivery.cycle.attempts", 0);
        int[] count = {0};
        for (String sampledState : STATES) {
        count[0] = 0;
        query(connection, "SELECT d.state, d.lifetime_attempts, d.cycle_attempts, "
                + "TIMESTAMPDIFF(MICROSECOND, d.created_at, UTC_TIMESTAMP(6)) / 1000000.0, "
                + "d.next_attempt_at <= UTC_TIMESTAMP(6), p.outcome "
                + "FROM event_deliveries d JOIN event_registrations r ON r.registration_id=d.registration_id "
                + "LEFT JOIN waitlist_promotion_receipts p ON p.tenant_id=d.tenant_id AND p.event_id=d.event_id AND p.consumer_id=r.consumer_id "
                + "WHERE r.consumer_id='waitlist.promotion' AND d.state='" + sampledState + "' "
                + "ORDER BY d.created_at, d.tenant_id, d.event_id, d.registration_id LIMIT " + (LIMIT + 1), rows -> {
            if (++count[0] > LIMIT) return;
            String state = rows.getString(1);
            if (!STATES.contains(state)) return;
            increment(values, "delivery.targets", "delivery_state", state);
            values.merge(new Key("delivery.oldest.created.age.seconds", "delivery_state", state), Math.max(0, rows.getDouble(4)), Math::max);
            if (rows.getString(6) == null) increment(values, "business.outstanding.targets", "delivery_state", state);
            values.merge(new Key("delivery.lifetime.attempts", "", ""), rows.getDouble(2), Double::sum);
            values.merge(new Key("delivery.cycle.attempts", "", ""), rows.getDouble(3), Double::sum);
            if ("PENDING".equals(state) && rows.getBoolean(5) && rows.getLong(2) > 0) {
                values.merge(new Key("delivery.retry.due", "", ""), 1.0, Double::sum);
            }
        });
        put(values, "sample.truncated", "sample", "deliveries_" + sampledState.toLowerCase(java.util.Locale.ROOT), count[0] > LIMIT ? 1 : 0);
        }
    }

    private void query(Connection connection, String sql, Row row) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(1);
            statement.setMaxRows(LIMIT + 1);
            try (ResultSet rows = statement.executeQuery(sql.replaceFirst("SELECT", "SELECT /*+ MAX_EXECUTION_TIME(1000) */"))) {
                while (rows.next()) row.accept(rows);
            }
        }
    }
    private static void increment(Map<Key, Double> values, String name, String dimension, String value) {
        values.merge(new Key(name, dimension, value), 1.0, Double::sum);
    }
    private static void put(Map<Key, Double> values, String name, double value) { put(values, name, "", "", value); }
    private static void put(Map<Key, Double> values, String name, String dimension, String label, double value) {
        values.put(new Key(name, dimension, label), value);
    }
    @FunctionalInterface
    private interface Row { void accept(ResultSet row) throws SQLException; }
}
