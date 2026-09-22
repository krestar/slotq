package com.slotq.integration.waitlist;

import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.EventHandler;
import com.slotq.events.application.StoredEvent;
import com.slotq.waitlist.application.WaitlistPromotionUseCase;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WaitlistPromotionRequestedHandler implements EventHandler {
    public static final ConsumerRoute ROUTE = new ConsumerRoute("waitlist.promotion", "waitlist.promotion-requested", 1);
    private final WaitlistPromotionEventAdapter adapter;
    WaitlistPromotionRequestedHandler(WaitlistPromotionEventAdapter adapter) { this.adapter = adapter; }
    @Override public ConsumerRoute route() { return ROUTE; }
    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void handle(StoredEvent event) {
        adapter.handle(event, ROUTE, WaitlistPromotionUseCase.Signal.PROMOTION_REQUESTED);
    }
}
