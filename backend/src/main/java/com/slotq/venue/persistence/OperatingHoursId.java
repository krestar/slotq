package com.slotq.venue.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
class OperatingHoursId implements Serializable {

    @Column(name = "tenant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID tenantId;

    @Column(name = "venue_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID venueId;

    @Column(name = "day_of_week", nullable = false)
    private byte dayOfWeek;

    protected OperatingHoursId() {
    }

    OperatingHoursId(UUID tenantId, UUID venueId, byte dayOfWeek) {
        this.tenantId = tenantId;
        this.venueId = venueId;
        this.dayOfWeek = dayOfWeek;
    }

    UUID tenantId() {
        return tenantId;
    }

    UUID venueId() {
        return venueId;
    }

    byte dayOfWeek() {
        return dayOfWeek;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OperatingHoursId that)) {
            return false;
        }
        return dayOfWeek == that.dayOfWeek
            && Objects.equals(tenantId, that.tenantId)
            && Objects.equals(venueId, that.venueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, venueId, dayOfWeek);
    }
}
