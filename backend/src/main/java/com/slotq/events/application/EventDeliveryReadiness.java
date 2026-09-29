package com.slotq.events.application;

/** Deployment supplies activation readiness; the foundation knows no business route or type. */
public interface EventDeliveryReadiness {
    String consumerId();
    boolean isReady();
}
