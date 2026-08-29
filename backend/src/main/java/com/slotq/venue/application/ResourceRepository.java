package com.slotq.venue.application;

import java.util.Optional;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public interface ResourceRepository {

    void save(Resource resource);

    Optional<Resource> find(TenantId tenantId, VenueId venueId, ResourceId resourceId);

    Optional<Resource> findForUpdate(TenantId tenantId, VenueId venueId, ResourceId resourceId);
}
