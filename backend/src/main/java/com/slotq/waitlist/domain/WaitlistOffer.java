package com.slotq.waitlist.domain;

import java.time.Instant;
import java.util.Objects;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

public final class WaitlistOffer {
    private final WaitlistOfferId id;
    private final TenantId tenantId;
    private final VenueId venueId;
    private final ResourceId resourceId;
    private final SlotInventoryId slotInventoryId;
    private final PrincipalId customerPrincipalId;
    private final WaitlistDemandId demandId;
    private final WaitlistEntryId entryId;
    private final ReservationId reservationId;
    private final Instant expiresAt;
    private WaitlistOfferState state;
    private WaitlistOfferTerminalReason terminalReason;

    public WaitlistOffer(
        WaitlistOfferId id, TenantId tenantId, VenueId venueId, ResourceId resourceId,
        SlotInventoryId slotInventoryId, PrincipalId customerPrincipalId,
        WaitlistDemandId demandId, WaitlistEntryId entryId, ReservationId reservationId,
        Instant expiresAt, WaitlistOfferState state, WaitlistOfferTerminalReason terminalReason
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.venueId = Objects.requireNonNull(venueId, "venueId must not be null");
        this.resourceId = Objects.requireNonNull(resourceId, "resourceId must not be null");
        this.slotInventoryId = Objects.requireNonNull(slotInventoryId, "slotInventoryId must not be null");
        this.customerPrincipalId = Objects.requireNonNull(customerPrincipalId,
            "customerPrincipalId must not be null");
        this.demandId = Objects.requireNonNull(demandId, "demandId must not be null");
        this.entryId = Objects.requireNonNull(entryId, "entryId must not be null");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.terminalReason = terminalReason;
        validateReason();
    }

    public static WaitlistOffer pending(
        WaitlistOfferId id, WaitlistEntry entry, ResourceId resourceId,
        SlotInventoryId slotInventoryId, ReservationId reservationId, Instant expiresAt
    ) {
        return new WaitlistOffer(
            id, entry.tenantId(), entry.venueId(), resourceId, slotInventoryId,
            entry.customerPrincipalId(), entry.demand().id(), entry.id(), reservationId,
            expiresAt, WaitlistOfferState.PENDING, null
        );
    }

    public void accept() {
        if (state == WaitlistOfferState.ACCEPTED) return;
        requirePending(WaitlistOfferState.ACCEPTED);
        state = WaitlistOfferState.ACCEPTED;
        terminalReason = null;
    }

    public void decline(WaitlistOfferTerminalReason reason) {
        if (state == WaitlistOfferState.DECLINED) return;
        if (reason != WaitlistOfferTerminalReason.CUSTOMER_DECLINED
            && reason != WaitlistOfferTerminalReason.ENTRY_CANCELLED
            && reason != WaitlistOfferTerminalReason.BACKING_CANCELLED) {
            throw new IllegalArgumentException("Invalid decline reason");
        }
        requirePending(WaitlistOfferState.DECLINED);
        state = WaitlistOfferState.DECLINED;
        terminalReason = reason;
    }

    public void expire() {
        if (state == WaitlistOfferState.EXPIRED) return;
        requirePending(WaitlistOfferState.EXPIRED);
        state = WaitlistOfferState.EXPIRED;
        terminalReason = WaitlistOfferTerminalReason.HOLD_EXPIRED;
    }

    private void requirePending(WaitlistOfferState target) {
        if (state != WaitlistOfferState.PENDING) {
            throw new IllegalStateException("Offer cannot transition from " + state + " to " + target);
        }
    }

    private void validateReason() {
        if ((state == WaitlistOfferState.PENDING || state == WaitlistOfferState.ACCEPTED)
            && terminalReason != null) {
            throw new IllegalArgumentException("Non-declined Offer cannot have a terminal reason");
        }
        if (state == WaitlistOfferState.DECLINED
            && (terminalReason == null || terminalReason == WaitlistOfferTerminalReason.HOLD_EXPIRED)) {
            throw new IllegalArgumentException("DECLINED Offer requires a decline reason");
        }
        if (state == WaitlistOfferState.EXPIRED
            && terminalReason != WaitlistOfferTerminalReason.HOLD_EXPIRED) {
            throw new IllegalArgumentException("EXPIRED Offer requires HOLD_EXPIRED");
        }
    }

    public WaitlistOfferId id() { return id; }
    public TenantId tenantId() { return tenantId; }
    public VenueId venueId() { return venueId; }
    public ResourceId resourceId() { return resourceId; }
    public SlotInventoryId slotInventoryId() { return slotInventoryId; }
    public PrincipalId customerPrincipalId() { return customerPrincipalId; }
    public WaitlistDemandId demandId() { return demandId; }
    public WaitlistEntryId entryId() { return entryId; }
    public ReservationId reservationId() { return reservationId; }
    public Instant expiresAt() { return expiresAt; }
    public WaitlistOfferState state() { return state; }
    public WaitlistOfferTerminalReason terminalReason() { return terminalReason; }
}
