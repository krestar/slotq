package com.slotq.integration.operations.recovery;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.slotq.events.application.DeliveryExecutionScope;
import com.slotq.events.application.DeliveryKey;
import com.slotq.events.application.DeliveryTransactions;
import com.slotq.events.application.EventDeliveryStore;
import com.slotq.events.application.EventId;
import com.slotq.events.application.KafkaConsumerCatalog;
import com.slotq.tenancy.domain.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import static com.slotq.integration.operations.recovery.OperatorCredentials.bytes;

/** A narrow human recovery transaction. Never wraps EventReplayService or invokes the broker. */
@Service
public final class HumanRecoveryService {
    private final JdbcTemplate db;
    private final OperatorCredentials credentials;
    private final DeliveryTransactions transactions;
    private final EventDeliveryStore deliveries;
    private final KafkaConsumerCatalog catalog;
    private final JsonMapper json = JsonMapper.builder().build();

    public HumanRecoveryService(JdbcTemplate db, OperatorCredentials credentials, DeliveryTransactions transactions,
                                EventDeliveryStore deliveries, KafkaConsumerCatalog catalog) {
        this.db = db;
        this.credentials = credentials;
        this.transactions = transactions;
        this.deliveries = deliveries;
        this.catalog = catalog;
    }

    public RecoveryResult recover(HumanOperator actor, RecoveryCommand command, String correlationId) {
        command.validate();
        if (correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,100}")) throw RecoveryProblem.invalid();
        String exact = json.writeValueAsString(command);
        var outcome = transactions.attempt(() -> transition(actor, command, exact, correlationId));
        if (outcome.failure() == null) return outcome.value();
        if (outcome.failure() instanceof RecoveryProblem problem) throw problem;
        if (outcome.confirmedRollback()) throw new RecoveryProblem(500, "RECOVERY_FAILED");
        // Unknown commit never opens another cycle. Reconcile operation AND audit using fresh MySQL state.
        try {
            RecoveryResult committed = transactions.execute(() -> {
                authorize(actor, command);
                return completed(actor, command, exact);
            });
            if (committed != null) return committed;
        } catch (RecoveryProblem problem) {
            throw problem;
        } catch (RuntimeException unavailable) {
            // The caller keeps the operation ID and exact request, including after DB unavailability.
        }
        throw new RecoveryProblem(503, "RECOVERY_OUTCOME_UNKNOWN");
    }

    private RecoveryResult transition(HumanOperator actor, RecoveryCommand command, String exact, String correlation) {
        String principal = authorize(actor, command);
        db.update("""
            INSERT INTO operations_recovery_operations
                (operation_id,operator_id,tenant_id,consumer_id,action,request_fingerprint,exact_request)
            VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE operation_id=operation_id
            """, bytes(command.operationId()), bytes(actor.operatorId()), bytes(command.tenantId()),
            command.consumerId(), command.action(), OperatorCredentials.hash(exact), exact);
        RecoveryResult previous = completed(actor, command, exact);
        if (previous != null) return previous;
        // Check original scope only after operation reuse: a historical retry remains historical even after cutover/DEAD.
        RecoveryResult result = command.action().equals("BUSINESS_REPLAY")
            ? business(actor, command, principal, correlation) : publication(actor, command, principal, correlation);
        String encoded = json.writeValueAsString(result);
        one(db.update("""
            UPDATE operations_recovery_operations SET result_json=? WHERE operation_id=? AND result_json IS NULL
            """, encoded, bytes(command.operationId())));
        one(db.update("""
            INSERT INTO operations_recovery_audit
                (operation_id,operator_id,tenant_id,consumer_id,action,recorded_at,result_json)
            VALUES (?,?,?,?,?,?,?)
            """, bytes(command.operationId()), bytes(actor.operatorId()), bytes(command.tenantId()),
            command.consumerId(), command.action(), utc(result.recordedAt()), encoded));
        return result;
    }

    private String authorize(HumanOperator actor, RecoveryCommand command) {
        String principal = credentials.requireCurrent(actor);
        for (String consumer : command.action().equals("PUBLICATION_RECOVER")
            ? command.affectedConsumers() : List.of(command.consumerId())) {
            credentials.requireGrant(actor, command.tenantId(), consumer, command.action());
        }
        return principal;
    }

    private RecoveryResult completed(HumanOperator actor, RecoveryCommand command, String exact) {
        var rows = db.query("""
            SELECT operator_id,exact_request,request_fingerprint,result_json
            FROM operations_recovery_operations WHERE operation_id=? FOR UPDATE
            """, (row, n) -> new Operation(OperatorCredentials.uuid(row.getBytes(1)), row.getString(2),
                row.getBytes(3), row.getString(4)), bytes(command.operationId()));
        if (rows.isEmpty()) return null;
        Operation operation = rows.getFirst();
        if (!actor.operatorId().equals(operation.operator()) || !exact.equals(operation.exact())
            || !java.security.MessageDigest.isEqual(OperatorCredentials.hash(exact), operation.fingerprint()))
            throw new RecoveryProblem(409, "RECOVERY_OPERATION_REUSED");
        if (operation.result() == null) return null;
        var audit = db.queryForList("SELECT result_json FROM operations_recovery_audit WHERE operation_id=? FOR SHARE",
            String.class, bytes(command.operationId()));
        if (audit.size() != 1 || !audit.getFirst().equals(operation.result()))
            throw new RecoveryProblem(503, "RECOVERY_OUTCOME_UNKNOWN");
        return json.readValue(operation.result(), RecoveryResult.class);
    }

    private RecoveryResult business(HumanOperator actor, RecoveryCommand c, String principal, String correlation) {
        var scope = new DeliveryExecutionScope(c.consumerId(), c.expectedTransport(), c.expectedAuthorityEpoch());
        var key = new DeliveryKey(new TenantId(c.tenantId()), new EventId(c.eventId()), c.registrationId());
        var prior = deliveries.lock(scope, key).orElseThrow(RecoveryProblem::hidden);
        if (!prior.state().name().equals(c.expectedState()) || prior.fencingToken() != c.expectedFence())
            throw RecoveryProblem.stale();
        Instant now = deliveries.databaseNow();
        one(db.update("""
            UPDATE event_deliveries SET state='PENDING',cycle_attempts=0,fencing_token=fencing_token+1,
                lease_until=NULL,next_attempt_at=?,failure_code=NULL,failure_detail=NULL,updated_at=?
            WHERE tenant_id=? AND event_id=? AND registration_id=? AND state='DEAD' AND fencing_token=?
            """, utc(now), utc(now), bytes(c.tenantId()), bytes(c.eventId()), bytes(c.registrationId()), c.expectedFence()));
        return result(actor, c, principal, correlation, prior.cycleAttempts(), prior.lifetimeAttempts(), now);
    }

    private RecoveryResult publication(HumanOperator actor, RecoveryCommand c, String principal, String correlation) {
        List<String> affected = affected(c.tenantId(), c.eventId());
        if (!affected.equals(c.affectedConsumers())) throw RecoveryProblem.hidden();
        var rows = db.query("""
            SELECT state,cycle_attempts,lifetime_attempts,fencing_token FROM event_kafka_publications
            WHERE tenant_id=? AND event_id=? AND destination=? FOR UPDATE
            """, (row, n) -> new Publication(row.getString(1), row.getInt(2), row.getLong(3), row.getLong(4)),
            bytes(c.tenantId()), bytes(c.eventId()), c.destination());
        if (rows.size() != 1) throw RecoveryProblem.hidden();
        Publication prior = rows.getFirst();
        if (!prior.state().equals(c.expectedState()) || prior.fence() != c.expectedFence()) throw RecoveryProblem.stale();
        Instant now = deliveries.databaseNow();
        one(db.update("""
            UPDATE event_kafka_publications SET state='PENDING',cycle_attempts=0,fencing_token=fencing_token+1,
                lease_until=NULL,next_attempt_at=?,failure_code=NULL,ack_partition=NULL,ack_offset=NULL,
                ack_at=NULL,updated_at=?
            WHERE tenant_id=? AND event_id=? AND destination=? AND state=? AND fencing_token=?
            """, utc(now), utc(now), bytes(c.tenantId()), bytes(c.eventId()), c.destination(), c.expectedState(), c.expectedFence()));
        return result(actor, c, principal, correlation, prior.attempts(), prior.lifetime(), now);
    }

    /** Original interval membership, including deactivated registrations; never replicas or current-only routes. */
    List<String> affected(UUID tenant, UUID event) {
        var routes = db.query("""
            SELECT r.consumer_id,r.event_type,r.schema_version FROM event_records e
            JOIN event_registrations r ON r.event_type=e.event_type AND r.schema_version=e.schema_version
                AND e.boundary_sequence>r.activation_boundary
                AND (r.deactivation_boundary IS NULL OR e.boundary_sequence<r.deactivation_boundary)
            JOIN event_transport_assignments a ON a.registration_id=r.registration_id
            WHERE e.tenant_id=? AND e.event_id=? ORDER BY r.consumer_id,r.registration_id FOR SHARE OF r,a
            """, (row, n) -> new com.slotq.events.application.ConsumerRoute(row.getString(1),row.getString(2),row.getInt(3)),
            bytes(tenant), bytes(event));
        var approved = catalog.consumers().stream().flatMap(value -> value.routes().stream()).toList();
        List<String> consumers = routes.stream().filter(approved::contains)
            .map(com.slotq.events.application.ConsumerRoute::consumerId).distinct().sorted().toList();
        if (consumers.isEmpty()) throw RecoveryProblem.hidden();
        return consumers;
    }

    private RecoveryResult result(HumanOperator actor, RecoveryCommand c, String principal, String correlation,
                                  int attempts, long lifetime, Instant now) {
        return new RecoveryResult(c.operationId(), actor.operatorId(), principal, c.tenantId(), c.eventId(),
            c.registrationId(), c.consumerId(), c.action(), c.destination(), c.affectedConsumers(), c.publicationCause(), c.reason(),
            correlation, c.expectedState(), c.expectedState(), "PENDING", attempts, 0, lifetime,
            c.expectedFence(), c.expectedFence()+1, c.expectedTransport(), c.expectedAuthorityEpoch(), now);
    }

    public RecoveryResult operation(HumanOperator actor, UUID tenant, String consumer, UUID operation) {
        return transactions.execute(() -> {
            credentials.requireCurrent(actor);
            credentials.requireGrant(actor, tenant, consumer, "READ");
            var values = db.queryForList("""
                SELECT exact_request FROM operations_recovery_operations
                WHERE operation_id=? AND tenant_id=? AND consumer_id=? AND result_json IS NOT NULL
                """, String.class, bytes(operation), bytes(tenant), consumer);
            if (values.size()!=1) throw RecoveryProblem.hidden();
            RecoveryCommand c = json.readValue(values.getFirst(), RecoveryCommand.class);
            if (c.affectedConsumers()!=null) for (String affected : c.affectedConsumers())
                credentials.requireGrant(actor, tenant, affected, "READ");
            return auditResult(operation);
        });
    }

    public List<RecoveryResult> audit(HumanOperator actor, UUID tenant, String consumer, UUID after, int limit) {
        if (limit < 1 || limit > 100) throw RecoveryProblem.invalid();
        return transactions.execute(() -> {
            credentials.requireCurrent(actor);
            credentials.requireGrant(actor, tenant, consumer, "READ");
            return db.query("""
                SELECT result_json FROM operations_recovery_audit a
                WHERE a.tenant_id=? AND a.consumer_id=? AND a.operation_id>?
                  AND NOT EXISTS (
                    SELECT 1 FROM JSON_TABLE(a.result_json,'$.affectedConsumers[*]'
                        COLUMNS (consumer VARCHAR(100) PATH '$')) affected
                    WHERE NOT EXISTS (SELECT 1 FROM operations_grants g
                        WHERE g.operator_id=? AND g.tenant_id=a.tenant_id AND g.consumer_id=affected.consumer
                          AND g.action='READ' AND g.revoked_at IS NULL))
                ORDER BY a.operation_id LIMIT ?
                """, (row,n)->json.readValue(row.getString(1),RecoveryResult.class), bytes(tenant), consumer,
                bytes(after == null ? new UUID(0,0) : after), bytes(actor.operatorId()), limit).stream()
                .filter(value -> value.affectedConsumers()==null || hasReadForAll(actor, tenant, value.affectedConsumers()))
                .toList();
        });
    }

    private boolean hasReadForAll(HumanOperator actor, UUID tenant, List<String> consumers) {
        try { for (String consumer : consumers) credentials.requireGrant(actor,tenant,consumer,"READ"); return true; }
        catch (RecoveryProblem denied) { return false; }
    }
    private RecoveryResult auditResult(UUID operation) {
        var values = db.queryForList("SELECT result_json FROM operations_recovery_audit WHERE operation_id=?",
            String.class, bytes(operation));
        if (values.size()!=1) throw new RecoveryProblem(503,"RECOVERY_OUTCOME_UNKNOWN");
        return json.readValue(values.getFirst(), RecoveryResult.class);
    }
    private static LocalDateTime utc(Instant now) { return LocalDateTime.ofInstant(now, ZoneOffset.UTC); }
    private static void one(int rows) { if (rows!=1) throw RecoveryProblem.stale(); }
    private record Operation(UUID operator, String exact, byte[] fingerprint, String result) { }
    private record Publication(String state, int attempts, long lifetime, long fence) { }
}
