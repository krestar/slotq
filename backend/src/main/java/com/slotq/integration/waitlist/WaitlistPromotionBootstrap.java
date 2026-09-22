package com.slotq.integration.waitlist;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.EventHandlers;
import com.slotq.events.application.EventRegistration;
import com.slotq.events.application.EventRegistrationService;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Initial same-version activation only. No route replacement, removal, backfill or business locks. */
@Component
public class WaitlistPromotionBootstrap implements ApplicationRunner {
    private static final List<ConsumerRoute> ROUTES = List.of(
        BookingCapacityReleasedHandler.ROUTE, WaitlistPromotionRequestedHandler.ROUTE);
    private final EventRegistrationService registrations;
    private final EventHandlers handlers;
    private final WaitlistPromotionReadiness readiness;
    private final boolean enabled;
    private final boolean deliveryEnabled;
    private final boolean maintenanceEnabled;

    public WaitlistPromotionBootstrap(EventRegistrationService registrations, EventHandlers handlers,
        WaitlistPromotionReadiness readiness,
        @Value("${slotq.waitlist.promotion.enabled:false}") boolean enabled,
        @Value("${slotq.events.delivery.scheduler-enabled:false}") boolean deliveryEnabled,
        @Value("${slotq.waitlist.promotion.maintenance-enabled:false}") boolean maintenanceEnabled) {
        this.registrations = registrations; this.handlers = handlers; this.readiness = readiness;
        this.enabled = enabled; this.deliveryEnabled = deliveryEnabled; this.maintenanceEnabled = maintenanceEnabled;
    }
    @Override public void run(ApplicationArguments args) { activate(); }

    public synchronized void activate() {
        readiness.close();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Bootstrap must start outside a caller transaction");
        }
        if (!enabled) return;
        if (!deliveryEnabled || !maintenanceEnabled) {
            throw new IllegalStateException("Enabled Waitlist requires delivery and maintenance schedulers");
        }
        requireHandler(BookingCapacityReleasedHandler.ROUTE, BookingCapacityReleasedHandler.class);
        requireHandler(WaitlistPromotionRequestedHandler.ROUTE, WaitlistPromotionRequestedHandler.class);
        var active = inspect();
        for (var route : ROUTES) {
            if (active.containsKey(route)) continue;
            try {
                registrations.activate(route);
            } catch (EventRegistrationService.AlreadyActiveException | DataAccessException | TransactionException failure) {
                // A competing activation or unknown response is not evidence of success. No retry
                // loop: one new fenced transaction must prove the exact durable generation exists.
                Map<ConsumerRoute, UUID> observed;
                try { observed = inspect(); }
                catch (RuntimeException verificationFailure) {
                    failure.addSuppressed(verificationFailure); throw failure;
                }
                if (!observed.containsKey(route)) throw failure;
            }
            active = inspect();
        }
        var committed = inspect();
        if (!committed.keySet().equals(Set.copyOf(ROUTES)) || !committed.equals(active)) {
            throw new IllegalStateException("Waitlist registration changed during activation");
        }
        readiness.open();
    }

    private void requireHandler(ConsumerRoute route, Class<?> expected) {
        var handler = handlers.resolve(route);
        if (!route.equals(handler.route()) || !expected.equals(AopUtils.getTargetClass(handler))) {
            throw new IllegalStateException("Waitlist production handler mismatch");
        }
    }
    private Map<ConsumerRoute, UUID> inspect() {
        var snapshot = registrations.inspect("waitlist.promotion", ROUTES.stream().map(ConsumerRoute::eventType).toList());
        Map<ConsumerRoute, UUID> active = new HashMap<>();
        Map<ConsumerRoute, EventRegistration> previous = new HashMap<>();
        Set<Long> boundaries = new HashSet<>();
        for (var row : snapshot.registrations()) {
            if (!ROUTES.contains(row.route()) || row.id() == null || row.activationBoundary() <= 0
                || row.activationBoundary() > snapshot.boundary()
                || (row.deactivationBoundary() != null && (row.deactivationBoundary() <= row.activationBoundary()
                    || row.deactivationBoundary() > snapshot.boundary()))) {
                throw new IllegalStateException("Incompatible or corrupt Waitlist registration");
            }
            if (!boundaries.add(row.activationBoundary())
                || (row.deactivationBoundary() != null && !boundaries.add(row.deactivationBoundary()))) {
                throw new IllegalStateException("Reused Waitlist registration boundary");
            }
            var prior = previous.put(row.route(), row);
            if (prior != null && (prior.active() || prior.deactivationBoundary() >= row.activationBoundary())) {
                throw new IllegalStateException("Overlapping Waitlist registration generations");
            }
            if (row.active() && active.put(row.route(), row.id()) != null) {
                throw new IllegalStateException("Duplicate active Waitlist registration");
            }
        }
        if (!active.keySet().containsAll(previous.keySet())) {
            throw new IllegalStateException("Waitlist route was deactivated; automatic replacement is unsupported");
        }
        return Map.copyOf(active);
    }
}
