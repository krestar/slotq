package com.slotq.integration.waitlist;

import java.time.Duration;
import java.util.List;
import com.slotq.auth.domain.SystemPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WaitlistMaintenanceSchedulerTests {
    private final WaitlistMaintenanceRuntime runtime=mock(WaitlistMaintenanceRuntime.class);
    private final WaitlistPromotionReadiness readiness=mock(WaitlistPromotionReadiness.class);
    private final ApplicationContextRunner context=new ApplicationContextRunner()
        .withInitializer(app -> app.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
        .withBean(WaitlistMaintenanceRuntime.class,()->runtime).withBean(WaitlistPromotionReadiness.class,()->readiness)
        .withUserConfiguration(Scheduling.class,WaitlistMaintenanceScheduler.class);

    @Test void actualPeriodicEntryPointOpensOnlyWithReadiness() {
        var invoked = new java.util.concurrent.CountDownLatch(1);
        when(runtime.runCycle(SystemPrincipal.INSTANCE)).thenAnswer(call -> {
            invoked.countDown();
            return new WaitlistMaintenanceRuntime.Cycle(0,0,0,0,List.of());
        });
        context.withPropertyValues("slotq.waitlist.promotion.maintenance-enabled=true", "slotq.waitlist.promotion.maintenance-interval=PT0.05S")
            .run(app -> {
                assertThat(app).hasNotFailed();verify(readiness,timeout(2000).atLeastOnce()).isReady();verifyNoInteractions(runtime);
                when(readiness.isReady()).thenReturn(true);
                // Mockito timeout verification would hold the synchronized runCycle monitor.
                assertThat(invoked.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                verify(runtime,atLeastOnce()).runCycle(SystemPrincipal.INSTANCE);
            });
    }
    @Test void defaultIsDisabledAndBothSchedulingIntervalsAreFinitePositiveMilliseconds() {
        context.run(app -> assertThat(app).hasNotFailed().doesNotHaveBean(WaitlistMaintenanceScheduler.class));
        for(Duration invalid:List.of(Duration.ZERO,Duration.ofNanos(1),Duration.ofSeconds(-1),Duration.ofDays(1).plusMillis(1))) {
            assertThatThrownBy(() -> new WaitlistMaintenanceScheduler(runtime,readiness,invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new com.slotq.events.application.EventDeliveryScheduler(
                mock(com.slotq.events.application.EventDeliveryWorker.class),java.util.Optional.empty(),invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableScheduling static class Scheduling {}
}
