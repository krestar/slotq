package com.slotq.venue.persistence;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VenueSpringDataRepository extends JpaRepository<VenueJpaEntity, UUID> {

    Optional<VenueJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select venue from VenueJpaEntity venue where venue.tenantId = :tenantId and venue.id = :venueId")
    Optional<VenueJpaEntity> findForUpdate(
        @Param("tenantId") UUID tenantId,
        @Param("venueId") UUID venueId
    );
}
