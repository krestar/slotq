package com.slotq.integration.waitlist;

import com.slotq.booking.application.CapacityReleaseReadiness;
import com.slotq.events.application.EventDeliveryReadiness;
import org.springframework.stereotype.Component;

/** One process-local publication point, initially closed. Append still verifies its durable fence. */
@Component
public final class WaitlistPromotionReadiness implements CapacityReleaseReadiness, EventDeliveryReadiness {
    private volatile boolean ready;
    @Override public boolean isReady() { return ready; }
    void close() { ready = false; }
    void open() { ready = true; }
}
