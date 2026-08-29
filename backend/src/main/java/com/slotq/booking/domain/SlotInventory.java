package com.slotq.booking.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public record SlotInventory(
    SlotInventoryId id,
    TenantId tenantId,
    VenueId venueId,
    ResourceId resourceId,
    Instant startsAt,
    Instant endsAt,
    int capacity,
    long appliedPolicyVersion
) {

    public static final int TABLE_CAPACITY = 1;
    public static final int ALLOCATION_QUANTITY = 1;

    public SlotInventory {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(venueId, "venueId must not be null");
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        Objects.requireNonNull(startsAt, "startsAt must not be null");
        Objects.requireNonNull(endsAt, "endsAt must not be null");
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        if (capacity != TABLE_CAPACITY) {
            throw new IllegalArgumentException("TABLE SlotInventory capacity must be 1");
        }
        if (appliedPolicyVersion <= 0) {
            throw new IllegalArgumentException("appliedPolicyVersion must be positive");
        }
    }

    public static SlotInventory create(
        SlotInventoryId id,
        TenantId tenantId,
        VenueId venueId,
        Resource resource,
        Instant startsAt,
        int slotDurationMinutes,
        long appliedPolicyVersion
    ) {
        if (slotDurationMinutes <= 0) {
            throw new IllegalArgumentException("slotDurationMinutes must be positive");
        }
        Objects.requireNonNull(resource, "resource must not be null");
        if (!resource.tenantId().equals(tenantId) || !resource.venueId().equals(venueId)) {
            throw new IllegalArgumentException("Resource and SlotInventory must share tenant and venue");
        }
        return new SlotInventory(
            id,
            tenantId,
            venueId,
            resource.id(),
            startsAt,
            startsAt.plus(Duration.ofMinutes(slotDurationMinutes)),
            TABLE_CAPACITY,
            appliedPolicyVersion
        );
    }

    public void validateDurationMinutes(int slotDurationMinutes) {
        if (!Duration.between(startsAt, endsAt).equals(Duration.ofMinutes(slotDurationMinutes))) {
            throw new IllegalArgumentException("Slot duration must match the applied policy");
        }
    }

    public void validateAllocationQuantity(int quantity) {
        if (quantity != ALLOCATION_QUANTITY) {
            throw new IllegalArgumentException("TABLE allocation quantity must be 1");
        }
    }
}
