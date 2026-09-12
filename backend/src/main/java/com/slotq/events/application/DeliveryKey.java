package com.slotq.events.application;

import java.util.Objects;
import java.util.UUID;

import com.slotq.tenancy.domain.TenantId;

public record DeliveryKey(TenantId tenantId, EventId eventId, UUID registrationId) {
    public DeliveryKey {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(registrationId, "registrationId");
    }
}
