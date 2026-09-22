package com.slotq.waitlist.application;

import java.util.Optional;
import java.util.UUID;
import com.slotq.booking.application.PromotionAvailabilityQuery;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;

/** Only Waitlist-owned admission and completion evidence. */
public interface WaitlistPromotionRequestStore {
    UUID lockLatest(TenantId tenantId, SlotInventoryId slotId);
    Optional<WaitlistPromotionReceiptStore.Receipt> receiptCurrent(TenantId tenantId, UUID eventId);
    boolean hasWaiting(PromotionAvailabilityQuery.Target target);
    void connect(TenantId tenantId, SlotInventoryId slotId, UUID eventId);
}
