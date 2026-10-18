package com.slotq.venue.persistence;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.application.ResourceRepository;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import org.springframework.stereotype.Component;

@Component
class ResourcePersistenceAdapter implements ResourceRepository {

    private final ResourceSpringDataRepository repository;
    private final EntityManager entityManager;

    ResourcePersistenceAdapter(ResourceSpringDataRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public void save(Resource resource) {
        repository.saveAndFlush(toEntity(resource));
    }

    @Override
    public Optional<Resource> find(TenantId tenantId, VenueId venueId, ResourceId resourceId) {
        return repository.findByTenantIdAndVenueIdAndId(
            tenantId.value(), venueId.value(), resourceId.value()
        ).map(this::toDomain);
    }

    @Override
    public List<Resource> findAll(TenantId tenantId, VenueId venueId) {
        return repository.findAllByTenantIdAndVenueIdOrderByNameAscIdAsc(
            tenantId.value(), venueId.value()
        ).stream().map(this::toDomain).toList();
    }

    @Override
    public Optional<Resource> findForUpdate(TenantId tenantId, VenueId venueId, ResourceId resourceId) {
        // Reload just this target through a locking current read; never merge PATCH fields from its old cache entry.
        repository.findByTenantIdAndVenueIdAndId(tenantId.value(), venueId.value(), resourceId.value())
            .ifPresent(entityManager::detach);
        return repository.findForUpdate(tenantId.value(), venueId.value(), resourceId.value())
            .map(this::toDomain);
    }

    private ResourceJpaEntity toEntity(Resource resource) {
        return new ResourceJpaEntity(
            resource.id().value(),
            resource.tenantId().value(),
            resource.venueId().value(),
            resource.type(),
            resource.name(),
            resource.seatingCapacity(),
            resource.status()
        );
    }

    private Resource toDomain(ResourceJpaEntity entity) {
        return new Resource(
            new ResourceId(entity.id()),
            new TenantId(entity.tenantId()),
            new VenueId(entity.venueId()),
            entity.type(),
            entity.name(),
            entity.seatingCapacity(),
            entity.status()
        );
    }
}
