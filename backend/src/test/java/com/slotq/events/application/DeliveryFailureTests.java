package com.slotq.events.application;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DeliveryFailureTests {
    @Test
    void directAndWrappedPersistenceFailuresAreClassifiedWithoutRetryingArbitraryProgrammingErrors() {
        assertThat(DeliveryFailure.classify(new jakarta.persistence.LockTimeoutException()))
            .isEqualTo(DeliveryFailure.DB_LOCK_TRANSIENT);
        assertThat(DeliveryFailure.classify(new jakarta.persistence.PessimisticLockException()))
            .isEqualTo(DeliveryFailure.DB_LOCK_TRANSIENT);
        assertThat(DeliveryFailure.classify(new jakarta.persistence.QueryTimeoutException()))
            .isEqualTo(DeliveryFailure.EFFECT_TIMEOUT);
        assertThat(DeliveryFailure.classify(new RuntimeException(new SQLException("deadlock", "40001", 1213))))
            .isEqualTo(DeliveryFailure.DB_LOCK_TRANSIENT);
        assertThat(DeliveryFailure.classify(new RuntimeException(new SQLTransientConnectionException())))
            .isEqualTo(DeliveryFailure.DB_RESOURCE_TRANSIENT);
        assertThat(DeliveryFailure.classify(new TransientDataAccessResourceException("temporary resource")))
            .isEqualTo(DeliveryFailure.DB_RESOURCE_TRANSIENT);
        assertThat(DeliveryFailure.classify(new DataIntegrityViolationException("constraint")))
            .isEqualTo(DeliveryFailure.UNCLASSIFIED_FAILURE);
        assertThat(DeliveryFailure.classify(new IllegalStateException("programming")))
            .isEqualTo(DeliveryFailure.UNCLASSIFIED_FAILURE);
    }

    @Test
    void causeCycleCannotTrapFailureClassificationInAnInfiniteLoop() {
        RuntimeException first = new RuntimeException();
        RuntimeException second = new RuntimeException(first);
        first.initCause(second);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
            assertThat(DeliveryFailure.classify(first)).isEqualTo(DeliveryFailure.UNCLASSIFIED_FAILURE));
    }
}
