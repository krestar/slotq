package com.slotq.events.application;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable registration changes do not register, remove or discover runtime handler beans. */
@Service
public class EventRegistrationService {

    private final EventRecordStore store;
    private final TransactionTemplate transaction;

    public EventRegistrationService(EventRecordStore store, PlatformTransactionManager transactionManager) {
        this.store = store;
        transaction = new TransactionTemplate(transactionManager);
    }

    public UUID activate(ConsumerRoute route) {
        return transaction.execute(status -> {
            requireWritableTransaction();
            Objects.requireNonNull(route, "consumer route must not be null");
            EventCanonicalizer.requireIdentifier(route.consumerId(), "consumerId");
            EventCanonicalizer.requireIdentifier(route.eventType(), "eventType");
            if (route.schemaVersion() <= 0) {
                throw new IllegalArgumentException("schemaVersion must be positive");
            }
            long boundary = store.lockBoundary();
            if (store.hasActiveRegistration(route)) {
                throw new IllegalStateException("consumer route already has an active registration");
            }
            long nextBoundary = Math.incrementExact(boundary);
            UUID registrationId = UUID.randomUUID();
            store.setBoundary(nextBoundary);
            store.insertRegistration(registrationId, route, nextBoundary);
            return registrationId;
        });
    }

    public boolean deactivate(UUID registrationId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            requireWritableTransaction();
            Objects.requireNonNull(registrationId, "registrationId must not be null");
            long boundary = store.lockBoundary();
            if (!store.isRegistrationActive(registrationId)) return false;
            long nextBoundary = Math.incrementExact(boundary);
            store.setBoundary(nextBoundary);
            store.deactivateRegistration(registrationId, nextBoundary);
            return true;
        }));
    }

    private void requireWritableTransaction() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("consumer cutover requires a writable transaction");
        }
    }
}
