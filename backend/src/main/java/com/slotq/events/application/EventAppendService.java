package com.slotq.events.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Joins the caller's writable Product transaction. Producers must not use an independent
 * REQUIRES_NEW, autocommit or asynchronous append; M4 verifies the real business JPA join.
 */
@Service
public class EventAppendService {

    private final EventRecordStore store;
    private final EventCanonicalizer canonicalizer;
    private final TransactionTemplate appendTransaction;

    public EventAppendService(
        EventRecordStore store, EventCanonicalizer canonicalizer, PlatformTransactionManager transactionManager
    ) {
        this.store = store;
        this.canonicalizer = canonicalizer;
        appendTransaction = new TransactionTemplate(transactionManager);
        appendTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    public StoredEvent append(EventEnvelope input) {
        // Validation belongs inside MANDATORY so even a caught failure marks the caller rollback-only.
        return appendTransaction.execute(status -> {
            if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new IllegalStateException("event append requires a writable caller transaction");
            }
            EventEnvelope event = canonicalizer.canonicalize(input);
            long boundary = store.lockBoundary();
            var existing = store.findEventForAppend(event.eventId());
            if (existing.isPresent()) {
                StoredEvent stored = existing.orElseThrow();
                if (!stored.envelope().equals(event)) {
                    throw new IllegalArgumentException("IDENTITY_CORRUPTION");
                }
                return stored;
            }
            long nextBoundary = Math.incrementExact(boundary);
            store.setBoundary(nextBoundary);
            return store.insertEvent(event, nextBoundary);
        });
    }
}
