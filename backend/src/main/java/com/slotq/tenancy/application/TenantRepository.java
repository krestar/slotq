package com.slotq.tenancy.application;

import java.util.Optional;

import com.slotq.tenancy.domain.Tenant;
import com.slotq.tenancy.domain.TenantId;

public interface TenantRepository {

    Tenant save(Tenant tenant);

    Optional<Tenant> findById(TenantId tenantId);
}
