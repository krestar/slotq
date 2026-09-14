package com.slotq.waitlist.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistEntryState;
import com.slotq.waitlist.domain.WaitlistOfferState;

public interface WaitlistUseCase {

    Registration register(CreateRegistration command);

    RegistrationReceipt getRegistration(
        VenueId venueId,
        WaitlistRegistrationKey key,
        AuthenticatedPrincipal principal
    );

    EntryView getEntry(
        VenueId venueId,
        WaitlistEntryId entryId,
        AuthenticatedPrincipal principal
    );

    EntryPage getEntries(
        VenueId venueId,
        LocalDate date,
        String cursor,
        Integer limit,
        AuthenticatedPrincipal principal
    );

    EntryView cancel(
        VenueId venueId,
        WaitlistEntryId entryId,
        AuthenticatedPrincipal principal
    );

    ManagementPage getManagementEntries(
        VenueId venueId,
        LocalDate date,
        SlotInventoryId slotInventoryId,
        String cursor,
        Integer limit,
        AuthenticatedPrincipal principal
    );

    record CreateRegistration(
        VenueId venueId,
        SlotInventoryId slotInventoryId,
        int partySize,
        WaitlistRegistrationKey key,
        AuthenticatedPrincipal principal
    ) { }

    record Registration(EntryView entry, int originalStatus) { }

    record RegistrationReceipt(
        UUID entryId,
        String entryLocation,
        int originalStatus
    ) { }

    record EntryView(
        UUID id,
        UUID venueId,
        Instant startsAt,
        Instant endsAt,
        int partySize,
        Instant joinedAt,
        WaitlistEntryState state,
        Instant observedAt,
        ZoneId venueTimezone,
        List<String> allowedActions,
        UUID offerId
    ) {
        public EntryView {
            allowedActions = List.copyOf(allowedActions);
        }
    }

    record EntryPage(
        List<EntryView> items,
        String nextCursor,
        Instant observedAt,
        ZoneId venueTimezone
    ) {
        public EntryPage {
            items = List.copyOf(items);
        }
    }

    record ManagementItem(
        EntryView entry, Boolean eligibleForSlot, WaitlistOfferState offerState,
        Instant offerExpiresAt, UUID reservationId
    ) { }

    record ManagementPage(
        List<ManagementItem> items,
        String nextCursor,
        Instant observedAt,
        ZoneId venueTimezone
    ) {
        public ManagementPage {
            items = List.copyOf(items);
        }
    }
}
