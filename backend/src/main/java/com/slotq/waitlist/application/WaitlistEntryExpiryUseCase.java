package com.slotq.waitlist.application;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistEntryId;

public interface WaitlistEntryExpiryUseCase {
    boolean expireWaiting(SystemPrincipal principal, VenueId venueId, WaitlistEntryId entryId);
}
