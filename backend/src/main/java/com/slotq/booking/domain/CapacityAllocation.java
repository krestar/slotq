package com.slotq.booking.domain;

import java.util.Objects;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public final class CapacityAllocation {

    public static final int UNIT = 1;

    private final CapacityAllocationId id;
    private final ReservationId reservationId;
    private final TenantId tenantId;
    private final VenueId venueId;
    private final ResourceId resourceId;
    private final SlotInventoryId slotInventoryId;
    private final int units;
    private boolean active;

    CapacityAllocation(
        CapacityAllocationId id,
        ReservationId reservationId,
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId
    ) {
        this(id, reservationId, tenantId, venueId, resourceId, slotInventoryId, UNIT, true);
    }

    private CapacityAllocation(
        CapacityAllocationId id,
        ReservationId reservationId,
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId,
        int units,
        boolean active
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.venueId = Objects.requireNonNull(venueId, "venueId must not be null");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId must not be null");
        this.slotInventoryId = Objects.requireNonNull(slotInventoryId, "slotInventoryId must not be null");
        if (units != UNIT) {
            throw new IllegalArgumentException("CapacityAllocation units must be 1");
        }
        this.units = units;
        this.active = active;
    }

    public static CapacityAllocation reconstitute(
        CapacityAllocationId id,
        ReservationId reservationId,
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId,
        int units,
        boolean active
    ) {
        return new CapacityAllocation(
            id,
            reservationId,
            tenantId,
            venueId,
            resourceId,
            slotInventoryId,
            units,
            active
        );
    }

    void release() {
        active = false;
    }

    public CapacityAllocationId id() {
        return id;
    }

    public ReservationId reservationId() {
        return reservationId;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public VenueId venueId() {
        return venueId;
    }

    public ResourceId resourceId() {
        return resourceId;
    }

    public SlotInventoryId slotInventoryId() {
        return slotInventoryId;
    }

    public int units() {
        return units;
    }

    public boolean active() {
        return active;
    }
}
