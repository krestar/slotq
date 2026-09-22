package com.slotq.integration.waitlist;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record WaitlistMaintenancePolicy(boolean enabled, int batchSize, int transactionTimeoutSeconds) {
    public WaitlistMaintenancePolicy(
        @Value("${slotq.waitlist.promotion.maintenance-enabled:false}") boolean enabled,
        @Value("${slotq.waitlist.promotion.maintenance-batch-size:32}") int batchSize,
        @Value("${slotq.waitlist.promotion.maintenance-timeout-seconds:5}") int transactionTimeoutSeconds
    ) {
        if (batchSize < 1 || batchSize > 1000 || transactionTimeoutSeconds < 1 || transactionTimeoutSeconds > 30) {
            throw new IllegalArgumentException("Invalid maintenance bounds");
        }
        this.enabled = enabled; this.batchSize = batchSize;
        this.transactionTimeoutSeconds = transactionTimeoutSeconds;
    }
}
