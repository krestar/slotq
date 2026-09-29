package com.slotq.events.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Explicit one-shot command for a stopped, maintenance-window deployment. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(name = "slotq.events.cutover.to")
public final class EventTransportCutoverCommand implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(EventTransportCutoverCommand.class);
    private final EventTransportCutover cutover;
    private final String role;
    private final String to;
    private final int batchSize;

    public EventTransportCutoverCommand(EventTransportCutover cutover,
        @Value("${slotq.events.runtime-role:product}") String role,
        @Value("${slotq.events.cutover.to}") String to,
        @Value("${slotq.events.cutover.batch-size:100}") int batchSize) {
        this.cutover = cutover;
        this.role = role;
        this.to = to;
        this.batchSize = batchSize;
    }

    @Override public void run(ApplicationArguments args) {
        if (!role.equals("cutover")) throw new IllegalStateException("Cutover requires the isolated cutover role");
        var result = cutover.complete(to, batchSize);
        var inventory = cutover.inventory();
        if (inventory.missingTargets() != 0 || !result.phase().equals("READY")) {
            throw new IllegalStateException("Cutover inventory is incomplete");
        }
        log.info("operation=event_transport_cutover outcome=ready transport={} epoch={} boundary={} states={}",
            result.toTransport(), result.authorityEpoch(), result.fenceBoundary(), inventory.deliveryStates());
    }
}
