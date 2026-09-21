package com.slotq.waitlist.application;

import java.time.Instant;
import java.util.List;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistOfferId;

/** Advisory scans only. No business state mutation or locking read. */
public interface WaitlistMaintenanceQuery {
    List<Pending> pendingAfter(WaitlistOfferId after, int limit);
    List<Waiting> waitingAfter(WaitlistEntryId after, int limit);
    record Pending(WaitlistOfferId id, VenueId venueId) { }
    record Waiting(WaitlistEntryId id, VenueId venueId, Instant startsAt) { }
}
