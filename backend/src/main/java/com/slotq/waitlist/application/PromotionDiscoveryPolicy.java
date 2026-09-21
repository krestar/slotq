package com.slotq.waitlist.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record PromotionDiscoveryPolicy(boolean enabled, int batchSize, int transactionTimeoutSeconds) {
    public PromotionDiscoveryPolicy(
        @Value("${slotq.waitlist.promotion.enabled:false}") boolean enabled,
        @Value("${slotq.waitlist.promotion.discovery-batch-size:32}") int batchSize,
        @Value("${slotq.waitlist.promotion.request-timeout-seconds:5}") int transactionTimeoutSeconds
    ) {
        if (batchSize < 1 || batchSize > 1000 || transactionTimeoutSeconds < 1 || transactionTimeoutSeconds > 30) {
            throw new IllegalArgumentException("Promotion discovery bounds are invalid");
        }
        this.enabled = enabled;
        this.batchSize = batchSize;
        this.transactionTimeoutSeconds = transactionTimeoutSeconds;
    }
}
