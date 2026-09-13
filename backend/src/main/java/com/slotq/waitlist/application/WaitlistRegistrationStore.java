package com.slotq.waitlist.application;

import java.time.Instant;
import java.util.Optional;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistEntryId;

public interface WaitlistRegistrationStore {

    Claim claim(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        WaitlistRegistrationKey key,
        Fingerprint fingerprint,
        Instant startedAt
    );

    void complete(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        WaitlistRegistrationKey key,
        WaitlistEntryId entryId,
        int originalStatus,
        Instant completedAt
    );

    Optional<Result> find(
        VenueId venueId,
        PrincipalId customerPrincipalId,
        WaitlistRegistrationKey key
    );

    record Fingerprint(
        VenueId venueId,
        ResourceId originalResourceId,
        SlotInventoryId slotInventoryId,
        int partySize
    ) {
        public boolean sameRequest(Fingerprint other) {
            return other != null
                && venueId.equals(other.venueId)
                && slotInventoryId.equals(other.slotInventoryId)
                && partySize == other.partySize;
        }
    }

    record Claim(
        boolean owner,
        Fingerprint fingerprint,
        WaitlistEntryId entryId,
        Integer originalStatus
    ) { }

    record Result(WaitlistEntryId entryId, int originalStatus) { }
}
