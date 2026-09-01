package com.slotq.booking.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface CapacityAllocationSpringDataRepository extends JpaRepository<CapacityAllocationJpaEntity, UUID> {

    Optional<CapacityAllocationJpaEntity> findByTenantIdAndVenueIdAndReservationId(
        UUID tenantId,
        UUID venueId,
        UUID reservationId
    );

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select allocation from CapacityAllocationJpaEntity allocation "
        + "where allocation.tenantId = :tenantId and allocation.venueId = :venueId "
        + "and allocation.reservationId = :reservationId")
    Optional<CapacityAllocationJpaEntity> findCurrentByTenantIdAndVenueIdAndReservationId(
        @Param("tenantId") UUID tenantId,
        @Param("venueId") UUID venueId,
        @Param("reservationId") UUID reservationId
    );
}
