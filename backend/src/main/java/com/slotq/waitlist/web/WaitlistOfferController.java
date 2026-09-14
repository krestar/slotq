package com.slotq.waitlist.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.booking.application.PromotionalReservationUseCase;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.OfferExpiredException;
import com.slotq.waitlist.application.OfferTransitionNotAllowedException;
import com.slotq.waitlist.application.WaitlistOfferUseCase;
import com.slotq.waitlist.application.WaitlistValidationException;
import com.slotq.waitlist.domain.WaitlistOfferId;
import com.slotq.waitlist.domain.WaitlistOfferState;
import com.slotq.waitlist.domain.WaitlistOfferTerminalReason;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/venues/{venueId}/waitlist-offers")
class WaitlistOfferController {
    private final WaitlistOfferUseCase offers;

    WaitlistOfferController(WaitlistOfferUseCase offers) { this.offers = offers; }

    @GetMapping("/{offerId}")
    ResponseEntity<OfferResponse> get(
        @PathVariable UUID venueId, @PathVariable UUID offerId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ok(response(offers.getOffer(
            new VenueId(venueId), new WaitlistOfferId(offerId), principal
        )));
    }

    @PostMapping("/{offerId}/accept")
    ResponseEntity<OfferResponse> accept(
        @PathVariable UUID venueId, @PathVariable UUID offerId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        HttpServletRequest request,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        requireEmptyAction(idempotencyKey, request);
        return action(offers.accept(new VenueId(venueId), new WaitlistOfferId(offerId), principal));
    }

    @PostMapping("/{offerId}/reject")
    ResponseEntity<OfferResponse> reject(
        @PathVariable UUID venueId, @PathVariable UUID offerId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        HttpServletRequest request,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        requireEmptyAction(idempotencyKey, request);
        return action(offers.reject(new VenueId(venueId), new WaitlistOfferId(offerId), principal));
    }

    private ResponseEntity<OfferResponse> action(WaitlistOfferUseCase.CommandResult result) {
        return switch (result.outcome()) {
            case SUCCESS -> ok(response(result.offer()));
            case OFFER_EXPIRED -> throw new OfferExpiredException();
            case TRANSITION_NOT_ALLOWED -> throw new OfferTransitionNotAllowedException();
            case CAPACITY_UNAVAILABLE -> throw new com.slotq.booking.application.CapacityUnavailableException();
        };
    }

    private void requireEmptyAction(String idempotencyKey, HttpServletRequest request) {
        Map<String, String> errors = new java.util.LinkedHashMap<>();
        if (idempotencyKey != null) errors.put("Idempotency-Key", "Idempotency-Key is not allowed.");
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            errors.put("body", "A request body is not allowed.");
        }
        if (!errors.isEmpty()) throw new WaitlistValidationException(errors);
    }

    private ResponseEntity<OfferResponse> ok(OfferResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    static OfferResponse response(WaitlistOfferUseCase.OfferView view) {
        PromotionalReservationUseCase.ReservationView reservation = view.reservation();
        return new OfferResponse(
            view.id(), view.entryId(), view.venueId(), view.resourceId(), view.slotInventoryId(),
            view.state(), view.terminalReason(), view.expiresAt(), view.observedAt(),
            view.venueTimezone().getId(), view.allowedActions(),
            new ReservationResponse(
                reservation.id().value(), reservation.state(), reservation.startsAt(),
                reservation.endsAt(), reservation.expiresAt(), reservation.partySize(),
                reservation.allocationQuantity(), reservation.appliedPolicyVersion()
            )
        );
    }

    record OfferResponse(
        UUID id, UUID entryId, UUID venueId, UUID resourceId, UUID slotInventoryId,
        WaitlistOfferState state, WaitlistOfferTerminalReason terminalReason,
        Instant expiresAt, Instant observedAt, String venueTimezone,
        List<String> allowedActions, ReservationResponse reservation
    ) { }

    record ReservationResponse(
        UUID id, com.slotq.booking.domain.ReservationState state,
        Instant startsAt, Instant endsAt, Instant expiresAt, int partySize,
        int allocationQuantity, long appliedPolicyVersion
    ) { }
}
