package com.slotq.waitlist.persistence;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import com.slotq.booking.application.PromotionAvailabilityQuery;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.waitlist.application.WaitlistPromotionReceiptStore;
import com.slotq.waitlist.application.WaitlistPromotionRequestStore;
import com.slotq.waitlist.application.WaitlistPromotionUseCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static com.slotq.waitlist.persistence.JdbcWaitlistDemandStore.bytes;
import static com.slotq.waitlist.persistence.JdbcWaitlistDemandStore.uuid;

@Component
class JdbcWaitlistPromotionRequestStore implements WaitlistPromotionRequestStore {
    private final JdbcTemplate jdbc;
    JdbcWaitlistPromotionRequestStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public UUID lockLatest(TenantId tenantId, SlotInventoryId slotId) {
        jdbc.update("""
            INSERT INTO waitlist_promotion_requests (tenant_id, slot_inventory_id) VALUES (?, ?)
            ON DUPLICATE KEY UPDATE slot_inventory_id = waitlist_promotion_requests.slot_inventory_id
            """, bytes(tenantId.value()), bytes(slotId.value()));
        return jdbc.queryForObject("""
            SELECT last_event_id FROM waitlist_promotion_requests
            WHERE tenant_id = ? AND slot_inventory_id = ? FOR UPDATE
            """, (row, n) -> row.getBytes(1) == null ? null : uuid(row.getBytes(1)), bytes(tenantId.value()), bytes(slotId.value()));
    }
    @Override public Optional<WaitlistPromotionReceiptStore.Receipt> receiptCurrent(TenantId tenantId, UUID eventId) {
        // Never lock delivery or infer completion from its state. This waits for effect commit/rollback.
        return jdbc.query("""
            SELECT * FROM waitlist_promotion_receipts
            WHERE tenant_id = ? AND consumer_id = ? AND event_id = ? FOR SHARE
            """, JdbcWaitlistPromotionReceiptStore::receipt,
            bytes(tenantId.value()), WaitlistPromotionUseCase.CONSUMER, bytes(eventId)).stream().findFirst();
    }
    @Override public boolean hasWaiting(PromotionAvailabilityQuery.Target target) {
        return !jdbc.queryForList("""
            SELECT demand.id FROM waitlist_demands demand
            WHERE demand.tenant_id = ? AND demand.venue_id = ? AND demand.starts_at = ? AND demand.ends_at = ?
              AND demand.party_size <= ? AND EXISTS (
                  SELECT 1 FROM waitlist_entries entry WHERE entry.demand_id = demand.id
                    AND entry.tenant_id = demand.tenant_id AND entry.venue_id = demand.venue_id AND entry.state = 'WAITING'
              ) LIMIT 1
            """, bytes(target.tenantId().value()), bytes(target.venueId().value()),
            Timestamp.from(target.startsAt()), Timestamp.from(target.endsAt()), target.seatingCapacity()).isEmpty();
    }
    @Override public void connect(TenantId tenantId, SlotInventoryId slotId, UUID eventId) {
        if (jdbc.update("""
            UPDATE waitlist_promotion_requests SET last_event_id = ? WHERE tenant_id = ? AND slot_inventory_id = ?
            """, bytes(eventId), bytes(tenantId.value()), bytes(slotId.value())) != 1) {
            throw new IllegalStateException("Request admission must affect one row");
        }
    }
}
