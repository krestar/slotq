package com.slotq.booking.application;

/**
 * Runtime admission for the Booking producer, supplied only by a ready Waitlist integration.
 * There is deliberately no production provider until consumer activation is implemented.
 * Durable route membership is checked separately on every append, under event_boundary.
 */
@FunctionalInterface
public interface CapacityReleaseReadiness {
    boolean isReady();
}
