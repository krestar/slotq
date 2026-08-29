package com.slotq.venue.domain;

import java.util.Objects;

import com.slotq.tenancy.domain.TenantId;

public record Resource(
    ResourceId id,
    TenantId tenantId,
    VenueId venueId,
    ResourceType type,
    String name,
    int seatingCapacity,
    ResourceStatus status
) {

    public Resource {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(venueId, "venueId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(status, "status must not be null");
        name = Objects.requireNonNull(name, "name must not be null").strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (name.length() > 100) {
            throw new IllegalArgumentException("name must not exceed 100 characters");
        }
        if (seatingCapacity < 1) {
            throw new IllegalArgumentException("seatingCapacity must be positive");
        }
    }

    public Resource withDetails(String newName, int newSeatingCapacity, ResourceStatus newStatus) {
        return new Resource(id, tenantId, venueId, type, newName, newSeatingCapacity, newStatus);
    }

    public void validateReservationEligibility(int partySize) {
        if (partySize < 1) {
            throw new IllegalArgumentException("partySize must be positive");
        }
        if (status != ResourceStatus.ACTIVE) {
            throw new IllegalStateException("Resource must be ACTIVE");
        }
        if (partySize > seatingCapacity) {
            throw new IllegalArgumentException("partySize exceeds seatingCapacity");
        }
    }
}
