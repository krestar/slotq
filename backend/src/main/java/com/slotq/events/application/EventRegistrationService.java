package com.slotq.events.application;

import java.util.Objects;
import java.util.UUID;
import java.util.List;

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
                throw new AlreadyActiveException();
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

    /** Fresh, fenced metadata only. Must not inherit a business transaction or its RR snapshot. */
    public Snapshot inspect(String consumerId, List<String> eventTypes) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Registration inspection must start outside a caller transaction");
        }
        EventCanonicalizer.requireIdentifier(consumerId, "consumerId");
        var types = List.copyOf(eventTypes);
        if (types.isEmpty() || types.size() > 100) throw new IllegalArgumentException("Invalid event type scope");
        types.forEach(type -> EventCanonicalizer.requireIdentifier(type, "eventType"));
        return transaction.execute(status -> {
            requireWritableTransaction();
            long boundary = store.lockBoundary();
            return new Snapshot(boundary, store.registrationsFor(consumerId, types));
        });
    }

    public record Snapshot(long boundary, List<EventRegistration> registrations) {
        public Snapshot { registrations = List.copyOf(registrations); }
    }
    public static final class AlreadyActiveException extends IllegalStateException {
        public AlreadyActiveException() { super("consumer route already has an active registration"); }
    }

    private void requireWritableTransaction() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("consumer cutover requires a writable transaction");
        }
    }
}
