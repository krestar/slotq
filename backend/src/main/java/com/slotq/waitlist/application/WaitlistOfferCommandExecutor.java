package com.slotq.waitlist.application;

import java.time.Instant;

import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.PromotionalReservationUseCase;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistEntry;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistEntryState;
import com.slotq.waitlist.domain.WaitlistOffer;
import com.slotq.waitlist.domain.WaitlistOfferId;
import com.slotq.waitlist.domain.WaitlistOfferState;
import com.slotq.waitlist.domain.WaitlistOfferTerminalReason;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class WaitlistOfferCommandExecutor {
    private final WaitlistEntryRepository entries;
    private final WaitlistOfferRepository offers;
    private final PromotionalReservationUseCase booking;

    WaitlistOfferCommandExecutor(
        WaitlistEntryRepository entries,
        WaitlistOfferRepository offers,
        PromotionalReservationUseCase booking
    ) {
        this.entries = entries;
        this.offers = offers;
        this.booking = booking;
    }

    @Transactional
    TargetExecution create(
        SystemPrincipal principal, WaitlistEntry routed, SlotInventoryId slotId, Instant commandNow
    ) {
        Holder holder = new Holder();
        PromotionalReservationUseCase.CreateResult bookingResult = booking.createHold(
            new PromotionalReservationUseCase.CreateCommand(
                principal, routed.id().value(), routed.tenantId(), routed.venueId(), slotId,
                routed.customerPrincipalId(), routed.demand().startsAt(), routed.demand().endsAt(),
                routed.demand().partySize(), commandNow
            ), lockedSlot -> {
                WaitlistEntry current = entries.findForUpdate(routed.venueId(), routed.id())
                    .orElseThrow(ResourceNotFoundException::new);
                holder.entry = current;
                WaitlistOffer existing = offers.findByEntryForUpdate(current.venueId(), current.id())
                    .orElse(null);
                holder.offer = existing;
                if (existing != null) return PromotionalReservationUseCase.GateDecision.EXISTING;
                if (current.state() != WaitlistEntryState.WAITING
                    || !commandNow.isBefore(current.demand().startsAt())) {
                    return PromotionalReservationUseCase.GateDecision.NOT_WAITING;
                }
                if (!lockedSlot.tenantId().equals(current.tenantId())
                    || !lockedSlot.venueId().equals(current.venueId())
                    || !lockedSlot.startsAt().equals(current.demand().startsAt())
                    || !lockedSlot.endsAt().equals(current.demand().endsAt())) {
                    return PromotionalReservationUseCase.GateDecision.NOT_ELIGIBLE;
                }
                return PromotionalReservationUseCase.GateDecision.PROCEED;
            }
        );

        if (holder.offer != null) {
            return new TargetExecution(
                WaitlistOfferUseCase.TargetOutcome.EXISTING, holder.offer,
                booking.get(holder.offer.venueId(), holder.offer.reservationId(), commandNow)
            );
        }
        WaitlistOfferUseCase.TargetOutcome refusal = switch (bookingResult.outcome()) {
            case NOT_WAITING -> WaitlistOfferUseCase.TargetOutcome.NOT_WAITING;
            case NOT_ELIGIBLE, IDENTITY_CONFLICT -> WaitlistOfferUseCase.TargetOutcome.NOT_ELIGIBLE;
            case CAPACITY_UNAVAILABLE -> WaitlistOfferUseCase.TargetOutcome.CAPACITY_UNAVAILABLE;
            case SKIPPED_EXISTING -> throw new IllegalStateException("Existing Offer was not loaded");
            case EXISTING -> throw new IllegalStateException("Promotional Reservation exists without Offer");
            case CREATED -> null;
        };
        if (refusal != null) return new TargetExecution(refusal, null, null);

        PromotionalReservationUseCase.ReservationView reservation = bookingResult.reservation();
        WaitlistOffer offer = WaitlistOffer.pending(
            WaitlistOfferId.newId(), holder.entry, reservation.resourceId(),
            reservation.slotInventoryId(), reservation.id(), reservation.expiresAt()
        );
        holder.entry.offer();
        offers.create(offer.venueId(), offer);
        entries.updateState(holder.entry.venueId(), holder.entry);
        return new TargetExecution(WaitlistOfferUseCase.TargetOutcome.CREATED, offer, reservation);
    }

    @Transactional
    ActionExecution accept(WaitlistOffer routed, Instant commandNow) {
        Holder holder = new Holder();
        PromotionalReservationUseCase.AcceptResult result = booking.accept(
            new PromotionalReservationUseCase.AcceptCommand(
                routed.entryId().value(), routed.venueId(), routed.slotInventoryId(),
                routed.reservationId(), commandNow
            ), () -> {
                holder.entry = entries.findForUpdate(routed.venueId(), routed.entryId())
                    .orElseThrow(() -> new IllegalStateException("Offer Entry is missing"));
                holder.offer = offers.findOwnedForUpdate(
                    routed.venueId(), routed.customerPrincipalId(), routed.id()
                ).orElseThrow(ResourceNotFoundException::new);
                validatePair(holder.entry, holder.offer);
                return holder.entry.state() == WaitlistEntryState.OFFERED
                    && holder.offer.state() == WaitlistOfferState.PENDING;
            }
        );
        if (result.outcome() == PromotionalReservationUseCase.AcceptOutcome.TERMINAL) {
            PromotionalReservationUseCase.ReservationView reservation = booking.get(
                routed.venueId(), routed.reservationId(), commandNow
            );
            return new ActionExecution(outcomeForTerminal(holder.offer), holder.offer, reservation);
        }
        return applyAccept(holder.entry, holder.offer, result);
    }

    @Transactional
    ActionExecution reject(WaitlistOffer routed, Instant commandNow,
                           WaitlistOfferTerminalReason reason) {
        WaitlistEntry entry = entries.findForUpdate(routed.venueId(), routed.entryId())
            .orElseThrow(() -> new IllegalStateException("Offer Entry is missing"));
        WaitlistOffer offer = offers.findOwnedForUpdate(
            routed.venueId(), routed.customerPrincipalId(), routed.id()
        ).orElseThrow(ResourceNotFoundException::new);
        validatePair(entry, offer);
        if (offer.state() != WaitlistOfferState.PENDING) {
            WaitlistOfferUseCase.CommandOutcome terminalOutcome = switch (offer.state()) {
                case DECLINED -> WaitlistOfferUseCase.CommandOutcome.SUCCESS;
                case ACCEPTED -> WaitlistOfferUseCase.CommandOutcome.TRANSITION_NOT_ALLOWED;
                case EXPIRED -> WaitlistOfferUseCase.CommandOutcome.OFFER_EXPIRED;
                case PENDING -> throw new IllegalStateException("unreachable");
            };
            return new ActionExecution(
                terminalOutcome, offer, booking.get(offer.venueId(), offer.reservationId(), commandNow)
            );
        }
        PromotionalReservationUseCase.ReleaseResult result = booking.release(
            new PromotionalReservationUseCase.ReleaseCommand(
                offer.entryId().value(), offer.venueId(), offer.reservationId(), commandNow, false
            )
        );
        return applyRelease(entry, offer, result, reason);
    }

    @Transactional
    TargetExecution reconcile(WaitlistOffer routed, Instant commandNow) {
        WaitlistEntry entry = entries.findForUpdate(routed.venueId(), routed.entryId())
            .orElseThrow(() -> new IllegalStateException("Offer Entry is missing"));
        WaitlistOffer offer = offers.findOwnedForUpdate(
            routed.venueId(), routed.customerPrincipalId(), routed.id()
        ).orElseThrow(ResourceNotFoundException::new);
        validatePair(entry, offer);
        return reconcileLocked(entry, offer, commandNow);
    }

    TargetExecution reconcileLocked(WaitlistEntry entry, Instant commandNow) {
        WaitlistOffer offer = offers.findByEntryForUpdate(entry.venueId(), entry.id())
            .orElseThrow(() -> new IllegalStateException("OFFERED Entry has no Offer"));
        validatePair(entry, offer);
        return reconcileLocked(entry, offer, commandNow);
    }

    private TargetExecution reconcileLocked(WaitlistEntry entry, WaitlistOffer offer, Instant now) {
        if (offer.state() != WaitlistOfferState.PENDING) {
            return new TargetExecution(
                WaitlistOfferUseCase.TargetOutcome.TERMINAL, offer,
                booking.get(offer.venueId(), offer.reservationId(), now)
            );
        }
        PromotionalReservationUseCase.ReleaseResult result = booking.release(
            new PromotionalReservationUseCase.ReleaseCommand(
                offer.entryId().value(), offer.venueId(), offer.reservationId(), now, true
            )
        );
        ActionExecution applied = applyRelease(
            entry, offer, result, WaitlistOfferTerminalReason.BACKING_CANCELLED
        );
        WaitlistOfferUseCase.TargetOutcome outcome = switch (applied.outcome()) {
            case SUCCESS, TRANSITION_NOT_ALLOWED -> WaitlistOfferUseCase.TargetOutcome.TERMINAL;
            case OFFER_EXPIRED -> WaitlistOfferUseCase.TargetOutcome.EXPIRED;
            case CAPACITY_UNAVAILABLE -> throw new IllegalStateException("release cannot lose capacity");
        };
        if (result.outcome() == PromotionalReservationUseCase.ReleaseOutcome.NOT_DUE) {
            outcome = WaitlistOfferUseCase.TargetOutcome.NOT_DUE;
        }
        return new TargetExecution(outcome, applied.offer(), applied.reservation());
    }

    private ActionExecution applyAccept(
        WaitlistEntry entry, WaitlistOffer offer, PromotionalReservationUseCase.AcceptResult result
    ) {
        WaitlistOfferUseCase.CommandOutcome outcome;
        switch (result.outcome()) {
            case ACCEPTED, ALREADY_ACCEPTED -> {
                offer.accept();
                entry.fulfill();
                outcome = WaitlistOfferUseCase.CommandOutcome.SUCCESS;
            }
            case EXPIRED -> {
                offer.expire();
                entry.expireOffer();
                outcome = WaitlistOfferUseCase.CommandOutcome.OFFER_EXPIRED;
            }
            case BACKING_CANCELLED -> {
                offer.decline(WaitlistOfferTerminalReason.BACKING_CANCELLED);
                entry.decline();
                outcome = WaitlistOfferUseCase.CommandOutcome.TRANSITION_NOT_ALLOWED;
            }
            case CAPACITY_UNAVAILABLE -> {
                return new ActionExecution(
                    WaitlistOfferUseCase.CommandOutcome.CAPACITY_UNAVAILABLE, offer, result.reservation()
                );
            }
            case TERMINAL -> throw new IllegalStateException("terminal result handled before apply");
            default -> throw new IllegalStateException("Unhandled accept outcome");
        }
        persist(entry, offer);
        return new ActionExecution(outcome, offer, result.reservation());
    }

    private ActionExecution applyRelease(
        WaitlistEntry entry, WaitlistOffer offer,
        PromotionalReservationUseCase.ReleaseResult result, WaitlistOfferTerminalReason reason
    ) {
        WaitlistOfferUseCase.CommandOutcome outcome;
        switch (result.outcome()) {
            case RELEASED -> {
                offer.decline(reason);
                entry.decline();
                outcome = WaitlistOfferUseCase.CommandOutcome.SUCCESS;
            }
            case ALREADY_CANCELLED -> {
                offer.decline(WaitlistOfferTerminalReason.BACKING_CANCELLED);
                entry.decline();
                outcome = WaitlistOfferUseCase.CommandOutcome.SUCCESS;
            }
            case EXPIRED -> {
                offer.expire();
                entry.expireOffer();
                outcome = WaitlistOfferUseCase.CommandOutcome.OFFER_EXPIRED;
            }
            case ALREADY_ACCEPTED -> {
                offer.accept();
                entry.fulfill();
                outcome = WaitlistOfferUseCase.CommandOutcome.TRANSITION_NOT_ALLOWED;
            }
            case NOT_DUE -> {
                return new ActionExecution(WaitlistOfferUseCase.CommandOutcome.SUCCESS, offer, result.reservation());
            }
            case TERMINAL -> throw new IllegalStateException("Invalid promotional backing state");
            default -> throw new IllegalStateException("Unhandled release outcome");
        }
        persist(entry, offer);
        return new ActionExecution(outcome, offer, result.reservation());
    }

    private void persist(WaitlistEntry entry, WaitlistOffer offer) {
        offers.update(offer.venueId(), offer);
        entries.updateState(entry.venueId(), entry);
    }

    private WaitlistOfferUseCase.CommandOutcome outcomeForTerminal(WaitlistOffer offer) {
        return switch (offer.state()) {
            case ACCEPTED, DECLINED -> offer.state() == WaitlistOfferState.ACCEPTED
                ? WaitlistOfferUseCase.CommandOutcome.SUCCESS
                : WaitlistOfferUseCase.CommandOutcome.TRANSITION_NOT_ALLOWED;
            case EXPIRED -> WaitlistOfferUseCase.CommandOutcome.OFFER_EXPIRED;
            case PENDING -> WaitlistOfferUseCase.CommandOutcome.TRANSITION_NOT_ALLOWED;
        };
    }

    private void validatePair(WaitlistEntry entry, WaitlistOffer offer) {
        if (!entry.id().equals(offer.entryId()) || !entry.tenantId().equals(offer.tenantId())
            || !entry.venueId().equals(offer.venueId())
            || !entry.customerPrincipalId().equals(offer.customerPrincipalId())
            || !entry.demand().id().equals(offer.demandId())) {
            throw new IllegalStateException("Offer and Entry scope does not match");
        }
    }

    private static final class Holder {
        private WaitlistEntry entry;
        private WaitlistOffer offer;
    }

    record ActionExecution(
        WaitlistOfferUseCase.CommandOutcome outcome, WaitlistOffer offer,
        PromotionalReservationUseCase.ReservationView reservation
    ) { }

    record TargetExecution(
        WaitlistOfferUseCase.TargetOutcome outcome, WaitlistOffer offer,
        PromotionalReservationUseCase.ReservationView reservation
    ) { }
}
