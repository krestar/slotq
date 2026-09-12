package com.slotq.events.application;

import java.util.NoSuchElementException;
import java.util.Objects;

import com.slotq.auth.domain.SystemPrincipal;
import org.springframework.stereotype.Service;

/** Trusted in-process recovery only. SystemPrincipal is never human operator identity. */
@Service
public final class EventReplayService {
    private final EventDeliveryStore store;
    private final DeliveryTransactions transactions;

    public EventReplayService(EventDeliveryStore store, DeliveryTransactions transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    public void replay(SystemPrincipal authority, DeliveryKey key, String reason) {
        Objects.requireNonNull(authority, "Trusted internal recovery authority is required");
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("Replay requires a nonblank reason of at most 500 characters");
        }
        transactions.execute(() -> {
            DeliverySnapshot delivery = store.lock(key).orElseThrow(NoSuchElementException::new);
            var now = store.databaseNow();
            if (delivery.state() != DeliverySnapshot.State.DEAD) {
                throw new IllegalStateException("Only DEAD deliveries may be replayed");
            }
            store.replay(delivery, reason, now);
            return null;
        });
    }
}
