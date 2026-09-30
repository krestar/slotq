package com.slotq.integration.operations.recovery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.slotq.events.application.DeliveryTransactions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import static com.slotq.integration.operations.recovery.OperatorCredentials.bytes;
import static com.slotq.integration.operations.recovery.OperatorCredentials.uuid;

@Service
public final class RecoveryReadService {
    private final JdbcTemplate db;
    private final OperatorCredentials credentials;
    private final DeliveryTransactions transactions;
    private final HumanRecoveryService recovery;
    private static final String DELIVERY = """
        SELECT e.event_id,r.registration_id,r.consumer_id,e.event_type,e.schema_version,a.transport,a.authority_epoch,
            d.state,d.cycle_attempts,d.lifetime_attempts,d.fencing_token,d.failure_code,
            p.destination,p.state AS publication_state,p.fencing_token AS publication_fence,
            i.topic,i.partition_id,i.first_offset,i.intaken_at,
            receipt.outcome,receipt.offer_id,
            obs.registration_id AS observation_registration,obs.slot_inventory_id,
            obs.from_state,obs.to_state,obs.projected_at
        FROM event_records e
        JOIN event_registrations r ON r.event_type=e.event_type AND r.schema_version=e.schema_version
            AND e.boundary_sequence>r.activation_boundary
            AND (r.deactivation_boundary IS NULL OR e.boundary_sequence<r.deactivation_boundary)
        JOIN event_transport_assignments a ON a.registration_id=r.registration_id
        LEFT JOIN event_deliveries d ON d.tenant_id=e.tenant_id AND d.event_id=e.event_id AND d.registration_id=r.registration_id
        LEFT JOIN event_kafka_publications p ON p.tenant_id=e.tenant_id AND p.event_id=e.event_id
            AND p.destination=(SELECT destination FROM event_kafka_discovery WHERE singleton_id=1)
        LEFT JOIN event_kafka_target_intakes i ON i.tenant_id=e.tenant_id AND i.event_id=e.event_id
            AND i.registration_id=r.registration_id
        LEFT JOIN waitlist_promotion_receipts receipt ON receipt.tenant_id=e.tenant_id
            AND receipt.event_id=e.event_id AND receipt.consumer_id=r.consumer_id
        LEFT JOIN event_observation_projections obs ON obs.tenant_id=e.tenant_id AND obs.event_id=e.event_id
            AND obs.registration_id=r.registration_id AND obs.consumer_id=r.consumer_id
        WHERE e.tenant_id=? AND r.consumer_id=?
        """;

    public RecoveryReadService(JdbcTemplate db, OperatorCredentials credentials, DeliveryTransactions transactions,
                              HumanRecoveryService recovery) {
        this.db=db; this.credentials=credentials; this.transactions=transactions; this.recovery=recovery;
    }

    public List<DeliveryView> list(HumanOperator actor, UUID tenant, String consumer,
                                  UUID afterEvent, UUID afterRegistration, int limit) {
        if (limit<1 || limit>100 || (afterEvent==null)!=(afterRegistration==null)) throw RecoveryProblem.invalid();
        return transactions.execute(() -> {
            authorize(actor,tenant,consumer);
            return db.query(DELIVERY + " AND (e.event_id,r.registration_id)>(?,?) ORDER BY e.event_id,r.registration_id LIMIT ?",
                RecoveryReadService::delivery, bytes(tenant), consumer,
                bytes(afterEvent==null ? new UUID(0,0) : afterEvent),
                bytes(afterRegistration==null ? new UUID(0,0) : afterRegistration), limit);
        });
    }

    public DeliveryView exact(HumanOperator actor, UUID tenant, String consumer, UUID event, UUID registration) {
        return transactions.execute(() -> {
            authorize(actor,tenant,consumer);
            var values=db.query(DELIVERY + " AND e.event_id=? AND r.registration_id=?", RecoveryReadService::delivery,
                bytes(tenant),consumer,bytes(event),bytes(registration));
            if (values.size()!=1) throw RecoveryProblem.hidden();
            return values.getFirst();
        });
    }

    public PublicationView publication(HumanOperator actor, UUID tenant, UUID event, String destination) {
        if (!RecoveryCommand.identifier(destination)) throw RecoveryProblem.invalid();
        return transactions.execute(() -> {
            credentials.requireCurrent(actor);
            List<String> affected=recovery.affected(tenant,event);
            for (String consumer:affected) credentials.requireGrant(actor,tenant,consumer,"READ");
            var values=db.query("""
                SELECT state,cycle_attempts,lifetime_attempts,fencing_token,failure_code,ack_partition,ack_offset
                FROM event_kafka_publications WHERE tenant_id=? AND event_id=? AND destination=?
                """, (row,n)->new PublicationView(tenant,event,destination,affected,row.getString(1),row.getInt(2),
                    row.getLong(3),row.getLong(4),row.getString(5),row.getObject(6,Integer.class),row.getObject(7,Long.class)),
                bytes(tenant),bytes(event),destination);
            if (values.size()!=1) throw RecoveryProblem.hidden();
            return values.getFirst();
        });
    }

    private void authorize(HumanOperator actor, UUID tenant, String consumer) {
        if (!RecoveryCommand.identifier(consumer)) throw RecoveryProblem.invalid();
        credentials.requireCurrent(actor);
        credentials.requireGrant(actor,tenant,consumer,"READ");
    }
    private static DeliveryView delivery(ResultSet r, int n) throws SQLException {
        return new DeliveryView(uuid(r.getBytes("event_id")),uuid(r.getBytes("registration_id")),r.getString("consumer_id"),
            r.getString("event_type"),r.getInt("schema_version"),r.getString("transport"),r.getLong("authority_epoch"),
            r.getString("state"),r.getObject("cycle_attempts",Integer.class),r.getObject("lifetime_attempts",Long.class),r.getObject("fencing_token",Long.class),
            r.getString("failure_code"),r.getString("destination"),r.getString("publication_state"),
            r.getObject("publication_fence",Long.class),r.getString("topic"),r.getObject("partition_id",Integer.class),
            r.getObject("first_offset",Long.class),instant(r,"intaken_at"),r.getString("outcome"),uuid(r.getBytes("offer_id")),
            uuid(r.getBytes("observation_registration")),uuid(r.getBytes("slot_inventory_id")),r.getString("from_state"),
            r.getString("to_state"),instant(r,"projected_at"));
    }
    private static Instant instant(ResultSet r, String name) throws SQLException {
        LocalDateTime value=r.getObject(name,LocalDateTime.class);
        return value==null ? null : value.toInstant(ZoneOffset.UTC);
    }
    public record DeliveryView(UUID eventId, UUID registrationId, String consumerId, String eventType, int schemaVersion,
        String transport, long authorityEpoch, String state, Integer cycleAttempts, Long lifetimeAttempts, Long fencingToken,
        String failureCode, String destination, String publicationState, Long publicationFence, String intakeTopic,
        Integer intakePartition, Long intakeOffset, Instant intakenAt, String receiptOutcome, UUID offerReference,
        UUID observationRegistration, UUID slotInventoryReference, String fromState, String toState, Instant projectedAt) { }
    public record PublicationView(UUID tenantId,UUID eventId,String destination,List<String> affectedConsumers,
        String state,int cycleAttempts,long lifetimeAttempts,long fencingToken,String failureCode,Integer ackPartition,Long ackOffset) { }
}
