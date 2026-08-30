package com.slotq.booking.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CapacityAllocationSpringDataRepository extends JpaRepository<CapacityAllocationJpaEntity, UUID> {

    Optional<CapacityAllocationJpaEntity> findByTenantIdAndVenueIdAndReservationId(
        UUID tenantId,
        UUID venueId,
        UUID reservationId
    );
}
