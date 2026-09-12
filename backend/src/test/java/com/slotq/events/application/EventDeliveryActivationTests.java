package com.slotq.events.application;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class EventDeliveryActivationTests {
    private final EventDeliveryWorker worker = mock(EventDeliveryWorker.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withInitializer(application -> application.getBeanFactory()
            .setConversionService(ApplicationConversionService.getSharedInstance()))
        .withBean(EventDeliveryWorker.class, () -> worker)
        .withUserConfiguration(Scheduling.class, EventDeliveryScheduler.class);

    @Test
    void explicitEventPropertyEnablesThinTriggerOfTheSameCoreCycle() {
        CountDownLatch called = new CountDownLatch(1);
        doAnswer(invocation -> { called.countDown(); return 0; }).when(worker).runCycle();
        context.withPropertyValues("slotq.events.delivery.scheduler-enabled=true",
                "slotq.events.delivery.poll-interval=PT0.05S")
            .run(application -> {
                assertThat(application).hasNotFailed().hasSingleBean(EventDeliveryScheduler.class);
                assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
            });
    }

    @Test
    void disabledByDefaultAndInvalidEnabledIntervalFailsFast() {
        context.run(application -> {
            assertThat(application).hasNotFailed().doesNotHaveBean(EventDeliveryScheduler.class);
            verifyNoInteractions(worker);
        });
        context.withPropertyValues("slotq.events.delivery.scheduler-enabled=true",
                "slotq.events.delivery.poll-interval=PT0S")
            .run(application -> assertThat(application).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling { }
}
