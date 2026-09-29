package com.slotq.integration.operations;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryExecutionScope;
import com.slotq.events.application.EventHandlers;
import com.slotq.events.application.EventDeliveryReadiness;
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

/** Registers only in DB-direct preparation. Kafka activation requires prior durable registration. */
@Component
public class OperationsObservationBootstrap implements ApplicationRunner, EventDeliveryReadiness {
    private static final List<ConsumerRoute> ROUTES = List.of(
        BookingCapacityObservedHandler.ROUTE, PromotionRequestedObservedHandler.ROUTE);
    private final EventRegistrationService registrations;
    private final EventHandlers handlers;
    private final DeliveryExecutionScope scope;
    private final String role;
    private final boolean enabled;
    private final boolean deliveryEnabled;
    private final String transport;
    private volatile boolean ready;

    public OperationsObservationBootstrap(EventRegistrationService registrations, EventHandlers handlers,
        DeliveryExecutionScope scope,
        @Value("${slotq.events.runtime-role:product}") String role,
        @Value("${slotq.operations.observation.enabled:false}") boolean enabled,
        @Value("${slotq.events.delivery.scheduler-enabled:false}") boolean deliveryEnabled,
        @Value("${slotq.events.delivery.transport:DB_DIRECT}") String transport) {
        this.registrations = registrations;
        this.handlers = handlers;
        this.scope = scope;
        this.role = role;
        this.enabled = enabled;
        this.deliveryEnabled = deliveryEnabled;
        this.transport = transport;
    }

    @Override public void run(ApplicationArguments args) { activate(); }

    @Override public String consumerId() { return OperationsEventObservationAdapter.CONSUMER; }
    @Override public boolean isReady() { return ready; }

    public synchronized void activate() {
        ready = false;
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Observer bootstrap must start outside a caller transaction");
        if (!enabled) return;
        if (transport.equals("KAFKA") && !role.equals("consumer"))
            throw new IllegalStateException("Kafka observer activation requires its isolated consumer role");
        if (!role.equals("product") && !role.equals("consumer"))
            throw new IllegalStateException("Observer cannot activate from relay or cutover role");
        if (role.equals("consumer") && (!scope.consumerId().equals(consumerId())
            || !scope.transport().equals(transport)))
            throw new IllegalStateException("Observer execution scope does not match activation");
        if (!deliveryEnabled) throw new IllegalStateException("Enabled observer requires the delivery scheduler");
        requireHandler(BookingCapacityObservedHandler.ROUTE, BookingCapacityObservedHandler.class);
        requireHandler(PromotionRequestedObservedHandler.ROUTE, PromotionRequestedObservedHandler.class);
        Map<ConsumerRoute, UUID> active = inspect();
        if (transport.equals("DB_DIRECT")) {
            for (ConsumerRoute route : ROUTES) {
                if (active.containsKey(route)) continue;
                try { registrations.activate(route); }
                catch (EventRegistrationService.AlreadyActiveException | DataAccessException | TransactionException failure) {
                    try {
                        if (!inspect().containsKey(route)) throw failure;
                    } catch (RuntimeException verificationFailure) {
                        if (verificationFailure != failure) failure.addSuppressed(verificationFailure);
                        throw failure;
                    }
                }
                active = inspect();
            }
        } else if (!transport.equals("KAFKA")) {
            throw new IllegalStateException("Unsupported observer transport");
        }
        if (!inspect().keySet().equals(Set.copyOf(ROUTES)))
            throw new IllegalStateException("Observer registration is absent or incompatible");
        ready = true;
    }

    private void requireHandler(ConsumerRoute route, Class<?> expected) {
        var handler = handlers.resolve(route);
        if (!route.equals(handler.route()) || !expected.equals(AopUtils.getTargetClass(handler)))
            throw new IllegalStateException("Observer handler mismatch");
    }

    private Map<ConsumerRoute, UUID> inspect() {
        var snapshot = registrations.inspect(OperationsEventObservationAdapter.CONSUMER,
            ROUTES.stream().map(ConsumerRoute::eventType).toList());
        Map<ConsumerRoute, UUID> active = new HashMap<>();
        Map<ConsumerRoute, EventRegistration> previous = new HashMap<>();
        Set<Long> boundaries = new HashSet<>();
        for (var row : snapshot.registrations()) {
            if (!ROUTES.contains(row.route()) || row.id() == null || row.activationBoundary() <= 0
                || row.activationBoundary() > snapshot.boundary()
                || (row.deactivationBoundary() != null && (row.deactivationBoundary() <= row.activationBoundary()
                    || row.deactivationBoundary() > snapshot.boundary())))
                throw new IllegalStateException("Incompatible observer registration");
            if (!boundaries.add(row.activationBoundary())
                || (row.deactivationBoundary() != null && !boundaries.add(row.deactivationBoundary())))
                throw new IllegalStateException("Reused observer registration boundary");
            var prior = previous.put(row.route(), row);
            if (prior != null && (prior.active() || prior.deactivationBoundary() >= row.activationBoundary()))
                throw new IllegalStateException("Overlapping observer registration generations");
            if (row.active() && active.put(row.route(), row.id()) != null)
                throw new IllegalStateException("Duplicate active observer registration");
        }
        if (!active.keySet().containsAll(previous.keySet()))
            throw new IllegalStateException("Observer route was deactivated; automatic replacement is unsupported");
        return Map.copyOf(active);
    }
}
