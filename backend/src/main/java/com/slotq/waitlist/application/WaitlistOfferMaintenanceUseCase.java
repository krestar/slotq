package com.slotq.waitlist.application;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistOfferId;

/** Bounded System routing to the existing #95 reconcile executor, without a caller snapshot. */
public interface WaitlistOfferMaintenanceUseCase {
    void reconcileTarget(SystemPrincipal principal, VenueId venueId, WaitlistOfferId offerId);
}
