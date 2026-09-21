package com.slotq.waitlist.application;

import com.slotq.tenancy.domain.TenantId;

/** Narrow consumer receipt, not an Inbox or a request execution queue. */
public interface WaitlistPromotionReceiptStore {
    Receipt claim(TenantId tenantId, WaitlistPromotionUseCase.Command command);
    void complete(TenantId tenantId, WaitlistPromotionUseCase.Command command, WaitlistPromotionUseCase.Result result);
    record Receipt(WaitlistPromotionUseCase.Command meaning, WaitlistPromotionUseCase.Result result) { }
}
