package com.slotq.booking.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "capacity_allocations")
class CapacityAllocationJpaEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;
    @Column(name = "reservation_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID reservationId;
    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;
    @Column(name = "venue_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID venueId;
    @Column(name = "resource_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID resourceId;
    @Column(name = "slot_inventory_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID slotInventoryId;
    @Column(name = "units", nullable = false)
    private int units;
    @Column(name = "active", nullable = false)
    private boolean active;

    protected CapacityAllocationJpaEntity() { }

    CapacityAllocationJpaEntity(UUID id, UUID reservationId, UUID tenantId, UUID venueId,
                                UUID resourceId, UUID slotInventoryId, int units, boolean active) {
        this.id = id;
        this.reservationId = reservationId;
        this.tenantId = tenantId;
        this.venueId = venueId;
        this.resourceId = resourceId;
        this.slotInventoryId = slotInventoryId;
        this.units = units;
        this.active = active;
    }

    UUID id() { return id; }
    UUID reservationId() { return reservationId; }
    UUID tenantId() { return tenantId; }
    UUID venueId() { return venueId; }
    UUID resourceId() { return resourceId; }
    UUID slotInventoryId() { return slotInventoryId; }
    int units() { return units; }
    boolean active() { return active; }
}
