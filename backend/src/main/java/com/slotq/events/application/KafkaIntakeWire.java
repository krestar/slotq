package com.slotq.events.application;

import java.util.UUID;

import com.slotq.observability.ProductTelemetry;

/** The integration layer interprets wire content; the DB original remains authoritative. */
public interface KafkaIntakeWire {
    Reference reference(String body);
    void verify(StoredEvent original, ProductTelemetry.Origin origin, String key, String body);

    record Reference(UUID tenantId, UUID eventId) { }
}
