package com.slotq.booking.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReservationSpringDataRepository extends JpaRepository<ReservationJpaEntity, UUID> {

    Optional<ReservationJpaEntity> findByVenueIdAndId(UUID venueId, UUID id);

    List<ReservationJpaEntity> findAllByTenantIdAndVenueIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscIdAsc(
        UUID tenantId,
        UUID venueId,
        Instant startsAt,
        Instant endsAt
    );

    @Query("""
        select count(reservation) from ReservationJpaEntity reservation, CapacityAllocationJpaEntity allocation
        where allocation.reservationId = reservation.id
          and reservation.tenantId = :tenantId
          and reservation.venueId = :venueId
          and reservation.resourceId = :resourceId
          and reservation.slotInventoryId = :slotInventoryId
          and allocation.tenantId = :tenantId
          and allocation.venueId = :venueId
          and allocation.resourceId = :resourceId
          and allocation.slotInventoryId = :slotInventoryId
          and allocation.active = true
          and (reservation.state in (com.slotq.booking.domain.ReservationState.CONFIRMED,
                                     com.slotq.booking.domain.ReservationState.CHECKED_IN)
               or (reservation.state = com.slotq.booking.domain.ReservationState.HELD
                   and reservation.expiresAt > :now))
        """)
    long countEffectiveCapacityConsumers(
        @Param("tenantId") UUID tenantId,
        @Param("venueId") UUID venueId,
        @Param("resourceId") UUID resourceId,
        @Param("slotInventoryId") UUID slotInventoryId,
        @Param("now") Instant now
    );
}
