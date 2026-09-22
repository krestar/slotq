package com.slotq.waitlist.application;

import java.util.UUID;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.domain.VenueId;

/** One newly observed opportunity; never a replacement/retry of an unfinished request. */
public interface WaitlistPromotionRequestUseCase {
    Result request(SystemPrincipal principal, VenueId venueId, SlotInventoryId slotId);
    enum Outcome { APPENDED, OUTSTANDING, NO_OP, DISABLED }
    record Result(Outcome outcome, UUID eventId) { }
}
