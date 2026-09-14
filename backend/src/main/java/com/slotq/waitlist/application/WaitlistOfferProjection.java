package com.slotq.waitlist.application;

import java.time.Instant;

import com.slotq.booking.application.PromotionalReservationUseCase;
import com.slotq.booking.domain.ReservationState;
import com.slotq.waitlist.domain.WaitlistOffer;
import com.slotq.waitlist.domain.WaitlistOfferState;
import com.slotq.waitlist.domain.WaitlistOfferTerminalReason;
import org.springframework.stereotype.Component;

@Component
class WaitlistOfferProjection {
    Effective project(
        WaitlistOffer offer, PromotionalReservationUseCase.ReservationView reservation, Instant now
    ) {
        validateScope(offer, reservation);
        if (offer.state() == WaitlistOfferState.ACCEPTED) {
            if (!reservation.promotionalConfirmed()) {
                throw new IllegalStateException("ACCEPTED Offer has no durable confirm evidence");
            }
            return new Effective(WaitlistOfferState.ACCEPTED, null);
        }
        if (offer.state() == WaitlistOfferState.DECLINED) {
            if (reservation.promotionalConfirmed()) {
                throw new IllegalStateException("DECLINED Offer conflicts with confirm evidence");
            }
            if (reservation.state() != ReservationState.CANCELLED || reservation.allocationActive()) {
                throw new IllegalStateException("DECLINED Offer backing is not released");
            }
            return new Effective(WaitlistOfferState.DECLINED, offer.terminalReason());
        }
        if (offer.state() == WaitlistOfferState.EXPIRED) {
            if (reservation.promotionalConfirmed()) {
                throw new IllegalStateException("EXPIRED Offer conflicts with confirm evidence");
            }
            if (reservation.state() != ReservationState.EXPIRED || reservation.allocationActive()) {
                throw new IllegalStateException("EXPIRED Offer backing is not released");
            }
            return new Effective(WaitlistOfferState.EXPIRED, WaitlistOfferTerminalReason.HOLD_EXPIRED);
        }
        if (reservation.promotionalConfirmed()) {
            return new Effective(WaitlistOfferState.ACCEPTED, null);
        }
        if (reservation.state() == ReservationState.CANCELLED) {
            return new Effective(WaitlistOfferState.DECLINED,
                WaitlistOfferTerminalReason.BACKING_CANCELLED);
        }
        if (reservation.state() == ReservationState.EXPIRED
            || (reservation.state() == ReservationState.HELD && !now.isBefore(offer.expiresAt()))) {
            return new Effective(WaitlistOfferState.EXPIRED,
                WaitlistOfferTerminalReason.HOLD_EXPIRED);
        }
        if (reservation.state() != ReservationState.HELD || !reservation.allocationActive()
            || !reservation.expiresAt().equals(offer.expiresAt())) {
            throw new IllegalStateException("PENDING Offer backing is invalid");
        }
        return new Effective(WaitlistOfferState.PENDING, null);
    }

    private void validateScope(
        WaitlistOffer offer, PromotionalReservationUseCase.ReservationView reservation
    ) {
        if (!offer.reservationId().equals(reservation.id())
            || !offer.tenantId().equals(reservation.tenantId())
            || !offer.venueId().equals(reservation.venueId())
            || !offer.resourceId().equals(reservation.resourceId())
            || !offer.slotInventoryId().equals(reservation.slotInventoryId())
            || !offer.customerPrincipalId().equals(reservation.customerPrincipalId())) {
            throw new IllegalStateException("Offer and promotional Reservation scope does not match");
        }
    }

    record Effective(WaitlistOfferState state, WaitlistOfferTerminalReason reason) { }
}
