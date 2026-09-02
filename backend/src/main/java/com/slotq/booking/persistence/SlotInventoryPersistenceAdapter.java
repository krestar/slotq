package com.slotq.booking.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.slotq.booking.application.SlotInventoryRepository;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import org.springframework.stereotype.Component;

@Component
class SlotInventoryPersistenceAdapter implements SlotInventoryRepository {

    private final SlotInventorySpringDataRepository repository;

    SlotInventoryPersistenceAdapter(SlotInventorySpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(SlotInventory slotInventory) {
        repository.saveAndFlush(new SlotInventoryJpaEntity(
            slotInventory.id().value(),
            slotInventory.tenantId().value(),
            slotInventory.venueId().value(),
            slotInventory.resourceId().value(),
            slotInventory.startsAt(),
            slotInventory.endsAt(),
            slotInventory.capacity(),
            slotInventory.appliedPolicyVersion()
        ));
    }

    @Override
    public Optional<SlotInventory> find(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId
    ) {
        return repository.findByTenantIdAndVenueIdAndResourceIdAndId(
            tenantId.value(), venueId.value(), resourceId.value(), slotInventoryId.value()
        ).map(this::toDomain);
    }

    @Override
    public Optional<SlotInventory> find(VenueId venueId, SlotInventoryId slotInventoryId) {
        return repository.findByVenueIdAndId(venueId.value(), slotInventoryId.value())
            .map(this::toDomain);
    }

    @Override
    public Optional<SlotInventory> findForUpdate(
        VenueId venueId,
        SlotInventoryId slotInventoryId
    ) {
        return repository.findForUpdateByVenueIdAndId(venueId.value(), slotInventoryId.value())
            .map(this::toDomain);
    }

    @Override
    public List<SlotInventory> findAll(
        TenantId tenantId,
        VenueId venueId,
        Instant startsAt,
        Instant endsAt
    ) {
        return repository
            .findAllByTenantIdAndVenueIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscIdAsc(
                tenantId.value(), venueId.value(), startsAt, endsAt
            ).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean overlaps(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        Instant startsAt,
        Instant endsAt
    ) {
        return !repository.findOverlappingForUpdate(
            tenantId.value(), venueId.value(), resourceId.value(), startsAt, endsAt
        ).isEmpty();
    }

    private SlotInventory toDomain(SlotInventoryJpaEntity entity) {
        return new SlotInventory(
            new SlotInventoryId(entity.id()),
            new TenantId(entity.tenantId()),
            new VenueId(entity.venueId()),
            new ResourceId(entity.resourceId()),
            entity.startsAt(),
            entity.endsAt(),
            entity.capacity(),
            entity.appliedPolicyVersion()
        );
    }
}
