package com.slotq.events.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.slotq.observability.ProductTelemetry;

/** Durable publication operations; no DB delivery target or business receipt ownership. */
public interface KafkaPublicationLedger {
    int discover(String destination, int batchSize);
    List<Key> candidates(int batchSize);
    Optional<Claim> claim(Key key, KafkaPublicationPolicy policy);
    Publication load(Claim claim);
    void published(Claim claim, int partition, long offset);
    void failed(Claim claim, String code, boolean retryable, KafkaPublicationPolicy policy);
    Inventory inventory();
    void verifyTopic(String destination, String topicId, Map<Integer, Long> logStarts);

    record Key(UUID tenantId, UUID eventId, String destination) { }
    record Claim(Key key, long token, int attempt) { }
    record Publication(StoredEvent event, ProductTelemetry.Origin origin) { }
    record Inventory(long pending, Double oldestSeconds) { }
}
