package com.slotq.booking.domain;

public record PartySize(int value) {

    public PartySize {
        if (value < 1) {
            throw new IllegalArgumentException("partySize must be positive");
        }
    }
}
