package com.slotq.tenancy.application;

import java.util.NoSuchElementException;
import java.util.Objects;

import com.slotq.tenancy.domain.Tenant;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.tenancy.domain.TenantStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class TenantService implements TenantUseCase {

    private final TenantRepository tenantRepository;

    TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    public Tenant createTenant() {
        return tenantRepository.save(new Tenant(TenantId.newId(), TenantStatus.ACTIVE));
    }

    @Override
    @Transactional(readOnly = true)
    public Tenant getTenant(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return tenantRepository.findById(tenantId)
            .orElseThrow(() -> new NoSuchElementException("Tenant not found: " + tenantId.value()));
    }

    @Override
    public Tenant updateTenantStatus(TenantId tenantId, TenantStatus status) {
        Tenant tenant = getTenant(tenantId);
        return tenantRepository.save(tenant.withStatus(Objects.requireNonNull(status, "status must not be null")));
    }
}
