package com.slotq.events.application;

/** Exact durable route; lifecycle service and runtime registry validate it at their boundaries. */
public record ConsumerRoute(String consumerId, String eventType, int schemaVersion) {
}
