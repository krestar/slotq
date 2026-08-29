package com.slotq.venue.persistence;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ResourceSpringDataRepository extends JpaRepository<ResourceJpaEntity, UUID> {

    Optional<ResourceJpaEntity> findByTenantIdAndVenueIdAndId(UUID tenantId, UUID venueId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select resource from ResourceJpaEntity resource
        where resource.tenantId = :tenantId
          and resource.venueId = :venueId
          and resource.id = :resourceId
        """)
    Optional<ResourceJpaEntity> findForUpdate(
        @Param("tenantId") UUID tenantId,
        @Param("venueId") UUID venueId,
        @Param("resourceId") UUID resourceId
    );
}
