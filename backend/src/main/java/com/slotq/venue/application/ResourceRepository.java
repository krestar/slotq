package com.slotq.venue.application;

import java.util.List;
import java.util.Optional;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public interface ResourceRepository {

    void save(Resource resource);

    Optional<Resource> find(TenantId tenantId, VenueId venueId, ResourceId resourceId);

    List<Resource> findAll(TenantId tenantId, VenueId venueId);

    Optional<Resource> findForUpdate(TenantId tenantId, VenueId venueId, ResourceId resourceId);
}
