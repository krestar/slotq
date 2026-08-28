package com.slotq.tenancy.persistence;

import java.util.Optional;

import com.slotq.tenancy.application.TenantRepository;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.tenancy.domain.TenantId;
import org.springframework.stereotype.Component;

@Component
class TenantPersistenceAdapter implements TenantRepository {

    private final TenantSpringDataRepository repository;

    TenantPersistenceAdapter(TenantSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public Tenant save(Tenant tenant) {
        return repository.save(TenantJpaEntity.fromDomain(tenant)).toDomain();
    }

    @Override
    public Optional<Tenant> findById(TenantId tenantId) {
        return repository.findById(tenantId.value()).map(TenantJpaEntity::toDomain);
    }
}
