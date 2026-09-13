package com.slotq.waitlist.web;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.WaitlistRegistrationKey;
import com.slotq.waitlist.application.WaitlistUseCase;
import com.slotq.waitlist.application.WaitlistValidationException;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistEntryState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/venues/{venueId}")
class WaitlistController {
    private final WaitlistUseCase waitlist;

    WaitlistController(WaitlistUseCase waitlist) { this.waitlist = waitlist; }

    @PostMapping("/waitlist-entries")
    ResponseEntity<EntryResponse> register(
        @PathVariable UUID venueId,
        @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody RegistrationRequest request,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        VenueId targetVenue = new VenueId(venueId);
        WaitlistUseCase.Registration registration = waitlist.register(
            new WaitlistUseCase.CreateRegistration(
                targetVenue, new SlotInventoryId(request.slotInventoryId()), request.partySize(),
                WaitlistRegistrationKey.fromHeader(idempotencyKey), principal
            )
        );
        URI location = URI.create(entryLocation(venueId, registration.entry().id()));
        return ResponseEntity.status(HttpStatus.valueOf(registration.originalStatus()))
            .location(location).cacheControl(CacheControl.noStore())
            .body(response(registration.entry()));
    }

    @GetMapping("/waitlist-registration-requests/{key}")
    ResponseEntity<RegistrationResponse> registration(
        @PathVariable UUID venueId, @PathVariable String key,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        WaitlistUseCase.RegistrationReceipt result = waitlist.getRegistration(
            new VenueId(venueId), WaitlistRegistrationKey.fromHeader(key), principal
        );
        return ok(new RegistrationResponse(
            result.entryId(), result.entryLocation(), result.originalStatus()
        ));
    }

    @GetMapping("/waitlist-entries/{entryId}")
    ResponseEntity<EntryResponse> entry(
        @PathVariable UUID venueId, @PathVariable UUID entryId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ok(response(waitlist.getEntry(
            new VenueId(venueId), new WaitlistEntryId(entryId), principal
        )));
    }

    @GetMapping("/waitlist-entries")
    ResponseEntity<EntryCollectionResponse> entries(
        @PathVariable UUID venueId, @RequestParam LocalDate date,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        WaitlistUseCase.EntryPage page = waitlist.getEntries(
            new VenueId(venueId), date, cursor, limit, principal
        );
        return ok(new EntryCollectionResponse(
            page.items().stream().map(this::response).toList(), page.nextCursor(),
            page.observedAt(), page.venueTimezone().getId()
        ));
    }

    @PostMapping("/waitlist-entries/{entryId}/cancel")
    ResponseEntity<EntryResponse> cancel(
        @PathVariable UUID venueId, @PathVariable UUID entryId,
        HttpServletRequest request,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        requireNoBody(request);
        return ok(response(waitlist.cancel(
            new VenueId(venueId), new WaitlistEntryId(entryId), principal
        )));
    }

    private void requireNoBody(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
            throw new WaitlistValidationException(java.util.Map.of(
                "body", "A request body is not allowed."
            ));
        }
    }

    private EntryResponse response(WaitlistUseCase.EntryView view) {
        return new EntryResponse(
            view.id(), view.venueId(), view.startsAt(), view.endsAt(), view.partySize(),
            view.joinedAt(), view.state(), view.observedAt(), view.venueTimezone().getId(),
            view.allowedActions(), view.offerId()
        );
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private String entryLocation(UUID venueId, UUID entryId) {
        return "/api/v1/venues/" + venueId + "/waitlist-entries/" + entryId;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record RegistrationRequest(
        @NotNull UUID slotInventoryId,
        @NotNull @Positive Integer partySize
    ) { }

    record RegistrationResponse(UUID entryId, String entryLocation, int originalStatus) { }

    record EntryResponse(
        UUID id, UUID venueId, Instant startsAt, Instant endsAt, int partySize,
        Instant joinedAt, WaitlistEntryState state, Instant observedAt,
        String venueTimezone, List<String> allowedActions, UUID offerId
    ) { }

    record EntryCollectionResponse(
        List<EntryResponse> items, String nextCursor, Instant observedAt, String venueTimezone
    ) { }
}
