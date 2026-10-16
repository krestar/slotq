package com.slotq.venue.persistence;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface BookingPolicySpringDataRepository extends JpaRepository<BookingPolicyJpaEntity, BookingPolicyId> {

    Optional<BookingPolicyJpaEntity> findFirstByIdTenantIdAndIdVenueIdOrderByIdPolicyVersionDesc(
        UUID tenantId,
        UUID venueId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BookingPolicyJpaEntity> findFirstForUpdateByIdTenantIdAndIdVenueIdOrderByIdPolicyVersionDesc(
        UUID tenantId,
        UUID venueId
    );
}
