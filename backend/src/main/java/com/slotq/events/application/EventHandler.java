package com.slotq.events.application;

/**
 * Participates synchronously in the caller's Product transaction. Recheck authoritative tenant
 * and business state, then persist effect and any (consumerId,eventId) receipt in that transaction.
 * No REQUIRES_NEW, autocommit, asynchronous, external, or unbounded blocking effects are allowed.
 * Handler removal requires draining old targets or an explicit compatibility/migration contract.
 */
public interface EventHandler {
    ConsumerRoute route();

    void handle(StoredEvent event);
}
