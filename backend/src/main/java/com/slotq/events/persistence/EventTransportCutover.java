package com.slotq.events.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.KafkaConsumerCatalog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Quiesced maintenance-window transition, followed by a resumable bounded target scan. */
@Service
public final class EventTransportCutover {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final KafkaConsumerCatalog catalog;

    public EventTransportCutover(JdbcTemplate db, PlatformTransactionManager manager,
                                 KafkaConsumerCatalog catalog) {
        this.db = db;
        this.transaction = new TransactionTemplate(manager);
        this.catalog = catalog;
    }

    public CutoverState prepare(String requestedTransport) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cutover must start outside a caller transaction");
        }
        if (!requestedTransport.equals("DB_DIRECT") && !requestedTransport.equals("KAFKA")) {
            throw new IllegalArgumentException("Unsupported transport");
        }
        return transaction.execute(status -> {
            // Append and registration changes take this fence first. No business row is locked here.
            long fence = db.queryForObject(
                "SELECT sequence_value FROM event_boundary WHERE singleton_id = 1 FOR UPDATE", Long.class);
            List<CutoverState> previous = db.query("""
                SELECT from_transport,to_transport,authority_epoch,fence_boundary,scan_boundary,phase
                  FROM event_transport_cutover WHERE singleton_id = 1 FOR UPDATE
                """, (row, n) -> new CutoverState(row.getString("from_transport"),
                    row.getString("to_transport"), row.getLong("authority_epoch"),
                    row.getLong("fence_boundary"), row.getLong("scan_boundary"), row.getString("phase")));
            if (!previous.isEmpty() && previous.getFirst().phase().equals("SCANNING")) {
                if (!previous.getFirst().toTransport().equals(requestedTransport)) {
                    throw new IllegalStateException("Finish the existing cutover scan first");
                }
                return previous.getFirst();
            }
            String from = previous.isEmpty() ? "DB_DIRECT" : previous.getFirst().toTransport();
            long epoch = previous.isEmpty() ? 1 : previous.getFirst().authorityEpoch();
            if (from.equals(requestedTransport)) {
                if (previous.isEmpty()) throw new IllegalStateException("Already in DB direct mode");
                return previous.getFirst();
            }
            var definitions = catalog.consumers();
            if (definitions.size() != 2 || definitions.stream().map(KafkaConsumerCatalog.ConsumerDefinition::consumerId)
                .distinct().count() != 2) {
                throw new IllegalStateException("Cutover requires the two approved logical consumers");
            }
            var registrations = db.query("""
                SELECT r.consumer_id,r.event_type,r.schema_version,r.deactivation_boundary,
                       a.transport,a.authority_epoch
                  FROM event_registrations r
                  JOIN event_transport_assignments a ON a.registration_id = r.registration_id
                 WHERE r.consumer_id IN (?,?) FOR UPDATE
                """, (row, n) -> new Registration(row.getString("consumer_id"),
                    row.getString("event_type"), row.getInt("schema_version"),
                    row.getObject("deactivation_boundary") == null,
                    row.getString("transport"), row.getLong("authority_epoch")),
                definitions.get(0).consumerId(), definitions.get(1).consumerId());
            Set<ConsumerRoute> active = new HashSet<>();
            for (Registration registration : registrations) {
                ConsumerRoute route = new ConsumerRoute(registration.consumerId(), registration.eventType(),
                    registration.version());
                if (!definitions.stream().anyMatch(definition -> definition.routes().contains(route))
                    || !registration.transport().equals(from) || registration.epoch() != epoch) {
                    throw new IllegalStateException("Registration authority or route mismatch");
                }
                if (registration.active() && !active.add(route)) {
                    throw new IllegalStateException("Duplicate active route");
                }
            }
            Set<ConsumerRoute> expected = new HashSet<>();
            definitions.forEach(definition -> expected.addAll(definition.routes()));
            if (!active.equals(expected)) throw new IllegalStateException("Missing active consumer route");
            int changed = db.update("""
                UPDATE event_transport_assignments a
                  JOIN event_registrations r ON r.registration_id = a.registration_id
                   SET a.transport = ?, a.authority_epoch = a.authority_epoch + 1,
                       a.assigned_at = UTC_TIMESTAMP(6)
                 WHERE r.consumer_id IN (?,?) AND a.transport = ? AND a.authority_epoch = ?
                """, requestedTransport, definitions.get(0).consumerId(), definitions.get(1).consumerId(),
                from, epoch);
            if (changed != registrations.size()) throw new IllegalStateException("Incomplete authority transition");
            long nextEpoch = Math.incrementExact(epoch);
            if (previous.isEmpty()) {
                db.update("""
                    INSERT INTO event_transport_cutover
                    (singleton_id,from_transport,to_transport,authority_epoch,fence_boundary,scan_boundary,phase)
                    VALUES (1,?,?,?,?,0,'SCANNING')
                    """, from, requestedTransport, nextEpoch, fence);
            } else {
                db.update("""
                    UPDATE event_transport_cutover SET from_transport=?,to_transport=?,authority_epoch=?,
                        fence_boundary=?,scan_boundary=0,phase='SCANNING',
                        started_at=UTC_TIMESTAMP(6),completed_at=NULL WHERE singleton_id=1
                    """, from, requestedTransport, nextEpoch, fence);
            }
            return new CutoverState(from, requestedTransport, nextEpoch, fence, 0, "SCANNING");
        });
    }

    /** One transaction scans at most batchSize committed original events. */
    public ScanResult scan(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Invalid cutover batch size");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cutover scan must start outside a caller transaction");
        }
        return transaction.execute(status -> {
            CutoverState state = db.query("""
                SELECT from_transport,to_transport,authority_epoch,fence_boundary,scan_boundary,phase
                  FROM event_transport_cutover WHERE singleton_id = 1 FOR UPDATE
                """, (row, n) -> new CutoverState(row.getString("from_transport"),
                    row.getString("to_transport"), row.getLong("authority_epoch"),
                    row.getLong("fence_boundary"), row.getLong("scan_boundary"), row.getString("phase")))
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("No cutover to scan"));
            if (state.phase().equals("READY")) return new ScanResult(state, 0, 0);
            var definitions = catalog.consumers();
            List<Long> positions = db.queryForList("""
                SELECT boundary_sequence FROM event_records
                 WHERE boundary_sequence > ? AND boundary_sequence <= ?
                 ORDER BY boundary_sequence LIMIT ?
                """, Long.class, state.scanBoundary(), state.fenceBoundary(), batchSize);
            if (positions.isEmpty()) {
                long missing = db.queryForObject("""
                    SELECT COUNT(*) FROM event_records e JOIN event_registrations r
                      ON r.event_type=e.event_type AND r.schema_version=e.schema_version
                     AND e.boundary_sequence > r.activation_boundary
                     AND (r.deactivation_boundary IS NULL OR e.boundary_sequence < r.deactivation_boundary)
                     WHERE e.boundary_sequence <= ? AND r.consumer_id IN (?,?)
                       AND NOT EXISTS (SELECT 1 FROM event_deliveries d
                                       WHERE d.registration_id=r.registration_id AND d.event_id=e.event_id)
                    """, Long.class, state.fenceBoundary(),
                    definitions.get(0).consumerId(), definitions.get(1).consumerId());
                if (missing != 0) throw new IllegalStateException("Cutover target scan is incomplete");
                db.update("""
                    UPDATE event_transport_cutover SET phase='READY',completed_at=UTC_TIMESTAMP(6)
                     WHERE singleton_id=1 AND phase='SCANNING'
                    """);
                return new ScanResult(new CutoverState(state.fromTransport(), state.toTransport(),
                    state.authorityEpoch(), state.fenceBoundary(), state.scanBoundary(), "READY"), 0, 0);
            }
            long through = positions.getLast();
            int inserted = db.update("""
                INSERT INTO event_deliveries (tenant_id,event_id,registration_id,state,next_attempt_at)
                SELECT e.tenant_id,e.event_id,r.registration_id,'PENDING',UTC_TIMESTAMP(6)
                  FROM event_records e JOIN event_registrations r
                    ON r.event_type=e.event_type AND r.schema_version=e.schema_version
                   AND e.boundary_sequence > r.activation_boundary
                   AND (r.deactivation_boundary IS NULL OR e.boundary_sequence < r.deactivation_boundary)
                  JOIN event_transport_assignments a ON a.registration_id=r.registration_id
                   AND a.transport=? AND a.authority_epoch=?
                 WHERE r.consumer_id IN (?,?) AND e.boundary_sequence > ? AND e.boundary_sequence <= ?
                   AND NOT EXISTS (SELECT 1 FROM event_deliveries d
                                   WHERE d.registration_id=r.registration_id AND d.event_id=e.event_id)
                """, state.toTransport(), state.authorityEpoch(), definitions.get(0).consumerId(),
                definitions.get(1).consumerId(), state.scanBoundary(), through);
            db.update("UPDATE event_transport_cutover SET scan_boundary=? WHERE singleton_id=1", through);
            return new ScanResult(new CutoverState(state.fromTransport(), state.toTransport(),
                state.authorityEpoch(), state.fenceBoundary(), through, "SCANNING"), positions.size(), inserted);
        });
    }

    public CutoverState complete(String requestedTransport, int batchSize) {
        CutoverState state = prepare(requestedTransport);
        while (!state.phase().equals("READY")) state = scan(batchSize).state();
        return state;
    }

    public Inventory inventory() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Inventory must start outside a caller transaction");
        }
        return transaction.execute(status -> {
            CutoverState state = db.query("""
                SELECT from_transport,to_transport,authority_epoch,fence_boundary,scan_boundary,phase
                  FROM event_transport_cutover WHERE singleton_id=1 FOR UPDATE
                """, (row, n) -> new CutoverState(row.getString("from_transport"),
                    row.getString("to_transport"), row.getLong("authority_epoch"),
                    row.getLong("fence_boundary"), row.getLong("scan_boundary"), row.getString("phase")))
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("No cutover inventory"));
            var definitions = catalog.consumers();
            long missing = db.queryForObject("""
                SELECT COUNT(*) FROM event_records e JOIN event_registrations r
                  ON r.event_type=e.event_type AND r.schema_version=e.schema_version
                 AND e.boundary_sequence > r.activation_boundary
                 AND (r.deactivation_boundary IS NULL OR e.boundary_sequence < r.deactivation_boundary)
                 WHERE e.boundary_sequence <= ? AND r.consumer_id IN (?,?)
                   AND NOT EXISTS (SELECT 1 FROM event_deliveries d
                                   WHERE d.registration_id=r.registration_id AND d.event_id=e.event_id)
                """, Long.class, state.fenceBoundary(),
                definitions.get(0).consumerId(), definitions.get(1).consumerId());
            var counts = db.queryForList("""
                SELECT d.state,COUNT(*) AS amount FROM event_deliveries d
                  JOIN event_registrations r ON r.registration_id=d.registration_id
                 WHERE r.consumer_id IN (?,?) GROUP BY d.state
                """, definitions.get(0).consumerId(), definitions.get(1).consumerId());
            return new Inventory(state, missing, counts.stream().collect(java.util.stream.Collectors.toMap(
                row -> (String) row.get("state"), row -> ((Number) row.get("amount")).longValue())));
        });
    }

    public record CutoverState(String fromTransport, String toTransport, long authorityEpoch,
                               long fenceBoundary, long scanBoundary, String phase) { }
    public record ScanResult(CutoverState state, int scannedEvents, int insertedTargets) { }
    public record Inventory(CutoverState state, long missingTargets,
                            java.util.Map<String, Long> deliveryStates) { }
    private record Registration(String consumerId, String eventType, int version, boolean active,
                                String transport, long epoch) { }
}
