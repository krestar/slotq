package com.slotq.events.application;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Service;

import static com.slotq.events.application.DeliverySnapshot.State.PENDING;
import static com.slotq.events.application.DeliverySnapshot.State.PROCESSING;

@Service
public final class EventDeliveryWorker {
    private final EventDeliveryStore store;
    private final DeliveryTransactions transactions;
    private final DeliveryPolicy policy;
    private final EventHandlers handlers;
    private final EventCanonicalizer canonicalizer;
    private final EntityManagerFactory entityManagerFactory;

    public EventDeliveryWorker(EventDeliveryStore store, DeliveryTransactions transactions,
                               DeliveryPolicy policy, EventHandlers handlers,
                               EventCanonicalizer canonicalizer, EntityManagerFactory entityManagerFactory) {
        this.store = store;
        this.transactions = transactions;
        this.policy = policy;
        this.handlers = handlers;
        this.canonicalizer = canonicalizer;
        this.entityManagerFactory = entityManagerFactory;
    }

    /** Bounded discovery; each candidate is claimed only when the synchronous execution slot is free. */
    public int runCycle() {
        materialize();
        var candidates = transactions.execute(() -> store.candidates(policy.batchSize()));
        int claimed = 0;
        for (DeliveryKey candidate : candidates) {
            Optional<DeliveryClaim> claim = claim(candidate);
            if (claim.isPresent()) {
                claimed++;
                process(claim.get());
            }
        }
        return claimed;
    }

    public int materialize() {
        return transactions.execute(() -> store.materialize(policy.batchSize()));
    }

    /** Exposed internal protocol step for the production crash harness; commit consumes the attempt. */
    public Optional<DeliveryClaim> claim(DeliveryKey key) {
        return transactions.execute(() -> {
            Optional<DeliverySnapshot> found = store.lock(key);
            Instant now = store.databaseNow();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            DeliverySnapshot delivery = found.get();
            boolean due = delivery.state() == PENDING && !delivery.nextAttemptAt().isAfter(now);
            boolean expired = delivery.state() == PROCESSING && !delivery.leaseUntil().isAfter(now);
            if (!due && !expired) {
                return Optional.empty();
            }
            if (delivery.cycleAttempts() >= policy.maxAttempts()) {
                store.exhaust(delivery, now);
                return Optional.empty();
            }
            store.claim(delivery, now, now.plus(policy.lease()));
            return Optional.of(new DeliveryClaim(key, Math.addExact(delivery.fencingToken(), 1)));
        });
    }

    public void process(DeliveryClaim claim) {
        var outcome = transactions.attempt(() -> {
            DeliverySnapshot delivery = store.lock(claim.key()).orElseThrow(EventOwnershipLostException::new);
            requireOwner(delivery, claim, store.databaseNow());
            EventDeliveryStore.Target target = store.target(claim.key());
            validateTarget(claim.key(), target);
            handlers.resolve(target.route()).handle(target.event());
            // Flush JPA effects before the final time check, never as a later implicit commit flush.
            var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            if (entityManager != null) {
                entityManager.flush();
            }
            Instant finalNow = store.databaseNow();
            requireOwner(delivery, claim, finalNow);
            store.done(claim, finalNow);
            return null;
        });
        if (outcome.failure() == null || outcome.failure() instanceof EventOwnershipLostException
            || !outcome.confirmedRollback()) {
            // A commit/rollback with unknown outcome leaves the durable claim for lease recovery.
            return;
        }
        DeliveryFailure failure = DeliveryFailure.classify(outcome.failure());
        transactions.execute(() -> {
            Optional<DeliverySnapshot> delivery = store.lock(claim.key());
            Instant now = store.databaseNow();
            if (delivery.isPresent() && delivery.get().ownedBy(claim, now)) {
                boolean retry = failure.retryable() && delivery.get().cycleAttempts() < policy.maxAttempts();
                Instant next = retry ? now.plus(policy.retryDelay(delivery.get().cycleAttempts())) : null;
                store.fail(claim, failure, now, next);
            }
            return null;
        });
    }

    private void validateTarget(DeliveryKey key, EventDeliveryStore.Target target) {
        EventEnvelope event = target.event().envelope();
        if (!key.tenantId().equals(event.tenantId())) {
            throw new EventHandlingException(DeliveryFailure.TENANT_MISMATCH);
        }
        if (!key.eventId().equals(event.eventId())) {
            throw new EventHandlingException(DeliveryFailure.IDENTITY_CORRUPTION);
        }
        if (!target.route().eventType().equals(event.eventType())
            || target.route().schemaVersion() != event.schemaVersion()) {
            throw new EventHandlingException(DeliveryFailure.TARGET_ROUTE_CORRUPTION);
        }
        try {
            if (!canonicalizer.canonicalize(event).equals(event)) {
                throw new IllegalArgumentException("Noncanonical event record");
            }
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException failure) {
            throw new EventHandlingException(DeliveryFailure.IDENTITY_CORRUPTION);
        }
    }

    private void requireOwner(DeliverySnapshot delivery, DeliveryClaim claim, Instant now) {
        if (!delivery.ownedBy(claim, now)) {
            throw new EventOwnershipLostException();
        }
    }
}
