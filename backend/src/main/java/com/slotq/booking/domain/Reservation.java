package com.slotq.booking.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.PolicyDeadlines;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public final class Reservation {

    private final ReservationId id;
    private final TenantId tenantId;
    private final VenueId venueId;
    private final ResourceId resourceId;
    private final SlotInventoryId slotInventoryId;
    private final PrincipalId customerPrincipalId;
    private final PartySize partySize;
    private final long appliedPolicyVersion;
    private final Instant startsAt;
    private final Instant expiresAt;
    private final Instant cancelAllowedUntil;
    private final Instant noShowEligibleAt;
    private final CapacityAllocation allocation;
    private ReservationState state;

    private Reservation(
        ReservationId id,
        CapacityAllocationId allocationId,
        TenantId tenantId,
        VenueId venueId,
        Resource resource,
        SlotInventory slotInventory,
        PrincipalId customerPrincipalId,
        PartySize partySize,
        PolicyDeadlines deadlines,
        Clock clock
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.venueId = Objects.requireNonNull(venueId, "venueId must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(slotInventory, "slotInventory must not be null");
        this.customerPrincipalId = Objects.requireNonNull(
            customerPrincipalId,
            "customerPrincipalId must not be null"
        );
        this.partySize = Objects.requireNonNull(partySize, "partySize must not be null");
        Objects.requireNonNull(deadlines, "deadlines must not be null");

        validateOwnership(resource, slotInventory);
        resource.validateReservationEligibility(partySize.value());
        validatePolicy(slotInventory, deadlines, Objects.requireNonNull(clock, "clock must not be null").instant());

        this.resourceId = resource.id();
        this.slotInventoryId = slotInventory.id();
        this.appliedPolicyVersion = deadlines.appliedPolicyVersion();
        this.startsAt = slotInventory.startsAt();
        this.expiresAt = deadlines.expiresAt();
        this.cancelAllowedUntil = deadlines.cancelAllowedUntil();
        this.noShowEligibleAt = deadlines.noShowEligibleAt();
        this.state = ReservationState.HELD;
        this.allocation = new CapacityAllocation(
            allocationId,
            id,
            tenantId,
            venueId,
            resourceId,
            slotInventoryId
        );
    }

    public static Reservation hold(
        ReservationId id,
        CapacityAllocationId allocationId,
        TenantId tenantId,
        VenueId venueId,
        Resource resource,
        SlotInventory slotInventory,
        PrincipalId customerPrincipalId,
        PartySize partySize,
        PolicyDeadlines deadlines,
        Clock clock
    ) {
        return new Reservation(
            id,
            allocationId,
            tenantId,
            venueId,
            resource,
            slotInventory,
            customerPrincipalId,
            partySize,
            deadlines,
            clock
        );
    }

    public void confirm(Clock clock) {
        if (state == ReservationState.CONFIRMED) {
            return;
        }
        requireState(ReservationState.HELD, ReservationState.CONFIRMED);
        Instant now = instant(clock);
        if (!now.isBefore(expiresAt)) {
            throw new IllegalStateException("Expired HOLD cannot be confirmed");
        }
        state = ReservationState.CONFIRMED;
    }

    public void expire(Clock clock) {
        if (state == ReservationState.EXPIRED) {
            return;
        }
        requireState(ReservationState.HELD, ReservationState.EXPIRED);
        Instant now = instant(clock);
        if (now.isBefore(expiresAt)) {
            throw new IllegalStateException("HOLD cannot expire before expiresAt");
        }
        state = ReservationState.EXPIRED;
        allocation.release();
    }

    public void cancel(Clock clock) {
        if (state == ReservationState.CANCELLED) {
            return;
        }
        if (state != ReservationState.HELD && state != ReservationState.CONFIRMED) {
            throw forbidden(ReservationState.CANCELLED);
        }
        Instant now = instant(clock);
        if (state == ReservationState.CONFIRMED && !now.isBefore(cancelAllowedUntil)) {
            throw new IllegalStateException("CONFIRMED reservation is past its cancellation deadline");
        }
        state = ReservationState.CANCELLED;
        allocation.release();
    }

    public void checkIn(Clock clock) {
        if (state == ReservationState.CHECKED_IN) {
            return;
        }
        requireState(ReservationState.CONFIRMED, ReservationState.CHECKED_IN);
        Instant now = instant(clock);
        if (now.isBefore(startsAt) || !now.isBefore(noShowEligibleAt)) {
            throw new IllegalStateException("Reservation is outside the check-in window");
        }
        state = ReservationState.CHECKED_IN;
    }

    public void markNoShow(Clock clock) {
        if (state == ReservationState.NO_SHOW) {
            return;
        }
        requireState(ReservationState.CONFIRMED, ReservationState.NO_SHOW);
        Instant now = instant(clock);
        if (now.isBefore(noShowEligibleAt)) {
            throw new IllegalStateException("Reservation is not eligible for NO_SHOW");
        }
        state = ReservationState.NO_SHOW;
        allocation.release();
    }

    public void complete() {
        if (state == ReservationState.COMPLETED) {
            return;
        }
        requireState(ReservationState.CHECKED_IN, ReservationState.COMPLETED);
        state = ReservationState.COMPLETED;
        allocation.release();
    }

    public ReservationState effectiveState(Clock clock) {
        Instant now = instant(clock);
        if (state == ReservationState.HELD && !now.isBefore(expiresAt)) {
            return ReservationState.EXPIRED;
        }
        return state;
    }

    public boolean effectiveConsumesCapacity(Clock clock) {
        ReservationState effectiveState = effectiveState(clock);
        return allocation.active()
            && (effectiveState == ReservationState.HELD
                || effectiveState == ReservationState.CONFIRMED
                || effectiveState == ReservationState.CHECKED_IN);
    }

    private void validateOwnership(Resource resource, SlotInventory slotInventory) {
        if (!resource.tenantId().equals(tenantId) || !resource.venueId().equals(venueId)) {
            throw new IllegalArgumentException("Resource and Reservation must share tenant and venue");
        }
        if (!slotInventory.tenantId().equals(tenantId)
            || !slotInventory.venueId().equals(venueId)
            || !slotInventory.resourceId().equals(resource.id())) {
            throw new IllegalArgumentException("SlotInventory and Reservation must share tenant, venue and resource");
        }
    }

    private void validatePolicy(SlotInventory slotInventory, PolicyDeadlines deadlines, Instant now) {
        if (!deadlines.expiresAt().isAfter(now) || deadlines.expiresAt().isAfter(slotInventory.startsAt())) {
            throw new IllegalArgumentException("expiresAt must be after now and no later than startsAt");
        }
        if (deadlines.cancelAllowedUntil().isAfter(slotInventory.startsAt())) {
            throw new IllegalArgumentException("cancelAllowedUntil must be no later than startsAt");
        }
        if (deadlines.noShowEligibleAt().isBefore(slotInventory.startsAt())) {
            throw new IllegalArgumentException("noShowEligibleAt must be no earlier than startsAt");
        }
    }

    private void requireState(ReservationState required, ReservationState target) {
        if (state != required) {
            throw forbidden(target);
        }
    }

    private IllegalStateException forbidden(ReservationState target) {
        return new IllegalStateException("Cannot transition Reservation from " + state + " to " + target);
    }

    private Instant instant(Clock clock) {
        return Objects.requireNonNull(clock, "clock must not be null").instant();
    }

    public ReservationId id() {
        return id;
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

    public PrincipalId customerPrincipalId() {
        return customerPrincipalId;
    }

    public PartySize partySize() {
        return partySize;
    }

    public long appliedPolicyVersion() {
        return appliedPolicyVersion;
    }

    public Instant startsAt() {
        return startsAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant cancelAllowedUntil() {
        return cancelAllowedUntil;
    }

    public Instant noShowEligibleAt() {
        return noShowEligibleAt;
    }

    public ReservationState state() {
        return state;
    }

    public CapacityAllocation allocation() {
        return allocation;
    }
}
