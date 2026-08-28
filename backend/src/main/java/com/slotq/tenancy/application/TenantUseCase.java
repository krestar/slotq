package com.slotq.tenancy.application;

import com.slotq.tenancy.domain.Tenant;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.tenancy.domain.TenantStatus;

public interface TenantUseCase {

    Tenant createTenant();

    Tenant getTenant(TenantId tenantId);

    Tenant updateTenantStatus(TenantId tenantId, TenantStatus status);
}
