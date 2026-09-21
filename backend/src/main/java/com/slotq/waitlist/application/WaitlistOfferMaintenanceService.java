package com.slotq.waitlist.application;

import java.time.Clock;
import java.util.Objects;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistOfferId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class WaitlistOfferMaintenanceService implements WaitlistOfferMaintenanceUseCase {
    private final WaitlistOfferRepository offers;
    private final WaitlistOfferCommandExecutor executor;
    private final WaitlistOfferProjection projection;
    private final Clock clock;
    private final TransactionTemplate routing;
    private final TransactionTemplate target;

    WaitlistOfferMaintenanceService(WaitlistOfferRepository offers, WaitlistOfferCommandExecutor executor,
        WaitlistOfferProjection projection,
        Clock clock, PlatformTransactionManager manager,
        @Value("${slotq.waitlist.promotion.maintenance-timeout-seconds:5}") int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 30) throw new IllegalArgumentException("Invalid maintenance timeout");
        this.offers = offers; this.executor = executor; this.projection = projection; this.clock = clock;
        routing = new TransactionTemplate(manager); routing.setReadOnly(true); routing.setTimeout(timeoutSeconds);
        target = new TransactionTemplate(manager); target.setTimeout(timeoutSeconds);
    }
    @Override
    public void reconcileTarget(SystemPrincipal principal, VenueId venueId, WaitlistOfferId offerId) {
        Objects.requireNonNull(principal, "system principal must not be null");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Maintenance reconcile must start outside a caller transaction");
        }
        // Preserve #95's routing-before-target boundary. Otherwise this SELECT establishes an
        // early RR snapshot before Entry/Offer locks, including its terminal backing validation.
        var routed = routing.execute(status -> offers.find(venueId, offerId).orElseThrow(ResourceNotFoundException::new));
        var commandNow = clock.instant();
        target.executeWithoutResult(status -> {
            var result = executor.reconcile(routed, commandNow);
            projection.project(result.offer(), result.reservation(), commandNow);
        });
    }
}
