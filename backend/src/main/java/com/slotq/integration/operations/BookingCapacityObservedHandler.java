package com.slotq.integration.operations;

import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.EventHandler;
import com.slotq.events.application.StoredEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BookingCapacityObservedHandler implements EventHandler {
    public static final ConsumerRoute ROUTE = new ConsumerRoute(
        OperationsEventObservationAdapter.CONSUMER, "booking.capacity-released", 1);
    private final OperationsEventObservationAdapter adapter;

    BookingCapacityObservedHandler(OperationsEventObservationAdapter adapter) { this.adapter = adapter; }

    @Override public ConsumerRoute route() { return ROUTE; }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void handle(StoredEvent event) { adapter.observe(event, ROUTE); }
}
