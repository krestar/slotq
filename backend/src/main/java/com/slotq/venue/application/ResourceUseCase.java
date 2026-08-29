package com.slotq.venue.application;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.VenueId;

public interface ResourceUseCase {

    Resource createResource(CreateResource command);

    Resource getResource(TenantId tenantId, VenueId venueId, ResourceId resourceId);

    Resource updateResource(UpdateResource command);

    Resource validateReservationEligibility(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        int partySize
    );

    record CreateResource(TenantId tenantId, VenueId venueId, String name, int seatingCapacity) {
    }

    record UpdateResource(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        String name,
        int seatingCapacity,
        ResourceStatus status
    ) {
    }
}
