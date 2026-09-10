package com.slotq.events.application;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short claim/failure transactions and the one effect transaction use the Product manager. */
@Component
public final class DeliveryTransactions {
    private final TransactionTemplate transaction;
    private final EventDeliveryStore store;
    private final DeliveryPolicy policy;

    public DeliveryTransactions(PlatformTransactionManager manager, EventDeliveryStore store,
                                DeliveryPolicy policy) {
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(Math.toIntExact(policy.effectTimeout().toSeconds()));
        this.store = store;
        this.policy = policy;
    }

    public <T> T execute(Supplier<T> action) {
        Outcome<T> outcome = attempt(action);
        if (outcome.failure() != null) {
            throw outcome.failure();
        }
        return outcome.value();
    }

    public <T> Outcome<T> attempt(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Delivery cycles must start outside a caller transaction");
        }
        int[] completion = {TransactionSynchronization.STATUS_UNKNOWN};
        try {
            T value = transaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public int getOrder() {
                        return Integer.MIN_VALUE;
                    }

                    @Override
                    public void suspend() {
                        throw new IllegalStateException("An event effect transaction cannot be suspended");
                    }

                    @Override
                    public void afterCompletion(int state) {
                        completion[0] = state;
                    }
                });
                store.configureTimeouts(policy);
                return action.get();
            });
            return new Outcome<>(value, null, completion[0]);
        } catch (RuntimeException failure) {
            return new Outcome<>(null, failure, completion[0]);
        }
    }

    public record Outcome<T>(T value, RuntimeException failure, int completion) {
        public boolean confirmedRollback() {
            return completion == TransactionSynchronization.STATUS_ROLLED_BACK;
        }
    }
}
