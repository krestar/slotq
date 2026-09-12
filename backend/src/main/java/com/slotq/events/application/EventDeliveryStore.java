package com.slotq.events.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Internal DB worker protocol; only discovery/candidate scans cross tenant scopes. No business access. */
public interface EventDeliveryStore {
    void configureTimeouts(DeliveryPolicy policy);

    int materialize(int batchSize);

    List<DeliveryKey> candidates(int batchSize);

    Optional<DeliverySnapshot> lock(DeliveryKey key);

    Instant databaseNow();

    Target target(DeliveryKey key);

    void claim(DeliverySnapshot previous, Instant now, Instant leaseUntil);

    void done(DeliveryClaim claim, Instant now);

    void fail(DeliveryClaim claim, DeliveryFailure failure, Instant now, Instant nextAttemptAt);

    void exhaust(DeliverySnapshot previous, Instant now);

    void replay(DeliverySnapshot previous, String reason, Instant now);

    record Target(StoredEvent event, ConsumerRoute route) { }
}
