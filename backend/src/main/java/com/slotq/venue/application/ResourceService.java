package com.slotq.venue.application;

import java.util.NoSuchElementException;
import java.util.Objects;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.ResourceType;
import com.slotq.venue.domain.VenueId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ResourceService implements ResourceUseCase {

    private final ResourceRepository resourceRepository;
    private final VenueConfigurationUseCase venueUseCase;

    ResourceService(ResourceRepository resourceRepository, VenueConfigurationUseCase venueUseCase) {
        this.resourceRepository = resourceRepository;
        this.venueUseCase = venueUseCase;
    }

    @Override
    public Resource createResource(CreateResource command) {
        Objects.requireNonNull(command, "command must not be null");
        venueUseCase.getVenue(command.tenantId(), command.venueId());
        Resource resource = new Resource(
            ResourceId.newId(),
            command.tenantId(),
            command.venueId(),
            ResourceType.TABLE,
            command.name(),
            command.seatingCapacity(),
            ResourceStatus.ACTIVE
        );
        resourceRepository.save(resource);
        return resource;
    }

    @Override
    @Transactional(readOnly = true)
    public Resource getResource(TenantId tenantId, VenueId venueId, ResourceId resourceId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(venueId, "venueId must not be null");
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        return resourceRepository.find(tenantId, venueId, resourceId)
            .orElseThrow(() -> new NoSuchElementException("Resource not found in tenant and venue scope"));
    }

    @Override
    public Resource updateResource(UpdateResource command) {
        Objects.requireNonNull(command, "command must not be null");
        Resource updated = getResource(command.tenantId(), command.venueId(), command.resourceId())
            .withDetails(command.name(), command.seatingCapacity(), command.status());
        resourceRepository.save(updated);
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public Resource validateReservationEligibility(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        int partySize
    ) {
        Resource resource = getResource(tenantId, venueId, resourceId);
        resource.validateReservationEligibility(partySize);
        return resource;
    }
}
