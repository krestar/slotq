package com.slotq.waitlist.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.PromotionAvailabilityQuery;
import com.slotq.booking.domain.SlotInventoryId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One bounded keyset page, explicitly invoked by System. No scheduler, HOLD or handler invocation. */
@Service
public class WaitlistPromotionDiscovery {
    private final PromotionAvailabilityQuery availability;
    private final WaitlistPromotionRequestUseCase requests;
    private final PromotionDiscoveryPolicy policy;
    private final Clock clock;
    private final TransactionTemplate observation;
    public WaitlistPromotionDiscovery(PromotionAvailabilityQuery availability, WaitlistPromotionRequestUseCase requests,
                                      PromotionDiscoveryPolicy policy, Clock clock, PlatformTransactionManager manager) {
        this.availability = availability; this.requests = requests; this.policy = policy; this.clock = clock;
        observation = new TransactionTemplate(manager);
        observation.setReadOnly(true);
        observation.setTimeout(policy.transactionTimeoutSeconds());
    }
    public Page discover(SystemPrincipal principal, SlotInventoryId after) {
        Objects.requireNonNull(principal, "system principal must not be null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Discovery must start outside a caller transaction");
        }
        if (!policy.enabled()) return new Page(null, 0, 0, 0, List.of());
        var candidates = observation.execute(status -> availability.availableAfter(after, clock.instant(), policy.batchSize()));
        int appended = 0, outstanding = 0;
        List<Failure> failures = new ArrayList<>();
        for (var target : candidates) {
            try {
                var result = requests.request(principal, target.venueId(), target.slotId());
                if (result.outcome() == WaitlistPromotionRequestUseCase.Outcome.APPENDED) appended++;
                if (result.outcome() == WaitlistPromotionRequestUseCase.Outcome.OUTSTANDING) outstanding++;
            } catch (RuntimeException failure) {
                // A failed/unknown target is not a no-op or confirmed rollback. Do not retry it
                // here; retain its evidence for the caller and allow other targets to progress.
                failures.add(new Failure(target.slotId(), failure));
            }
        }
        // Advance past no-op/outstanding candidates too. The caller may discard this advisory
        // cursor on restart and rescan: only the durable link/receipt controls admission.
        var next = candidates.size() == policy.batchSize() ? candidates.getLast().slotId() : null;
        return new Page(next, candidates.size(), appended, outstanding, List.copyOf(failures));
    }
    public record Page(SlotInventoryId nextCursor, int examined, int appended, int outstanding, List<Failure> failures) { }
    public record Failure(SlotInventoryId slotId, RuntimeException cause) { }
}
