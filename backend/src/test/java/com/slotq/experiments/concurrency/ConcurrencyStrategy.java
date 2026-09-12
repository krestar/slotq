package com.slotq.experiments.concurrency;

enum ConcurrencyStrategy {
    OPTIMISTIC_SLOT_VERSION_BOUNDED_RETRY,
    PESSIMISTIC_WRITE_SLOT;

    static ConcurrencyStrategy parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported strategy: " + value, exception);
        }
    }

    boolean optimistic() {
        return this == OPTIMISTIC_SLOT_VERSION_BOUNDED_RETRY;
    }
}
