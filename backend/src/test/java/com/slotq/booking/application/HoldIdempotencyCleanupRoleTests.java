package com.slotq.booking.application;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HoldIdempotencyCleanupRoleTests {
    private final HoldIdempotencyStore store = mock(HoldIdempotencyStore.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withBean(HoldIdempotencyStore.class, () -> store)
        .withBean(HoldIdempotencyPolicy.class, () -> new HoldIdempotencyPolicy(10))
        .withBean(Clock.class, Clock::systemUTC)
        .withUserConfiguration(Scheduling.class, HoldIdempotencyCleanup.class)
        .withPropertyValues("slotq.booking.hold-idempotency.cleanup-interval=PT0.05S");

    @Test void relayDoesNotRegisterOrRunProductCleanup() {
        context.withPropertyValues("slotq.events.runtime-role=relay").run(app -> {
            assertThat(app).hasNotFailed().doesNotHaveBean(HoldIdempotencyCleanup.class);
            Thread.sleep(200);
            verifyNoInteractions(store);
        });
    }

    @Test void productRoleStillRunsScheduledCleanup() {
        when(store.deleteCompletedAtOrBefore(any(), anyInt())).thenReturn(0);
        context.withPropertyValues("slotq.events.runtime-role=product").run(app -> {
            assertThat(app).hasNotFailed().hasSingleBean(HoldIdempotencyCleanup.class);
            verify(store, org.mockito.Mockito.timeout(2000).atLeastOnce())
                .deleteCompletedAtOrBefore(any(), anyInt());
        });
    }

    @Configuration(proxyBeanMethods = false) @EnableScheduling static class Scheduling { }
}
