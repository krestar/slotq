package com.slotq.events.application;

/** Deployment supplies activation readiness; the foundation knows no business route or type. */
@FunctionalInterface
public interface EventDeliveryReadiness {
    boolean isReady();
}
