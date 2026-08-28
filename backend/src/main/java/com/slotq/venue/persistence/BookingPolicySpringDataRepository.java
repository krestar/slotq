package com.slotq.venue.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
interface BookingPolicySpringDataRepository extends JpaRepository<BookingPolicyJpaEntity, BookingPolicyId> {

    Optional<BookingPolicyJpaEntity> findFirstByIdTenantIdAndIdVenueIdOrderByIdPolicyVersionDesc(
        UUID tenantId,
        UUID venueId
    );
}
