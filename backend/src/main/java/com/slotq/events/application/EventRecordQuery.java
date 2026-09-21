package com.slotq.events.application;

import java.util.Optional;
import com.slotq.tenancy.domain.TenantId;

/** Exact tenant-scoped immutable evidence read. No locking, delivery inspection or replay. */
public interface EventRecordQuery {
    Optional<StoredEvent> find(TenantId tenantId, EventId eventId);
}
