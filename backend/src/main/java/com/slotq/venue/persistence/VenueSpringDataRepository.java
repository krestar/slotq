package com.slotq.venue.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VenueSpringDataRepository extends JpaRepository<VenueJpaEntity, UUID> {

    Optional<VenueJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    @Query("""
        select venue.id as id, venue.tenantId as tenantId,
               venue.name as name, venue.timezone as timezone
        from VenueJpaEntity venue, TenantJpaEntity tenant
        where tenant.id = venue.tenantId
          and tenant.status = com.slotq.tenancy.domain.TenantStatus.ACTIVE
          and venue.status = com.slotq.venue.domain.VenueStatus.ACTIVE
        order by venue.name asc, venue.id asc
        """)
    List<PublicVenueRow> findAllActivePublicVenues();

    @Query("""
        select venue.id as id, venue.tenantId as tenantId,
               venue.name as name, venue.timezone as timezone
        from VenueJpaEntity venue, TenantJpaEntity tenant
        where tenant.id = venue.tenantId
          and tenant.status = com.slotq.tenancy.domain.TenantStatus.ACTIVE
          and venue.status = com.slotq.venue.domain.VenueStatus.ACTIVE
          and venue.id = :venueId
        """)
    Optional<PublicVenueRow> findActivePublicVenueById(@Param("venueId") UUID venueId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select venue from VenueJpaEntity venue where venue.tenantId = :tenantId and venue.id = :venueId")
    Optional<VenueJpaEntity> findForUpdate(
        @Param("tenantId") UUID tenantId,
        @Param("venueId") UUID venueId
    );

    interface PublicVenueRow {
        UUID getId();
        UUID getTenantId();
        String getName();
        String getTimezone();
    }
}
