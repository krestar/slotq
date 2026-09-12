package com.slotq.events.application;

import java.time.Instant;
import java.util.UUID;

import com.slotq.tenancy.domain.TenantId;

/**
 * Producer input, validated only inside the append transaction boundary. The producer owns
 * authoritative tenant derivation and its business schema's PII-free payload contract.
 */
public record EventEnvelope(
    EventId eventId,
    TenantId tenantId,
    String aggregateType,
    UUID aggregateId,
    String eventType,
    int schemaVersion,
    Instant occurredAt,
    String payload
) {
}
