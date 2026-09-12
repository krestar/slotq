package com.slotq.events.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Internal append/cutover persistence only. Global current reads are limited to the shared
 * fence, immutable event-ID collision detection and exact registration lifecycle operations.
 * Every operation participates in the transaction opened or required by its owning service.
 */
public interface EventRecordStore {

    long lockBoundary();

    void setBoundary(long sequence);

    Optional<StoredEvent> findEventForAppend(EventId eventId);

    StoredEvent insertEvent(EventEnvelope envelope, long boundarySequence);

    boolean hasActiveRegistration(ConsumerRoute route);

    void insertRegistration(UUID registrationId, ConsumerRoute route, long activationBoundary);

    boolean isRegistrationActive(UUID registrationId);

    void deactivateRegistration(UUID registrationId, long deactivationBoundary);
}
