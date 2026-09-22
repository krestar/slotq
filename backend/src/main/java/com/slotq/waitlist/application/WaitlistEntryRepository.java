package com.slotq.waitlist.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistDemandId;
import com.slotq.waitlist.domain.WaitlistEntry;
import com.slotq.waitlist.domain.WaitlistEntryId;

public interface WaitlistEntryRepository {

    void create(WaitlistEntry entry);

    void updateState(VenueId venueId, WaitlistEntry entry);

    Optional<WaitlistEntry> findOwned(
        VenueId venueId,
        PrincipalId customerPrincipalId,
        WaitlistEntryId entryId
    );

    Optional<WaitlistEntry> findOwnedForUpdate(
        VenueId venueId,
        PrincipalId customerPrincipalId,
        WaitlistEntryId entryId
    );

    Optional<WaitlistEntry> find(VenueId venueId, WaitlistEntryId entryId);

    Optional<WaitlistEntry> findForUpdate(VenueId venueId, WaitlistEntryId entryId);

    Optional<WaitlistEntry> findForExpiry(VenueId venueId, WaitlistEntryId entryId);

    Optional<WaitlistEntry> firstEligibleForUpdate(
        TenantId tenantId, VenueId venueId, Instant startsAt, Instant endsAt, int seatingCapacity
    );

    Optional<WaitlistEntry> findActiveForUpdate(
        TenantId tenantId,
        VenueId venueId,
        PrincipalId customerPrincipalId,
        WaitlistDemandId demandId
    );

    Page findAllOwned(
        TenantId tenantId,
        VenueId venueId,
        PrincipalId customerPrincipalId,
        Instant startsAt,
        Instant endsAt,
        Optional<Seek> seek,
        int fetchSize
    );

    Page findAllForVenue(
        TenantId tenantId,
        VenueId venueId,
        Instant startsAt,
        Instant endsAt,
        Optional<TimeWindow> demandWindow,
        Optional<Seek> seek,
        int fetchSize
    );

    record Seek(Instant joinedAt, WaitlistEntryId entryId) { }

    record TimeWindow(Instant startsAt, Instant endsAt) { }

    record Page(List<WaitlistEntry> entries) {
        public Page {
            entries = List.copyOf(entries);
        }
    }
}
