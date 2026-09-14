package com.slotq.waitlist.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.PromotionalReservationUseCase;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistOfferId;
import com.slotq.waitlist.domain.WaitlistOfferState;
import com.slotq.waitlist.domain.WaitlistOfferTerminalReason;

public interface WaitlistOfferUseCase {
    OfferView getOffer(VenueId venueId, WaitlistOfferId offerId, AuthenticatedPrincipal principal);
    OfferView getManagementOffer(VenueId venueId, WaitlistOfferId offerId,
                                 AuthenticatedPrincipal principal);
    CommandResult accept(VenueId venueId, WaitlistOfferId offerId, AuthenticatedPrincipal principal);
    CommandResult reject(VenueId venueId, WaitlistOfferId offerId, AuthenticatedPrincipal principal);
    TargetResult createTarget(SystemPrincipal principal, VenueId venueId,
                              WaitlistEntryId entryId, SlotInventoryId slotInventoryId);
    TargetResult reconcileTarget(SystemPrincipal principal, VenueId venueId, WaitlistOfferId offerId);

    enum CommandOutcome { SUCCESS, OFFER_EXPIRED, TRANSITION_NOT_ALLOWED, CAPACITY_UNAVAILABLE }
    enum TargetOutcome {
        CREATED, EXISTING, NOT_WAITING, NOT_ELIGIBLE, CAPACITY_UNAVAILABLE, NOT_DUE, EXPIRED, TERMINAL
    }

    record OfferView(
        UUID id, UUID entryId, UUID venueId, UUID resourceId, UUID slotInventoryId,
        WaitlistOfferState state, WaitlistOfferTerminalReason terminalReason,
        Instant expiresAt, Instant observedAt, ZoneId venueTimezone,
        List<String> allowedActions,
        PromotionalReservationUseCase.ReservationView reservation
    ) {
        public OfferView { allowedActions = List.copyOf(allowedActions); }
    }

    record CommandResult(CommandOutcome outcome, OfferView offer) { }
    record TargetResult(TargetOutcome outcome, OfferView offer) { }
}
