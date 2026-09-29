package com.slotq.integration.operations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.slotq.auth.application.AccessDeniedException;
import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.TenantRole;
import com.slotq.venue.domain.VenueId;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Bounded, scoped operational activity read; the delivery ledger remains authoritative. */
@Service
public class OperationsObservationReadService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final Duration MAX_WINDOW = Duration.ofDays(31);
    private final AuthorizationUseCase authorization;
    private final JdbcTemplate jdbc;

    OperationsObservationReadService(AuthorizationUseCase authorization, JdbcTemplate jdbc) {
        this.authorization = authorization;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page list(VenueId venueId, Instant from, Instant to, Integer requestedLimit,
                     AuthenticatedPrincipal principal) {
        if (from == null || to == null || !from.isBefore(to)
            || Duration.between(from, to).compareTo(MAX_WINDOW) > 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Observation time window is invalid");
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Observation limit is invalid");
        var actor = authorization.requireVenueAccess(principal, venueId);
        if (actor.role() != TenantRole.OWNER && actor.role() != TenantRole.MANAGER)
            throw new AccessDeniedException();
        List<Item> fetched = jdbc.query("""
            SELECT p.*, w.registration_id AS waitlist_registration_id,
                   w.state AS waitlist_delivery_state,
                   receipt.outcome AS waitlist_receipt_outcome,
                   receipt.offer_id AS waitlist_offer_id
            FROM event_observation_projections p
            LEFT JOIN event_deliveries w ON w.tenant_id = p.tenant_id AND w.event_id = p.event_id
                AND EXISTS (SELECT 1 FROM event_registrations wr
                    WHERE wr.registration_id = w.registration_id
                      AND wr.consumer_id = 'waitlist.promotion'
                      AND wr.event_type = p.event_type AND wr.schema_version = p.schema_version)
            LEFT JOIN waitlist_promotion_receipts receipt ON receipt.tenant_id = p.tenant_id
                AND receipt.event_id = p.event_id AND receipt.consumer_id = 'waitlist.promotion'
            WHERE p.tenant_id = ? AND p.venue_id = ?
              AND p.projected_at >= ? AND p.projected_at < ?
            ORDER BY p.projected_at DESC, p.event_id DESC
            LIMIT ?
            """, OperationsObservationReadService::item,
            OperationsEventObservationAdapter.bytes(actor.tenantId().value()),
            OperationsEventObservationAdapter.bytes(venueId.value()),
            OperationsEventObservationAdapter.utc(from), OperationsEventObservationAdapter.utc(to), limit + 1);
        return new Page(fetched.stream().limit(limit).toList(), fetched.size() > limit);
    }

    private static Item item(ResultSet row, int number) throws SQLException {
        return new Item(
            OperationsEventObservationAdapter.uuid(row.getBytes("event_id")),
            OperationsEventObservationAdapter.uuid(row.getBytes("registration_id")),
            row.getString("event_type"), row.getInt("schema_version"),
            row.getString("aggregate_type"), OperationsEventObservationAdapter.uuid(row.getBytes("aggregate_id")),
            OperationsEventObservationAdapter.instant(row, "occurred_at"),
            OperationsEventObservationAdapter.uuid(row.getBytes("slot_inventory_id")),
            row.getString("from_state"), row.getString("to_state"),
            nullableInstant(row, "intaken_at"), OperationsEventObservationAdapter.instant(row, "projected_at"),
            nullableUuid(row.getBytes("waitlist_registration_id")), row.getString("waitlist_delivery_state"),
            row.getString("waitlist_receipt_outcome"), nullableUuid(row.getBytes("waitlist_offer_id")));
    }

    private static UUID nullableUuid(byte[] value) {
        return value == null ? null : OperationsEventObservationAdapter.uuid(value);
    }
    private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public record Page(List<Item> items, boolean truncated) { }
    public record Item(UUID eventId, UUID observerRegistrationId, String eventType, int schemaVersion,
                       String aggregateType, UUID aggregateId, Instant occurredAt, UUID slotInventoryId,
                       String fromState, String toState, Instant durableIntakeObservedAt, Instant projectedAt,
                       UUID waitlistRegistrationId, String waitlistDeliveryState,
                       String waitlistReceiptOutcome, UUID waitlistOfferId) { }
}
