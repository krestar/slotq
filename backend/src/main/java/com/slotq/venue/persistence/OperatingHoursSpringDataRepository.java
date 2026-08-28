package com.slotq.venue.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OperatingHoursSpringDataRepository extends JpaRepository<OperatingHoursJpaEntity, OperatingHoursId> {

    @Query("select hours from OperatingHoursJpaEntity hours "
        + "where hours.id.tenantId = :tenantId and hours.id.venueId = :venueId")
    List<OperatingHoursJpaEntity> findForVenue(
        @Param("tenantId") UUID tenantId,
        @Param("venueId") UUID venueId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from OperatingHoursJpaEntity hours "
        + "where hours.id.tenantId = :tenantId and hours.id.venueId = :venueId")
    void deleteForVenue(@Param("tenantId") UUID tenantId, @Param("venueId") UUID venueId);
}
