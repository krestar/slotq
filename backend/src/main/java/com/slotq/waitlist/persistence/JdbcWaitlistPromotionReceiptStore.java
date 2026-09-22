package com.slotq.waitlist.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.slotq.booking.domain.ReservationState;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.WaitlistPromotionReceiptStore;
import com.slotq.waitlist.application.WaitlistPromotionUseCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static com.slotq.waitlist.persistence.JdbcWaitlistDemandStore.bytes;
import static com.slotq.waitlist.persistence.JdbcWaitlistDemandStore.uuid;

@Component
class JdbcWaitlistPromotionReceiptStore implements WaitlistPromotionReceiptStore {
    private final JdbcTemplate jdbc;
    JdbcWaitlistPromotionReceiptStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Receipt claim(TenantId tenantId, WaitlistPromotionUseCase.Command command) {
        requireScope(tenantId, command);
        // Insert first avoids two absent-key gap locks followed by conflicting insert intentions.
        // ON DUPLICATE KEY acquires the existing PK lock but never overwrites immutable evidence.
        jdbc.update("""
            INSERT INTO waitlist_promotion_receipts
                (tenant_id, consumer_id, event_id, signal_type, source_id, occurred_at,
                 venue_id, resource_id, slot_inventory_id, from_state, to_state)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE event_id = waitlist_promotion_receipts.event_id
            """, bytes(tenantId.value()), WaitlistPromotionUseCase.CONSUMER, bytes(command.eventId()),
            command.signal().name(), bytes(command.sourceId()), LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC),
            bytes(command.venueId().value()), bytes(command.resourceId().value()), bytes(command.slotInventoryId().value()),
            command.fromState() == null ? null : command.fromState().name(),
            command.toState() == null ? null : command.toState().name());
        return jdbc.queryForObject("""
            SELECT * FROM waitlist_promotion_receipts
            WHERE tenant_id = ? AND consumer_id = ? AND event_id = ? FOR UPDATE
            """, JdbcWaitlistPromotionReceiptStore::receipt,
            bytes(tenantId.value()), WaitlistPromotionUseCase.CONSUMER, bytes(command.eventId()));
    }

    @Override
    public void complete(TenantId tenantId, WaitlistPromotionUseCase.Command command, WaitlistPromotionUseCase.Result result) {
        requireScope(tenantId, command);
        int count = jdbc.update("""
            UPDATE waitlist_promotion_receipts SET outcome = ?, offer_id = ?, entry_id = ?, reservation_id = ?
            WHERE tenant_id = ? AND consumer_id = ? AND event_id = ? AND outcome IS NULL
            """, result.outcome().name(), nullableBytes(result.offerId()), nullableBytes(result.entryId()),
            nullableBytes(result.reservationId()), bytes(tenantId.value()), WaitlistPromotionUseCase.CONSUMER, bytes(command.eventId()));
        if (count != 1) throw new IllegalStateException("Promotion receipt completion must affect one row");
    }

    static Receipt receipt(ResultSet row, int number) throws SQLException {
        var meaning = new WaitlistPromotionUseCase.Command(new TenantId(uuid(row.getBytes("tenant_id"))),
            uuid(row.getBytes("event_id")), WaitlistPromotionUseCase.Signal.valueOf(row.getString("signal_type")),
            uuid(row.getBytes("source_id")), row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            new VenueId(uuid(row.getBytes("venue_id"))), new ResourceId(uuid(row.getBytes("resource_id"))),
            new SlotInventoryId(uuid(row.getBytes("slot_inventory_id"))), state(row.getString("from_state")), state(row.getString("to_state")));
        String outcome = row.getString("outcome");
        return new Receipt(meaning, outcome == null ? null : new WaitlistPromotionUseCase.Result(
            WaitlistPromotionUseCase.Outcome.valueOf(outcome), nullableUuid(row.getBytes("offer_id")),
            nullableUuid(row.getBytes("entry_id")), nullableUuid(row.getBytes("reservation_id"))));
    }
    private static void requireScope(TenantId tenantId, WaitlistPromotionUseCase.Command command) {
        if (!tenantId.equals(command.tenantId())) throw new IllegalArgumentException("Receipt tenant does not match");
    }
    private static ReservationState state(String value) { return value == null ? null : ReservationState.valueOf(value); }
    private static byte[] nullableBytes(UUID value) { return value == null ? null : bytes(value); }
    private static UUID nullableUuid(byte[] value) { return value == null ? null : uuid(value); }
}
