package com.slotq.waitlist.domain;

import java.time.Instant;
import java.util.Objects;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;

public final class WaitlistEntry {

    private final WaitlistEntryId id;
    private final TenantId tenantId;
    private final VenueId venueId;
    private final PrincipalId customerPrincipalId;
    private final WaitlistDemand demand;
    private final Instant joinedAt;
    private WaitlistEntryState state;

    public WaitlistEntry(
        WaitlistEntryId id,
        TenantId tenantId,
        VenueId venueId,
        PrincipalId customerPrincipalId,
        WaitlistDemand demand,
        Instant joinedAt,
        WaitlistEntryState state
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.venueId = Objects.requireNonNull(venueId, "venueId must not be null");
        this.customerPrincipalId = Objects.requireNonNull(
            customerPrincipalId, "customerPrincipalId must not be null"
        );
        this.demand = Objects.requireNonNull(demand, "demand must not be null");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        if (!tenantId.equals(demand.tenantId()) || !venueId.equals(demand.venueId())) {
            throw new IllegalArgumentException("Entry and Demand must share tenant and venue");
        }
    }

    public static WaitlistEntry join(
        WaitlistEntryId id,
        PrincipalId customerPrincipalId,
        WaitlistDemand demand,
        Instant joinedAt
    ) {
        return new WaitlistEntry(
            id, demand.tenantId(), demand.venueId(), customerPrincipalId,
            demand, joinedAt, WaitlistEntryState.WAITING
        );
    }

    public WaitlistEntryState effectiveState(Instant observedAt) {
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        if (state == WaitlistEntryState.WAITING && !observedAt.isBefore(demand.startsAt())) {
            return WaitlistEntryState.EXPIRED;
        }
        return state;
    }

    public void cancel(Instant commandNow) {
        WaitlistEntryState effective = effectiveState(commandNow);
        if (effective == WaitlistEntryState.CANCELLED) {
            return;
        }
        if (effective != WaitlistEntryState.WAITING) {
            throw new IllegalStateException("Waitlist entry cannot be cancelled from " + effective);
        }
        state = WaitlistEntryState.CANCELLED;
    }

    public boolean expireWaiting(Instant commandNow) {
        Objects.requireNonNull(commandNow, "commandNow must not be null");
        if (state != WaitlistEntryState.WAITING || commandNow.isBefore(demand.startsAt())) return false;
        state = WaitlistEntryState.EXPIRED;
        return true;
    }

    public void offer() {
        requireState(WaitlistEntryState.WAITING, WaitlistEntryState.OFFERED);
        state = WaitlistEntryState.OFFERED;
    }

    public void fulfill() {
        if (state == WaitlistEntryState.FULFILLED) {
            return;
        }
        requireState(WaitlistEntryState.OFFERED, WaitlistEntryState.FULFILLED);
        state = WaitlistEntryState.FULFILLED;
    }

    public void decline() {
        if (state == WaitlistEntryState.DECLINED) {
            return;
        }
        requireState(WaitlistEntryState.OFFERED, WaitlistEntryState.DECLINED);
        state = WaitlistEntryState.DECLINED;
    }

    public void expireOffer() {
        if (state == WaitlistEntryState.EXPIRED) {
            return;
        }
        requireState(WaitlistEntryState.OFFERED, WaitlistEntryState.EXPIRED);
        state = WaitlistEntryState.EXPIRED;
    }

    private void requireState(WaitlistEntryState required, WaitlistEntryState target) {
        if (state != required) {
            throw new IllegalStateException("Waitlist entry cannot transition from " + state + " to " + target);
        }
    }

    public WaitlistEntryId id() { return id; }
    public TenantId tenantId() { return tenantId; }
    public VenueId venueId() { return venueId; }
    public PrincipalId customerPrincipalId() { return customerPrincipalId; }
    public WaitlistDemand demand() { return demand; }
    public Instant joinedAt() { return joinedAt; }
    public WaitlistEntryState state() { return state; }
}
