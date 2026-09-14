package com.slotq.waitlist.application;

import java.util.Optional;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistOffer;
import com.slotq.waitlist.domain.WaitlistOfferId;

public interface WaitlistOfferRepository {
    void create(VenueId venueId, WaitlistOffer offer);
    void update(VenueId venueId, WaitlistOffer offer);
    Optional<WaitlistOffer> find(VenueId venueId, WaitlistOfferId offerId);
    Optional<WaitlistOffer> findOwned(VenueId venueId, PrincipalId customerId, WaitlistOfferId offerId);
    Optional<WaitlistOffer> findByEntry(VenueId venueId, WaitlistEntryId entryId);
    Optional<WaitlistOffer> findByEntryForUpdate(VenueId venueId, WaitlistEntryId entryId);
    Optional<WaitlistOffer> findOwnedForUpdate(
        VenueId venueId, PrincipalId customerId, WaitlistOfferId offerId
    );
}
