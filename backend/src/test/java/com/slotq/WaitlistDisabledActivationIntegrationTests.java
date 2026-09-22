package com.slotq;

import java.time.*;
import java.util.*;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.*;
import com.slotq.booking.application.*;
import com.slotq.events.application.*;
import com.slotq.integration.waitlist.*;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties={"slotq.waitlist.promotion.enabled=false", "slotq.waitlist.promotion.maintenance-enabled=true",
    "slotq.events.delivery.scheduler-enabled=true", "slotq.events.delivery.poll-interval=PT0.05S", "slotq.waitlist.promotion.maintenance-interval=PT0.05S"})
@Import(WaitlistDisabledActivationIntegrationTests.Configuration.class)
class WaitlistDisabledActivationIntegrationTests {
    @AfterAll static void stopBeforeContainer(@Autowired org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler) {
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.shutdown();
    }
    @Container @ServiceConnection static final org.testcontainers.mysql.MySQLContainer MYSQL=
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_activation_disabled");
    @Autowired WaitlistPromotionBootstrap bootstrap;
    @Autowired WaitlistPromotionReadiness readiness;
    @Autowired EventRegistrationService registrations;
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired ReservationUseCase booking;
    @Autowired AccessControlProvisioning access;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean EventDeliveryScheduler delivery;
    @MockitoSpyBean WaitlistMaintenanceScheduler maintenance;
    @MockitoSpyBean EventDeliveryWorker worker;
    @MockitoSpyBean WaitlistMaintenanceRuntime runtime;

    @Test void disabledRunnerKeepsExistingRegistrationsAndOrdinaryBookingButNoScheduledWork() {
        assertThat(readiness.isReady()).isFalse();
        registrations.activate(BookingCapacityReleasedHandler.ROUTE);registrations.activate(WaitlistPromotionRequestedHandler.ROUTE);
        var before=registrations.inspect("waitlist.promotion",List.of(BookingCapacityReleasedHandler.ROUTE.eventType(),WaitlistPromotionRequestedHandler.ROUTE.eventType()));
        bootstrap.run(new DefaultApplicationArguments());
        verify(delivery,timeout(2000).atLeast(2)).tick();verify(maintenance,timeout(2000).atLeast(2)).tick();
        verifyNoInteractions(worker,runtime);
        var t=tenants.createTenant();var v=venues.createVenue(new VenueConfigurationUseCase.CreateVenue(t.id(),"Disabled","UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(14,0)))),new BookingPolicyTerms(30,5,20,10)));
        var r=resources.createResource(new ResourceUseCase.CreateResource(t.id(),v.id(),"Table",4));
        var s=slots.createSlot(new SlotInventoryUseCase.CreateSlot(t.id(),v.id(),r.id(),"2026-08-30T11:00:00Z"));
        var p=new AuthenticatedPrincipal(PrincipalId.newId());access.registerPrincipal(p.principalId());
        var held=booking.createHold(new ReservationUseCase.CreateHold(v.id(),s.id(),p,2)).reservation();
        assertThat(booking.transition(v.id(),held.id(),p,ReservationCommand.CANCEL).reservation().state().name()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_records",Long.class)).isZero();
        assertThat(registrations.inspect("waitlist.promotion",List.of(BookingCapacityReleasedHandler.ROUTE.eventType(),WaitlistPromotionRequestedHandler.ROUTE.eventType())))
            .isEqualTo(before);
    }
    @TestConfiguration static class Configuration {@Bean @Primary Clock disabledActivationClock(){return Clock.fixed(Instant.parse("2026-08-30T09:00:00Z"),ZoneOffset.UTC);}}
}
