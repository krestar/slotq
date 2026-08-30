package com.slotq.venue.persistence;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.slotq.venue.domain.VenueStatus;

@Entity
@Table(
    name = "venues",
    uniqueConstraints = @UniqueConstraint(name = "uk_venues_tenant_id_id", columnNames = {"tenant_id", "id"})
)
class VenueJpaEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VenueStatus status;

    @Column(name = "timezone", nullable = false, length = 63)
    private String timezone;

    protected VenueJpaEntity() {
    }

    VenueJpaEntity(UUID id, UUID tenantId, String name, VenueStatus status, String timezone) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.status = status;
        this.timezone = timezone;
    }

    UUID id() {
        return id;
    }

    UUID tenantId() {
        return tenantId;
    }

    String name() {
        return name;
    }

    VenueStatus status() {
        return status;
    }

    String timezone() {
        return timezone;
    }

    void update(String newName, VenueStatus newStatus, String newTimezone) {
        this.name = newName;
        this.status = newStatus;
        this.timezone = newTimezone;
    }
}
