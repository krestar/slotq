package com.slotq.tenancy.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TenantSpringDataRepository extends JpaRepository<TenantJpaEntity, UUID> {
}
