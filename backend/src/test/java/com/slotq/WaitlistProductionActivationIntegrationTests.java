package com.slotq;

import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.*;
import com.slotq.booking.application.*;
import com.slotq.booking.domain.*;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.integration.waitlist.*;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import com.slotq.waitlist.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

/** Real ApplicationRunner + real Spring periodic callbacks + production handlers, not manual worker calls. */
@Testcontainers
@SpringBootTest(properties={"slotq.waitlist.promotion.enabled=true", "slotq.waitlist.promotion.maintenance-enabled=true",
    "slotq.events.delivery.scheduler-enabled=true", "slotq.events.delivery.poll-interval=PT0.05S",
    "slotq.waitlist.promotion.maintenance-interval=PT0.05S", "slotq.waitlist.promotion.maintenance-batch-size=2",
    "slotq.waitlist.promotion.discovery-batch-size=2"})
@Import(WaitlistProductionActivationIntegrationTests.Configuration.class)
class WaitlistProductionActivationIntegrationTests {
    @AfterAll static void stopBeforeContainer(@Autowired org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler) {
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.shutdown();
    }
    static final Instant NOW=Instant.parse("2026-08-30T09:00:00Z"), START=Instant.parse("2026-08-30T11:00:00Z");
    @Container @ServiceConnection static final org.testcontainers.mysql.MySQLContainer MYSQL=
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_activation_live");
    @Autowired WaitlistPromotionReadiness readiness;
    @Autowired EventRegistrationService registrations;
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired AccessControlProvisioning access;
    @Autowired ReservationUseCase booking;
    @Autowired WaitlistUseCase waitlist;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @Test void startupThenPeriodicReleasePromotionAndMaintenanceConvergeThroughActualProductionWiring() {
        assertThat(readiness.isReady()).isTrue();
        var initial=registrations.inspect("waitlist.promotion",List.of(BookingCapacityReleasedHandler.ROUTE.eventType(),WaitlistPromotionRequestedHandler.ROUTE.eventType()));
        assertThat(initial.registrations()).hasSize(2).allMatch(row->row.active());
        var tenant=tenants.createTenant();
        var venue=venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(),"Scheduled","UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(14,0)))),new BookingPolicyTerms(30,5,20,10)));
        var resource=resources.createResource(new ResourceUseCase.CreateResource(tenant.id(),venue.id(),"Table",4));
        var slot=slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(),venue.id(),resource.id(),START.toString()));
        var customer=customer();var hold=booking.createHold(new ReservationUseCase.CreateHold(venue.id(),slot.id(),customer,2)).reservation();
        UUID first=register(venue,slot);clock.now.set(NOW.plusSeconds(1));UUID next=register(venue,slot);
        booking.transition(venue.id(),hold.id(),customer,ReservationCommand.CANCEL);
        await(() -> count("waitlist_offers","state='PENDING'")==1 && count("event_deliveries","state='DONE'")>=1);
        assertThat(state("waitlist_entries",first)).isEqualTo("OFFERED");
        assertThat(count("waitlist_notification_requests","1=1")).isEqualTo(1);
        assertThat(count("capacity_allocations","active=TRUE")).isEqualTo(1);
        clock.now.set(NOW.plusSeconds(301));
        await(() -> state("waitlist_entries",first).equals("EXPIRED") && state("waitlist_entries",next).equals("OFFERED"));
        assertThat(count("waitlist_offers","1=1")).isEqualTo(2);
        assertThat(count("waitlist_notification_requests","1=1")).isEqualTo(2);
        assertThat(count("capacity_allocations","active=TRUE")).isEqualTo(1);
        UUID waiting=register(venue,slot);clock.now.set(START);
        await(() -> state("waitlist_entries",waiting).equals("EXPIRED") && count("waitlist_offers","state='PENDING'")==0
            && count("reservations","state='HELD'")==0 && count("event_deliveries","state<>'DONE'")==0
            && count("event_deliveries","1=1")==count("event_records","1=1")
            && count("waitlist_promotion_receipts","1=1")==count("event_records","1=1"));
        assertThat(count("waitlist_offers","1=1")).isEqualTo(2);
        assertThat(count("capacity_allocations","active=TRUE")).isZero();
        assertThat(count("event_records","event_type='booking.capacity-released'")).isEqualTo(3);
        assertThat(registrations.inspect("waitlist.promotion",List.of(BookingCapacityReleasedHandler.ROUTE.eventType(),WaitlistPromotionRequestedHandler.ROUTE.eventType())).registrations())
            .isEqualTo(initial.registrations());
    }
    private AuthenticatedPrincipal customer(){var p=new AuthenticatedPrincipal(PrincipalId.newId());access.registerPrincipal(p.principalId());return p;}
    private UUID register(Venue venue,SlotInventory slot){return waitlist.register(new WaitlistUseCase.CreateRegistration(venue.id(),slot.id(),2,new WaitlistRegistrationKey(UUID.randomUUID()),customer())).entry().id();}
    private long count(String table,String where){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+where,Long.class);}
    private String state(String table,UUID id){return jdbc.queryForObject("SELECT state FROM "+table+" WHERE id=?",String.class,ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());}
    private void await(BooleanSupplier done){long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!done.getAsBoolean()&&System.nanoTime()<deadline)LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));assertThat(done.getAsBoolean()).as("real scheduled workload converges").isTrue();}
    @TestConfiguration static class Configuration {@Bean @Primary MutableClock liveActivationClock(){return new MutableClock();}}
    static class MutableClock extends Clock {final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        public Instant instant(){return now.get();}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}}
}
