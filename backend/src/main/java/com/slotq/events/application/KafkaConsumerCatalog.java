package com.slotq.events.application;

import java.util.List;

/** Deployment-owned logical identities; process instances and registration generations are not consumers. */
public interface KafkaConsumerCatalog {
    List<ConsumerDefinition> consumers();

    default ConsumerDefinition definition(String consumerId) {
        return consumers().stream().filter(value -> value.consumerId().equals(consumerId))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown logical Kafka consumer"));
    }

    record ConsumerDefinition(String consumerId, String groupId, List<ConsumerRoute> routes) { }
}
