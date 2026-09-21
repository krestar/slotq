package com.slotq.waitlist.application;

import java.time.Clock;
import java.util.Objects;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistEntryId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WaitlistEntryExpiryService implements WaitlistEntryExpiryUseCase {
    private final WaitlistEntryRepository entries;
    private final Clock clock;
    WaitlistEntryExpiryService(WaitlistEntryRepository entries, Clock clock) {
        this.entries = entries; this.clock = clock;
    }
    @Override @Transactional
    public boolean expireWaiting(SystemPrincipal principal, VenueId venueId, WaitlistEntryId entryId) {
        Objects.requireNonNull(principal, "system principal must not be null");
        var commandNow = clock.instant();
        var entry = entries.findForExpiry(venueId, entryId).orElseThrow(ResourceNotFoundException::new);
        if (!entry.expireWaiting(commandNow)) return false;
        entries.updateState(venueId, entry);
        return true;
    }
}
