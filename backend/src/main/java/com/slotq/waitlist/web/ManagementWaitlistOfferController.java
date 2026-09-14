package com.slotq.waitlist.web;

import java.util.UUID;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.WaitlistOfferUseCase;
import com.slotq.waitlist.domain.WaitlistOfferId;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/management/venues/{venueId}/waitlist-offers")
class ManagementWaitlistOfferController {
    private final WaitlistOfferUseCase offers;

    ManagementWaitlistOfferController(WaitlistOfferUseCase offers) { this.offers = offers; }

    @GetMapping("/{offerId}")
    ResponseEntity<WaitlistOfferController.OfferResponse> get(
        @PathVariable UUID venueId, @PathVariable UUID offerId,
        @AuthenticationPrincipal AuthenticatedPrincipal principal
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            WaitlistOfferController.response(offers.getManagementOffer(
                new VenueId(venueId), new WaitlistOfferId(offerId), principal
            ))
        );
    }
}
