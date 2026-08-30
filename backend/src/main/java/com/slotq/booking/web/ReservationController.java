package com.slotq.booking.web;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.domain.VenueId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/venues/{venueId}/reservations")
class ReservationController {

    private final ReservationUseCase reservationUseCase;
    ReservationController(ReservationUseCase reservationUseCase) {
        this.reservationUseCase = reservationUseCase;
    }

    @PostMapping("/holds")
    ResponseEntity<ReservationResponse> createHold(
        @PathVariable UUID venueId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @Valid @RequestBody HoldRequest request
    ) {
        VenueId targetVenueId = new VenueId(venueId);
        ReservationUseCase.ReservationDetails details = reservationUseCase.createHold(
            new ReservationUseCase.CreateHold(
                targetVenueId,
                new SlotInventoryId(request.slotInventoryId()),
                principal,
                request.partySize()
            )
        );
        ReservationResponse response = response(details);
        return ResponseEntity.created(URI.create(
            "/api/v1/venues/" + venueId + "/reservations/" + response.id()
        )).body(response);
    }

    @GetMapping("/{reservationId}")
    ResponseEntity<ReservationResponse> getReservation(
        @PathVariable UUID venueId,
        @PathVariable UUID reservationId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        ReservationUseCase.ReservationDetails details = reservationUseCase.getReservation(
            new VenueId(venueId), new ReservationId(reservationId), principal
        );
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(response(details));
    }

    private ReservationResponse response(ReservationUseCase.ReservationDetails details) {
        Reservation reservation = details.reservation();
        return new ReservationResponse(
            reservation.id().value(), reservation.venueId().value(), reservation.resourceId().value(),
            reservation.slotInventoryId().value(), details.effectiveState(),
            reservation.partySize().value(), reservation.allocation().units(), reservation.startsAt(),
            details.endsAt(), reservation.expiresAt(), reservation.cancelAllowedUntil(),
            reservation.noShowEligibleAt(), reservation.appliedPolicyVersion()
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record HoldRequest(@NotNull UUID slotInventoryId, @Min(1) int partySize) { }

    record ReservationResponse(
        UUID id,
        UUID venueId,
        UUID resourceId,
        UUID slotInventoryId,
        com.slotq.booking.domain.ReservationState state,
        int partySize,
        int allocationQuantity,
        Instant startsAt,
        Instant endsAt,
        Instant expiresAt,
        Instant cancelAllowedUntil,
        Instant noShowEligibleAt,
        long appliedPolicyVersion
    ) { }
}
