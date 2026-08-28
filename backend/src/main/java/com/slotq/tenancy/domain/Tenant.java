package com.slotq.tenancy.domain;

import java.util.Objects;

public record Tenant(TenantId id, TenantStatus status) {

    public Tenant {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    public Tenant withStatus(TenantStatus newStatus) {
        return new Tenant(id, newStatus);
    }
}
