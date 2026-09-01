package com.slotq.booking.application;

public enum ProductError {
    CAPACITY_UNAVAILABLE("Capacity unavailable", "The selected slot has no remaining capacity."),
    PARTY_SIZE_NOT_SUPPORTED("Party size not supported",
        "The selected resource cannot accommodate the requested party size."),
    BOOKING_NOT_ALLOWED("Booking not allowed",
        "A reservation cannot be created for the selected slot."),
    IDEMPOTENCY_KEY_REUSED("Idempotency key reused",
        "The idempotency key was already used for a different HOLD request."),
    HOLD_EXPIRED("Hold expired", "The reservation hold has expired."),
    CANCELLATION_WINDOW_CLOSED("Cancellation window closed",
        "The reservation can no longer be cancelled."),
    RESERVATION_TRANSITION_NOT_ALLOWED("Reservation transition not allowed",
        "The requested reservation state transition is not allowed.");

    private final String title;
    private final String detail;

    ProductError(String title, String detail) {
        this.title = title;
        this.detail = detail;
    }

    public String title() { return title; }
    public String detail() { return detail; }
}
