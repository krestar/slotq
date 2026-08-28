package com.slotq.tenancy.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.slotq.tenancy.domain.Tenant;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.tenancy.domain.TenantStatus;

@Entity
@Table(name = "tenants")
class TenantJpaEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TenantStatus status;

    protected TenantJpaEntity() {
    }

    private TenantJpaEntity(UUID id, TenantStatus status) {
        this.id = id;
        this.status = status;
    }

    static TenantJpaEntity fromDomain(Tenant tenant) {
        return new TenantJpaEntity(tenant.id().value(), tenant.status());
    }

    Tenant toDomain() {
        return new Tenant(new TenantId(id), status);
    }
}
