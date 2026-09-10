package com.slotq.events.application;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionTimedOutException;

/** Stable, deliberately PII-free diagnostics. Raw handler/SQL messages are never persisted. */
public enum DeliveryFailure {
    TRANSIENT_HANDLER(true),
    DB_LOCK_TRANSIENT(true),
    DB_RESOURCE_TRANSIENT(true),
    EFFECT_TIMEOUT(true),
    TARGET_HANDLER_MISSING(false),
    UNSUPPORTED_VERSION(false),
    TARGET_ROUTE_CORRUPTION(false),
    PAYLOAD_INVALID(false),
    TENANT_MISMATCH(false),
    IDENTITY_CORRUPTION(false),
    UNCLASSIFIED_FAILURE(false),
    CRASH_EXHAUSTED(false);

    private final boolean retryable;

    DeliveryFailure(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public static DeliveryFailure classify(RuntimeException failure) {
        if (failure instanceof EventHandlingException known) {
            return known.failure();
        }
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
            if (cause instanceof PessimisticLockingFailureException
                || cause instanceof jakarta.persistence.LockTimeoutException
                || cause instanceof jakarta.persistence.PessimisticLockException) {
                return DB_LOCK_TRANSIENT;
            }
            if (cause instanceof QueryTimeoutException || cause instanceof TransactionTimedOutException
                || cause instanceof jakarta.persistence.QueryTimeoutException) {
                return EFFECT_TIMEOUT;
            }
            if (cause instanceof TransientDataAccessResourceException
                || cause instanceof java.sql.SQLTransientConnectionException
                || cause instanceof java.sql.SQLRecoverableException) {
                return DB_RESOURCE_TRANSIENT;
            }
            if (cause instanceof java.sql.SQLException sql
                && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205)) {
                return DB_LOCK_TRANSIENT;
            }
        }
        return UNCLASSIFIED_FAILURE;
    }
}
