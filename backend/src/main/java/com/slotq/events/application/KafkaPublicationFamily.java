package com.slotq.events.application;

import java.util.List;

import com.slotq.observability.ProductTelemetry;

/** The integration owner selects the approved original routes and maps their wire format. */
public interface KafkaPublicationFamily {
    String consumerId();
    List<ConsumerRoute> routes();
    boolean localConsumerEnabled();
    boolean localMaintenanceEnabled();
    Message encode(StoredEvent event, ProductTelemetry.Origin origin);

    record Message(String key, String body) { }
}
