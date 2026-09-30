package com.slotq.integration.operations.recovery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Recovery admission, not business DONE or a Kafka acknowledgement. */
public record RecoveryResult(UUID operationId, UUID operatorId, String principalReference,
    UUID tenantId, UUID eventId, UUID registrationId, String consumerId, String action, String destination,
    List<String> affectedConsumers, String publicationCause, String reason, String correlationId, String expectedState,
    String priorState, String postState, int priorCycleAttempts, int postCycleAttempts,
    long lifetimeAttempts, long priorFence, long postFence, String transport, long authorityEpoch,
    Instant recordedAt) { }
