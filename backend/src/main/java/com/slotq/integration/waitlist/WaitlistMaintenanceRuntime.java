package com.slotq.integration.waitlist;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.BookingMaintenanceQuery;
import com.slotq.booking.application.CapacityReleaseReadiness;
import com.slotq.booking.application.ReservationExpiryUseCase;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.waitlist.application.PromotionDiscoveryPolicy;
import com.slotq.waitlist.application.WaitlistEntryExpiryUseCase;
import com.slotq.waitlist.application.WaitlistMaintenanceQuery;
import com.slotq.waitlist.application.WaitlistOfferMaintenanceUseCase;
import com.slotq.waitlist.application.WaitlistPromotionDiscovery;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistOfferId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit System cycle, not a scheduler. Cursors are advisory and restart from the durable backlog. */
@Component
public class WaitlistMaintenanceRuntime {
    private final BookingMaintenanceQuery bookingScan;
    private final WaitlistMaintenanceQuery waitlistScan;
    private final ReservationExpiryUseCase expiry;
    private final WaitlistOfferMaintenanceUseCase offers;
    private final WaitlistEntryExpiryUseCase entries;
    private final WaitlistPromotionDiscovery discovery;
    private final WaitlistMaintenancePolicy policy;
    private final PromotionDiscoveryPolicy promotion;
    private final Optional<CapacityReleaseReadiness> readiness;
    private final Clock clock;
    private final TransactionTemplate scan;
    private final TransactionTemplate target;
    private ReservationId heldCursor;
    private WaitlistOfferId offerCursor;
    private WaitlistEntryId entryCursor;
    private SlotInventoryId discoveryCursor;

    public WaitlistMaintenanceRuntime(BookingMaintenanceQuery bookingScan, WaitlistMaintenanceQuery waitlistScan,
        ReservationExpiryUseCase expiry, WaitlistOfferMaintenanceUseCase offers, WaitlistEntryExpiryUseCase entries,
        WaitlistPromotionDiscovery discovery, WaitlistMaintenancePolicy policy, PromotionDiscoveryPolicy promotion,
        Optional<CapacityReleaseReadiness> readiness, Clock clock, PlatformTransactionManager manager) {
        this.bookingScan = bookingScan; this.waitlistScan = waitlistScan; this.expiry = expiry;
        this.offers = offers; this.entries = entries; this.discovery = discovery; this.policy = policy;
        this.promotion = promotion; this.readiness = readiness; this.clock = clock;
        scan = new TransactionTemplate(manager); scan.setReadOnly(true);
        scan.setTimeout(policy.transactionTimeoutSeconds());
        target = new TransactionTemplate(manager); target.setTimeout(policy.transactionTimeoutSeconds());
    }

    public synchronized Cycle runCycle(SystemPrincipal principal) {
        Objects.requireNonNull(principal, "system principal must not be null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Maintenance must start outside a caller transaction");
        }
        if (!policy.enabled() || !promotion.enabled()) return new Cycle(0, 0, 0, 0, List.of());
        if (!readiness.map(CapacityReleaseReadiness::isReady).orElse(false)) {
            throw new IllegalStateException("Waitlist promotion is not ready");
        }
        var scanNow = clock.instant();
        List<Failure> failures = new ArrayList<>();
        int held = 0, pending = 0, waiting = 0, discovered = 0;
        try {
            var page = scan.execute(status -> bookingScan.heldAfter(heldCursor, policy.batchSize()));
            held = page.size();
            for (var row : page) {
                if (!row.expiresAt().isAfter(scanNow)) attempt(Kind.HOLD, row.id().value(), failures,
                    () -> expiry.expire(row.venueId(), row.id(), principal));
            }
            heldCursor = page.size() == policy.batchSize() ? page.getLast().id() : null;
        } catch (RuntimeException failure) { failures.add(new Failure(Kind.HOLD, null, failure)); }
        try {
            var page = scan.execute(status -> waitlistScan.pendingAfter(offerCursor, policy.batchSize()));
            pending = page.size();
            for (var row : page) {
                try {
                    // The #95 routing read must finish before the narrow reconcile transaction.
                    offers.reconcileTarget(principal, row.venueId(), row.id());
                } catch (RuntimeException failure) { failures.add(new Failure(Kind.OFFER, row.id().value(), failure)); }
            }
            offerCursor = page.size() == policy.batchSize() ? page.getLast().id() : null;
        } catch (RuntimeException failure) { failures.add(new Failure(Kind.OFFER, null, failure)); }
        try {
            var page = scan.execute(status -> waitlistScan.waitingAfter(entryCursor, policy.batchSize()));
            waiting = page.size();
            for (var row : page) {
                if (!row.startsAt().isAfter(scanNow)) attempt(Kind.ENTRY, row.id().value(), failures,
                    () -> entries.expireWaiting(principal, row.venueId(), row.id()));
            }
            entryCursor = page.size() == policy.batchSize() ? page.getLast().id() : null;
        } catch (RuntimeException failure) { failures.add(new Failure(Kind.ENTRY, null, failure)); }
        try {
            // Discovery owns separate scan/admission transactions. Never wrap it in a target transaction.
            var page = discovery.discover(principal, discoveryCursor);
            discovered = page.examined(); discoveryCursor = page.nextCursor();
            page.failures().forEach(failure -> failures.add(
                new Failure(Kind.REQUEST, failure.slotId().value(), failure.cause())));
        } catch (RuntimeException failure) { failures.add(new Failure(Kind.REQUEST, null, failure)); }
        return new Cycle(held, pending, waiting, discovered, List.copyOf(failures));
    }

    private void attempt(Kind kind, UUID id, List<Failure> failures, Runnable command) {
        try {
            // One target only. Existing public commands capture commandNow afresh before their locks.
            target.executeWithoutResult(status -> command.run());
        } catch (RuntimeException failure) {
            // No local retry or compensation; unknown outcome is not asserted to be a rollback.
            failures.add(new Failure(kind, id, failure));
        }
    }
    public enum Kind { HOLD, OFFER, ENTRY, REQUEST }
    /** Counts are scanned rows, not claims of mutations/commits. Null failure id means scan failure. */
    public record Cycle(int heldScanned, int offersScanned, int entriesScanned, int slotsScanned,
                        List<Failure> failures) { }
    public record Failure(Kind kind, UUID targetId, RuntimeException cause) { }
}
