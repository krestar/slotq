package com.slotq.venue.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.ResourceType;

@Entity
@Table(
    name = "resources",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_resources_tenant_venue_id",
        columnNames = {"tenant_id", "venue_id", "id"}
    )
)
class ResourceJpaEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "venue_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID venueId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private ResourceType type;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "seating_capacity", nullable = false)
    private int seatingCapacity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ResourceStatus status;

    protected ResourceJpaEntity() {
    }

    ResourceJpaEntity(
        UUID id,
        UUID tenantId,
        UUID venueId,
        ResourceType type,
        String name,
        int seatingCapacity,
        ResourceStatus status
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.venueId = venueId;
        this.type = type;
        this.name = name;
        this.seatingCapacity = seatingCapacity;
        this.status = status;
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

    ResourceType type() {
        return type;
    }

    String name() {
        return name;
    }

    int seatingCapacity() {
        return seatingCapacity;
    }

    ResourceStatus status() {
        return status;
    }
}
