package com.slotq.integration.waitlist;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.CapacityReleaseReadiness;
import com.slotq.booking.application.PromotionAvailabilityQuery;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.events.application.EventAppendService;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRecordQuery;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.PromotionDiscoveryPolicy;
import com.slotq.waitlist.application.PromotionIdentityException;
import com.slotq.waitlist.application.WaitlistPromotionRequestStore;
import com.slotq.waitlist.application.WaitlistPromotionRequestUseCase;
import com.slotq.waitlist.application.WaitlistPromotionUseCase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WaitlistPromotionRequestAdmission implements WaitlistPromotionRequestUseCase {
    private final PromotionAvailabilityQuery availability;
    private final WaitlistPromotionRequestStore requests;
    private final EventRecordQuery records;
    private final EventAppendService append;
    private final WaitlistPromotionEventAdapter schema;
    private final PromotionDiscoveryPolicy policy;
    private final Optional<CapacityReleaseReadiness> readiness;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate observation;

    WaitlistPromotionRequestAdmission(PromotionAvailabilityQuery availability, WaitlistPromotionRequestStore requests,
        EventRecordQuery records, EventAppendService append, WaitlistPromotionEventAdapter schema,
        PromotionDiscoveryPolicy policy, Optional<CapacityReleaseReadiness> readiness,
        Clock clock, PlatformTransactionManager manager) {
        this.availability = availability; this.requests = requests; this.records = records;
        this.append = append; this.schema = schema; this.policy = policy; this.readiness = readiness; this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        transaction.setTimeout(policy.transactionTimeoutSeconds());
        this.observation = new TransactionTemplate(manager);
        observation.setReadOnly(true);
        observation.setTimeout(policy.transactionTimeoutSeconds());
    }

    @Override public Result request(SystemPrincipal principal, VenueId venueId, SlotInventoryId slotId) {
        Objects.requireNonNull(principal, "system principal must not be null");
        // A caller's early RR snapshot is not a new discovery invocation. Do not suspend it.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Request discovery must start outside a caller transaction");
        }
        if (!policy.enabled()) return new Result(Outcome.DISABLED, null);
        if (!readiness.map(CapacityReleaseReadiness::isReady).orElse(false)) {
            throw new IllegalStateException("waitlist promotion producer is not ready");
        }
        // Only immutable ownership is carried across this read and the target transaction.
        var identity = observation.execute(status -> availability.observe(venueId, slotId, clock.instant()).orElse(null));
        if (identity == null) return new Result(Outcome.NO_OP, null);
        return transaction.execute(status -> {
            UUID latest = requests.lockLatest(identity.tenantId(), identity.slotId());
            if (latest != null) {
                var receipt = requests.receiptCurrent(identity.tenantId(), latest).orElse(null);
                if (receipt == null || receipt.result() == null) return new Result(Outcome.OUTSTANDING, latest);
                var stored = records.find(identity.tenantId(), new EventId(latest)).orElseThrow(PromotionIdentityException::new);
                var meaning = schema.decode(stored.envelope(), WaitlistPromotionRequestedHandler.ROUTE,
                    WaitlistPromotionUseCase.Signal.PROMOTION_REQUESTED);
                if (!meaning.equals(receipt.meaning()) || !meaning.slotInventoryId().equals(identity.slotId())
                    || !meaning.venueId().equals(identity.venueId()) || !meaning.resourceId().equals(identity.resourceId())) {
                    throw new PromotionIdentityException();
                }
            }
            // No consistent read in this transaction preceded the receipt lock. Any waited-on
            // completion is committed before this new snapshot/opportunity is observed.
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var current = availability.observe(venueId, slotId, now).orElse(null);
            if (current == null || !current.tenantId().equals(identity.tenantId())
                || !current.resourceId().equals(identity.resourceId())) throw new PromotionIdentityException();
            if (!current.available() || !requests.hasWaiting(current)) return new Result(Outcome.NO_OP, latest);
            EventId eventId = EventId.newId();
            // All admission writes precede the foundation fence; no new business locks after append.
            requests.connect(identity.tenantId(), slotId, eventId.value());
            var route = WaitlistPromotionRequestedHandler.ROUTE;
            String payload = "{\"venueId\":\"%s\",\"resourceId\":\"%s\",\"slotInventoryId\":\"%s\"}"
                .formatted(current.venueId().value(), current.resourceId().value(), current.slotId().value());
            append.appendForActiveRoute(new EventEnvelope(eventId, current.tenantId(), "SlotInventory", slotId.value(),
                route.eventType(), route.schemaVersion(), now, payload), route);
            return new Result(Outcome.APPENDED, eventId.value());
        });
    }
}
