package com.slotq.booking.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SlotInventorySpringDataRepository extends JpaRepository<SlotInventoryJpaEntity, UUID> {

    Optional<SlotInventoryJpaEntity> findByVenueIdAndId(UUID venueId, UUID id);

    Optional<SlotInventoryJpaEntity> findByTenantIdAndVenueIdAndResourceIdAndId(
        UUID tenantId,
        UUID venueId,
        UUID resourceId,
        UUID id
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select slot from SlotInventoryJpaEntity slot
        where slot.tenantId = :tenantId
          and slot.venueId = :venueId
          and slot.resourceId = :resourceId
          and slot.startsAt < :endsAt
          and slot.endsAt > :startsAt
        """)
    List<SlotInventoryJpaEntity> findOverlappingForUpdate(
        @Param("tenantId") UUID tenantId,
        @Param("venueId") UUID venueId,
        @Param("resourceId") UUID resourceId,
        @Param("startsAt") Instant startsAt,
        @Param("endsAt") Instant endsAt
    );
}
