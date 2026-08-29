package com.slotq.booking.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "slot_inventories",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_slot_inventories_tenant_venue_resource_id",
            columnNames = {"tenant_id", "venue_id", "resource_id", "id"}
        ),
        @UniqueConstraint(
            name = "uk_slot_inventories_resource_starts_at",
            columnNames = {"tenant_id", "resource_id", "starts_at"}
        )
    }
)
class SlotInventoryJpaEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "venue_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID venueId;

    @Column(name = "resource_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID resourceId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    @Column(name = "applied_policy_version", nullable = false)
    private long appliedPolicyVersion;

    protected SlotInventoryJpaEntity() {
    }

    SlotInventoryJpaEntity(
        UUID id,
        UUID tenantId,
        UUID venueId,
        UUID resourceId,
        Instant startsAt,
        Instant endsAt,
        int capacity,
        long appliedPolicyVersion
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.venueId = venueId;
        this.resourceId = resourceId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.capacity = capacity;
        this.appliedPolicyVersion = appliedPolicyVersion;
    }

    UUID id() {
        return id;
    }

    UUID tenantId() {
        return tenantId;
    }

    UUID venueId() {
        return venueId;
    }

    UUID resourceId() {
        return resourceId;
    }

    Instant startsAt() {
        return startsAt;
    }

    Instant endsAt() {
        return endsAt;
    }

    int capacity() {
        return capacity;
    }

    long appliedPolicyVersion() {
        return appliedPolicyVersion;
    }
}
