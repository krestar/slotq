package com.slotq.events.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** A runtime owns one stable logical consumer under one durable transport authority. */
@Component
public record DeliveryExecutionScope(String consumerId, String transport, long authorityEpoch) {
    public DeliveryExecutionScope {
        EventCanonicalizer.requireIdentifier(consumerId, "consumerId");
        if (!"DB_DIRECT".equals(transport) && !"KAFKA".equals(transport)) {
            throw new IllegalArgumentException("Unsupported delivery transport");
        }
        if (authorityEpoch < 1) throw new IllegalArgumentException("Invalid delivery authority epoch");
    }

    @Autowired
    public DeliveryExecutionScope(KafkaPublicationFamily family,
        @Value("${slotq.events.delivery.consumer-id:}") String configuredConsumerId,
        @Value("${slotq.events.delivery.transport:DB_DIRECT}") String transport,
        @Value("${slotq.events.delivery.authority-epoch:1}") long authorityEpoch) {
        this(configuredConsumerId == null || configuredConsumerId.isBlank()
            ? family.consumerId() : configuredConsumerId, transport, authorityEpoch);
    }
}
