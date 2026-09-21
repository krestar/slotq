package com.slotq.waitlist.application;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.waitlist.domain.WaitlistOffer;

/** DB-only request acceptance; recording this does not mean customer delivery. */
public interface WaitlistNotificationStore {
    void offerAvailable(TenantId tenantId, WaitlistOffer offer);
}
