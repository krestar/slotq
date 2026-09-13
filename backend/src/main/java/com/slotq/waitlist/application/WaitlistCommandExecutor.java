package com.slotq.waitlist.application;

import java.time.Instant;

import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.booking.application.WaitlistDemandQuery;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistDemand;
import com.slotq.waitlist.domain.WaitlistEntry;
import com.slotq.waitlist.domain.WaitlistEntryId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class WaitlistCommandExecutor {

    private final WaitlistDemandQuery demandQuery;
    private final WaitlistDemandStore demandStore;
    private final WaitlistEntryRepository entryRepository;
    private final WaitlistRegistrationStore registrationStore;

    WaitlistCommandExecutor(
        WaitlistDemandQuery demandQuery,
        WaitlistDemandStore demandStore,
        WaitlistEntryRepository entryRepository,
        WaitlistRegistrationStore registrationStore
    ) {
        this.demandQuery = demandQuery;
        this.demandStore = demandStore;
        this.entryRepository = entryRepository;
        this.registrationStore = registrationStore;
    }

    @Transactional
    Registration register(
        VenueId venueId,
        SlotInventoryId slotInventoryId,
        PrincipalId customerPrincipalId,
        int partySize,
        WaitlistRegistrationKey key,
        Instant commandNow
    ) {
        WaitlistDemandQuery.RegistrationTarget target = demandQuery
            .findRegistrationTargetForUpdate(venueId, slotInventoryId, partySize)
            .orElseThrow(ResourceNotFoundException::new);
        WaitlistRegistrationStore.Fingerprint fingerprint = new WaitlistRegistrationStore.Fingerprint(
            venueId, target.originalResourceId(), slotInventoryId, partySize
        );
        WaitlistRegistrationStore.Claim claim = registrationStore.claim(
            target.tenantId(), customerPrincipalId, key, fingerprint, commandNow
        );
        if (!claim.fingerprint().sameRequest(fingerprint)) {
            throw new WaitlistIdempotencyKeyReusedException();
        }
        if (!claim.owner()) {
            if (claim.entryId() == null || claim.originalStatus() == null) {
                throw new IllegalStateException("Committed waitlist registration is incomplete");
            }
            WaitlistEntry replay = entryRepository.findOwned(
                venueId, customerPrincipalId, claim.entryId()
            ).orElseThrow(() -> new IllegalStateException("Waitlist registration entry is missing"));
            return new Registration(replay, claim.originalStatus());
        }

        if (!target.tenantActive() || !target.venueActive()
            || !commandNow.isBefore(target.startsAt()) || !target.eligibleResourceExists()) {
            throw new WaitlistDemandNotAllowedException();
        }
        WaitlistDemand demand = demandStore.lockOrCreate(new WaitlistDemand.Identity(
            target.tenantId(), venueId, target.startsAt(), target.endsAt(), partySize
        ));
        WaitlistEntry entry = entryRepository.findActiveForUpdate(
            target.tenantId(), venueId, customerPrincipalId, demand.id()
        ).orElse(null);
        int originalStatus;
        if (entry == null) {
            entry = WaitlistEntry.join(
                WaitlistEntryId.newId(), customerPrincipalId, demand, commandNow
            );
            entryRepository.create(entry);
            originalStatus = 201;
        } else {
            originalStatus = 200;
        }
        registrationStore.complete(
            target.tenantId(), customerPrincipalId, key,
            entry.id(), originalStatus, commandNow
        );
        return new Registration(entry, originalStatus);
    }

    @Transactional
    WaitlistEntry cancel(
        VenueId venueId,
        PrincipalId customerPrincipalId,
        WaitlistEntry routedEntry,
        Instant commandNow
    ) {
        demandStore.lock(routedEntry.demand());
        WaitlistEntry current = entryRepository.findOwnedForUpdate(
            venueId, customerPrincipalId, routedEntry.id()
        ).orElseThrow(ResourceNotFoundException::new);
        if (!current.demand().id().equals(routedEntry.demand().id())) {
            throw new ResourceNotFoundException();
        }
        try {
            current.cancel(commandNow);
        } catch (IllegalStateException invalidTransition) {
            throw new WaitlistTransitionNotAllowedException();
        }
        entryRepository.updateState(venueId, current);
        return current;
    }

    record Registration(WaitlistEntry entry, int originalStatus) { }
}
