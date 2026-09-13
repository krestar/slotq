package com.slotq.waitlist.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.WaitlistUseCase;
import com.slotq.waitlist.domain.WaitlistEntryState;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/management/venues/{venueId}/waitlist-entries")
class ManagementWaitlistController {
    private final WaitlistUseCase waitlist;

    ManagementWaitlistController(WaitlistUseCase waitlist) { this.waitlist = waitlist; }

    @GetMapping
    ResponseEntity<CollectionResponse> entries(
        @PathVariable UUID venueId, @RequestParam LocalDate date,
        @RequestParam(required = false) UUID slotInventoryId,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false) Integer limit,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        WaitlistUseCase.ManagementPage page = waitlist.getManagementEntries(
            new VenueId(venueId), date,
            slotInventoryId == null ? null : new SlotInventoryId(slotInventoryId),
            cursor, limit, principal
        );
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new CollectionResponse(
            page.items().stream().map(this::item).toList(), page.nextCursor(),
            page.observedAt(), page.venueTimezone().getId()
        ));
    }

    private ItemResponse item(WaitlistUseCase.ManagementItem item) {
        WaitlistUseCase.EntryView view = item.entry();
        return new ItemResponse(
            view.id(), view.venueId(), view.startsAt(), view.endsAt(), view.partySize(),
            view.joinedAt(), view.state(), view.observedAt(), view.venueTimezone().getId(),
            view.allowedActions(), view.offerId(), item.eligibleForSlot()
        );
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ItemResponse(
        UUID id, UUID venueId, Instant startsAt, Instant endsAt, int partySize,
        Instant joinedAt, WaitlistEntryState state, Instant observedAt,
        String venueTimezone, List<String> allowedActions, UUID offerId,
        Boolean eligibleForSlot
    ) { }

    record CollectionResponse(
        List<ItemResponse> items, String nextCursor, Instant observedAt, String venueTimezone
    ) { }
}
