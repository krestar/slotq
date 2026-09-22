package com.slotq.integration.waitlist;

import java.nio.ByteBuffer;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.*;
import com.slotq.booking.application.*;
import com.slotq.booking.domain.*;
import com.slotq.events.application.*;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import com.slotq.waitlist.application.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties={"slotq.waitlist.promotion.enabled=true", "slotq.waitlist.promotion.maintenance-enabled=true",
    "slotq.events.delivery.scheduler-enabled=true", "slotq.waitlist.promotion.candidate-time-limit=PT1S"})
@Import(WaitlistActivationIntegrationTests.Configuration.class)
class WaitlistActivationIntegrationTests {
    static final Instant NOW=Instant.parse("2026-08-30T09:00:00Z"), START=Instant.parse("2026-08-30T11:00:00Z");
    static final ConsumerRoute RELEASE=BookingCapacityReleasedHandler.ROUTE, REQUEST=WaitlistPromotionRequestedHandler.ROUTE;
    @Container @ServiceConnection static final org.testcontainers.mysql.MySQLContainer MYSQL=
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_activation")
            .withCommand("--log-bin-trust-function-creators=1");
    // Component failure/race tests control the startup invocation and scheduler ticks, not their implementation.
    // A separate production wiring suite runs the unmodified ApplicationRunner and real periodic schedulers.
    @MockitoBean WaitlistPromotionBootstrap startup;
    @MockitoBean TaskScheduler taskScheduler;
    @MockitoSpyBean EventRegistrationService registrations;
    @Autowired EventHandlers handlers;
    @Autowired BookingCapacityReleasedHandler releaseHandler;
    @Autowired WaitlistPromotionReadiness readiness;
    @Autowired EventDeliveryScheduler delivery;
    @Autowired WaitlistMaintenanceScheduler maintenance;
    @Autowired EventAppendService append;
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired ReservationUseCase booking;
    @Autowired WaitlistUseCase waitlist;
    @Autowired AccessControlProvisioning access;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    @BeforeEach void isolateFixture() {
        readiness.close(); clock.now.set(NOW);
        // Reset only this disposable Testcontainers foundation fixture, not a runtime recovery strategy.
        jdbc.update("DELETE FROM event_replay_audit");
        jdbc.update("DELETE FROM event_deliveries");
        jdbc.update("DELETE FROM event_records");
        jdbc.update("DELETE FROM event_registrations");
        jdbc.update("UPDATE event_discovery SET boundary_sequence=0 WHERE singleton_id=1");
        jdbc.update("UPDATE event_boundary SET sequence_value=0 WHERE singleton_id=1");
        jdbc.update("UPDATE tenants SET status='INACTIVE'");
    }
    @AfterEach void closeGate() { readiness.close(); jdbc.execute("DROP TRIGGER IF EXISTS fail_activation"); }

    @Test void firstActivationAndComponentRestartPreserveIdentityAndBoundary() {
        bootstrap().activate();
        var first=snapshot(); assertThat(first.registrations()).hasSize(2).allMatch(EventRegistration::active);
        assertThat(readiness.isReady()).isTrue(); assertThat(first.boundary()).isEqualTo(2);
        var restarted=new WaitlistPromotionReadiness(); bootstrap(registrations,handlers,restarted,true,true,true).activate();
        assertThat(restarted.isReady()).isTrue(); assertThat(snapshot()).isEqualTo(first);
    }

    @Test void validClosedHistoryDoesNotReplaceTheExistingActiveGeneration() {
        var historical=registrations.activate(RELEASE);registrations.deactivate(historical);
        var current=registrations.activate(RELEASE);registrations.activate(REQUEST);var before=snapshot();
        bootstrap().activate();
        assertThat(readiness.isReady()).isTrue();assertThat(active(RELEASE)).isEqualTo(current);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void multiInstanceLoserVerifiesFreshDurableGenerationInsteadOfCreatingAnother() throws Exception {
        var entered=new CountDownLatch(2);var proceed=new CountDownLatch(1);var losers=new AtomicInteger();
        doAnswer(call -> {entered.countDown();await(proceed);try{return call.callRealMethod();}
            catch(EventRegistrationService.AlreadyActiveException failure){losers.incrementAndGet();throw failure;}})
            .when(registrations).activate(RELEASE);
        var other=new WaitlistPromotionReadiness();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> bootstrap().activate());
            var b=pool.submit(() -> bootstrap(registrations,handlers,other,true,true,true).activate());
            await(entered);proceed.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertThat(losers.get()).isEqualTo(1);assertThat(readiness.isReady()).isTrue();assertThat(other.isReady()).isTrue();
        assertThat(snapshot().registrations()).hasSize(2);assertThat(snapshot().boundary()).isEqualTo(2);
    }

    @Test void lostActivationResponseIsRecoveredByNewFencedObservationWithoutAnotherGeneration() {
        var committed=new AtomicReference<UUID>();
        doAnswer(call -> {committed.set((UUID)call.callRealMethod());throw new DataAccessResourceFailureException("lost response");})
            .when(registrations).activate(RELEASE);
        bootstrap().activate();
        assertThat(readiness.isReady()).isTrue();assertThat(active(RELEASE)).isEqualTo(committed.get());
        assertThat(snapshot().boundary()).isEqualTo(2);verify(registrations,times(1)).activate(RELEASE);
    }

    @Test void allegedAlreadyActiveWithoutDurableEvidenceFailsClosed() {
        doThrow(new EventRegistrationService.AlreadyActiveException()).when(registrations).activate(RELEASE);
        assertThatThrownBy(() -> bootstrap().activate()).isInstanceOf(EventRegistrationService.AlreadyActiveException.class);
        assertClosedWithoutMutation(fixture());assertThat(snapshot().registrations()).isEmpty();
    }

    @Test void programmingFailureIsNotReclassifiedAsAlreadyActiveEvenAfterInsert() {
        doAnswer(call -> {call.callRealMethod();throw new IllegalArgumentException("programming fault");}).when(registrations).activate(RELEASE);
        assertThatThrownBy(() -> bootstrap().activate()).isInstanceOf(IllegalArgumentException.class);
        assertThat(readiness.isReady()).isFalse();assertThat(snapshot().registrations()).hasSize(1);
    }

    @Test void missingHandlerFailsBeforeAnyRegistrationAndReleaseRollsBack() {
        var missing=new EventHandlers(List.of(releaseHandler));
        assertThatThrownBy(() -> bootstrap(registrations,missing,readiness,true,true,true).activate())
            .isInstanceOf(EventHandlingException.class);
        assertClosedWithoutMutation(fixture());assertThat(snapshot().registrations()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"version","consumer","case","implementation"})
    void incompatibleRuntimeHandlerDoesNotOpenReadiness(String kind) {
        ConsumerRoute route=switch(kind){case "version" -> new ConsumerRoute("waitlist.promotion",REQUEST.eventType(),2);
            case "consumer" -> new ConsumerRoute("wrong.consumer",REQUEST.eventType(),1);
            case "case" -> new ConsumerRoute("waitlist.promotion","Waitlist.promotion-requested",1);default -> REQUEST;};
        EventHandler wrong=new EventHandler(){public ConsumerRoute route(){return route;}public void handle(StoredEvent event){throw new AssertionError("not invoked");}};
        assertThatThrownBy(() -> bootstrap(registrations,new EventHandlers(List.of(releaseHandler,wrong)),readiness,true,true,true).activate())
            .isInstanceOf(RuntimeException.class);
        assertClosedWithoutMutation(fixture());assertThat(snapshot().registrations()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"version","consumer","case","boundary","inactive"})
    void incompatibleOrCorruptDurableMetadataIsNotRepairedByBootstrap(String kind) {
        ConsumerRoute route=switch(kind){case "version" -> new ConsumerRoute("waitlist.promotion",RELEASE.eventType(),2);
            case "consumer" -> new ConsumerRoute("wrong.consumer",RELEASE.eventType(),1);
            case "case" -> new ConsumerRoute("waitlist.promotion","Booking.capacity-released",1);default -> RELEASE;};
        UUID id=registrations.activate(route);
        if(kind.equals("boundary"))jdbc.update("UPDATE event_registrations SET activation_boundary=100 WHERE registration_id=?",bytes(id));
        if(kind.equals("inactive"))registrations.deactivate(id);
        assertThatThrownBy(() -> bootstrap().activate()).isInstanceOf(IllegalStateException.class);
        assertThat(readiness.isReady()).isFalse();assertThat(count("event_registrations")).isEqualTo(1);
    }

    @Test void secondRouteDbFailureKeepsPartialBootstrapClosedAndRestartReusesFirstIdentity() {
        jdbc.execute("CREATE TRIGGER fail_activation BEFORE INSERT ON event_registrations FOR EACH ROW BEGIN "
            + "IF NEW.event_type='waitlist.promotion-requested' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='activation fault'; END IF; END");
        assertThatThrownBy(() -> bootstrap().activate()).isInstanceOf(RuntimeException.class);
        UUID first=active(RELEASE);assertClosedWithoutMutation(fixture());assertThat(count("event_registrations")).isEqualTo(1);
        jdbc.execute("DROP TRIGGER fail_activation");bootstrap().activate();
        assertThat(readiness.isReady()).isTrue();assertThat(active(RELEASE)).isEqualTo(first);assertThat(count("event_registrations")).isEqualTo(2);
    }

    @Test void reusedGlobalBoundaryAcrossExactRoutesIsCorruptionNotReady() {
        registrations.activate(RELEASE);var request=registrations.activate(REQUEST);
        jdbc.update("UPDATE event_registrations SET activation_boundary=1 WHERE registration_id=?",bytes(request));
        assertThatThrownBy(() -> bootstrap().activate()).isInstanceOf(IllegalStateException.class);
        assertClosedWithoutMutation(fixture());assertThat(count("event_registrations")).isEqualTo(2);
    }

    @Test void ticksBeforeSecondRegistrationCommitCannotExecuteBusinessOrDelivery() throws Exception {
        var f=fixture();var entry=register(f);clock.now.set(NOW.plusSeconds(300));
        var waiting=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        doAnswer(call -> {waiting.countDown();await(proceed);return call.callRealMethod();}).when(registrations).activate(REQUEST);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var started=pool.submit(() -> bootstrap().activate());await(waiting);
            try {assertClosedWithoutMutation(f);assertThat(count("event_registrations")).isEqualTo(1);}
            finally {proceed.countDown();}
            started.get(10,TimeUnit.SECONDS);
        }
        maintenance.tick();delivery.tick();
        assertThat(state("reservations",f.reservation.value())).isEqualTo("EXPIRED");
        assertThat(state("waitlist_entries",entry)).isEqualTo("OFFERED");
        assertThat(scalar("SELECT COUNT(*) FROM waitlist_notification_requests WHERE tenant_id=?",bytes(f.tenant.id().value()))).isEqualTo(1);
    }

    @Test void disabledRestartDoesNotInspectOrChangeExistingDurableRegistrations() {
        bootstrap().activate();var before=snapshot();clearInvocations(registrations);
        bootstrap(registrations,handlers,readiness,false,true,true).activate();
        assertThat(readiness.isReady()).isFalse();verifyNoInteractions(registrations);
        delivery.tick();maintenance.tick();assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void enabledConfigurationCannotOmitEitherScheduler(boolean deliveryEnabled) {
        assertThatThrownBy(() -> bootstrap(registrations,handlers,readiness,true,deliveryEnabled,!deliveryEnabled).activate())
            .isInstanceOf(IllegalStateException.class);
        assertThat(readiness.isReady()).isFalse();assertThat(count("event_registrations")).isZero();
    }

    @Test void historicalEventBeforeFirstRegistrationIsNeverBackfilled() {
        var f=fixture();var old=EventId.newId();
        new TransactionTemplate(manager).executeWithoutResult(status -> append.append(new EventEnvelope(old,f.tenant.id(),"SlotInventory",
            f.slot.id().value(),REQUEST.eventType(),1,NOW,"{\"venueId\":\""+f.venue.id().value()+"\",\"resourceId\":\""+f.slot.resourceId().value()
                +"\",\"slotInventoryId\":\""+f.slot.id().value()+"\"}")));
        bootstrap().activate();booking.transition(f.venue.id(),f.reservation,f.customer,ReservationCommand.CANCEL);delivery.tick();
        assertThat(scalar("SELECT COUNT(*) FROM event_deliveries WHERE event_id=?",bytes(old.value()))).isZero();
        assertThat(count("event_deliveries")).isEqualTo(1);
    }

    @Test void readyCacheCannotPermitAppendAfterDurableRouteWasRemoved() {
        var f=fixture();bootstrap().activate();registrations.deactivate(active(RELEASE));
        assertThat(readiness.isReady()).isTrue();
        assertThatThrownBy(() -> booking.transition(f.venue.id(),f.reservation,f.customer,ReservationCommand.CANCEL)).isInstanceOf(RuntimeException.class);
        assertThat(state("reservations",f.reservation.value())).isEqualTo("HELD");assertThat(count("event_records")).isZero();
        assertThatThrownBy(() -> bootstrap().activate()).isInstanceOf(IllegalStateException.class);assertThat(readiness.isReady()).isFalse();
    }

    @Test void bootstrapAndBusinessAppendShareOnlyFoundationFenceAndKeepEventTarget() throws Exception {
        var f=fixture();bootstrap().activate();var before=snapshot();var other=new WaitlistPromotionReadiness();
        try(var fence=connection();var slot=connection();var pool=Executors.newFixedThreadPool(2)) {
            fence.setAutoCommit(false);slot.setAutoCommit(false);
            fence.createStatement().executeQuery("SELECT singleton_id FROM event_boundary WHERE singleton_id=1 FOR UPDATE").close();
            try(var q=slot.prepareStatement("SELECT id FROM slot_inventories WHERE id=? FOR UPDATE")){q.setBytes(1,bytes(f.slot.id().value()));q.executeQuery().close();}
            var release=pool.submit(() -> booking.transition(f.venue.id(),f.reservation,f.customer,ReservationCommand.CANCEL));
            var start=pool.submit(() -> bootstrap(registrations,handlers,other,true,true,true).activate());
            awaitFenceWaits(2);
            var lockSets=fenceLockSets();
            assertThat(lockSets).hasSize(2);
            assertThat(lockSets.values()).anySatisfy(locks -> assertThat(locks).allMatch(row -> row.startsWith("event_boundary|")));
            assertThat(lockSets.values()).anySatisfy(locks -> assertThat(locks).anyMatch(row -> row.startsWith("reservations|")));
            assertThat(lockSets.values()).allSatisfy(locks -> assertThat(locks).noneMatch(row -> row.startsWith("slot_inventories|")));
            var evidence=new ArrayList<String>();
            try(var query=fence.createStatement();var row=query.executeQuery("SELECT VERSION(),@@transaction_isolation")) {
                row.next();evidence.add("mysql="+row.getString(1));evidence.add("isolation="+row.getString(2));
            }
            lockSets.forEach((id,locks) -> {evidence.add("connection="+id);evidence.addAll(locks);});
            java.nio.file.Files.write(java.nio.file.Path.of("build/waitlist-activation-fence-locks.txt"),
                evidence);
            fence.commit();release.get(10,TimeUnit.SECONDS);start.get(10,TimeUnit.SECONDS);
        }
        assertThat(snapshot().registrations()).isEqualTo(before.registrations());
        delivery.tick();assertThat(count("event_records")).isEqualTo(1);assertThat(count("event_deliveries")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM event_deliveries WHERE state='DONE'")).isEqualTo(1);
    }

    @Test void activationAndFreshInspectionRejectAnOuterBusinessTransaction() {
        var tx=new TransactionTemplate(manager);
        assertThatThrownBy(() -> tx.execute(status -> {bootstrap().activate();return null;})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tx.execute(status -> snapshot())).isInstanceOf(IllegalStateException.class);
        assertThat(readiness.isReady()).isFalse();assertThat(count("event_registrations")).isZero();
    }

    private void assertClosedWithoutMutation(Fixture f) {
        assertThat(readiness.isReady()).isFalse();long events=count("event_records"),targets=count("event_deliveries");
        delivery.tick();maintenance.tick();
        assertThatThrownBy(() -> booking.transition(f.venue.id(),f.reservation,f.customer,ReservationCommand.CANCEL)).isInstanceOf(RuntimeException.class);
        assertThat(state("reservations",f.reservation.value())).isEqualTo("HELD");
        assertThat(scalar("SELECT COUNT(*) FROM capacity_allocations WHERE reservation_id=? AND active=TRUE",bytes(f.reservation.value()))).isEqualTo(1);
        assertThat(count("event_records")).isEqualTo(events);assertThat(count("event_deliveries")).isEqualTo(targets);
    }
    private WaitlistPromotionBootstrap bootstrap(){return bootstrap(registrations,handlers,readiness,true,true,true);}
    private WaitlistPromotionBootstrap bootstrap(EventRegistrationService service,EventHandlers registry,WaitlistPromotionReadiness gate,boolean enabled,boolean event,boolean maintenance){return new WaitlistPromotionBootstrap(service,registry,gate,enabled,event,maintenance);}
    private EventRegistrationService.Snapshot snapshot(){return registrations.inspect("waitlist.promotion",List.of(RELEASE.eventType(),REQUEST.eventType()));}
    private UUID active(ConsumerRoute route){return snapshot().registrations().stream().filter(r->r.active()&&r.route().equals(route)).findFirst().orElseThrow().id();}
    private Fixture fixture(){var t=tenants.createTenant();var v=venues.createVenue(new VenueConfigurationUseCase.CreateVenue(t.id(),"Activation","UTC",
        new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(14,0)))),new BookingPolicyTerms(30,5,20,10)));
        var r=resources.createResource(new ResourceUseCase.CreateResource(t.id(),v.id(),"Table",4));var s=slots.createSlot(new SlotInventoryUseCase.CreateSlot(t.id(),v.id(),r.id(),START.toString()));
        var p=customer();var reservation=booking.createHold(new ReservationUseCase.CreateHold(v.id(),s.id(),p,2)).reservation().id();return new Fixture(t,v,s,p,reservation);}
    private AuthenticatedPrincipal customer(){var p=new AuthenticatedPrincipal(PrincipalId.newId());access.registerPrincipal(p.principalId());return p;}
    private UUID register(Fixture f){return waitlist.register(new WaitlistUseCase.CreateRegistration(f.venue.id(),f.slot.id(),2,new WaitlistRegistrationKey(UUID.randomUUID()),customer())).entry().id();}
    private long count(String table){return scalar("SELECT COUNT(*) FROM "+table);}
    private long scalar(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    private String state(String table,UUID id){return jdbc.queryForObject("SELECT state FROM "+table+" WHERE id=?",String.class,bytes(id));}
    private java.sql.Connection connection() throws Exception{return DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());}
    private void awaitFenceWaits(int expected) throws Exception {
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var q=c.prepareStatement("""
            SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l
            ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='event_boundary'
            """)){q.setString(1,MYSQL.getDatabaseName());long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){try(var r=q.executeQuery()){r.next();if(r.getLong(1)>=expected)return;}LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));}
            throw new AssertionError("missing fence waits");}
    }
    private Map<Long,List<String>> fenceLockSets() throws Exception {
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var q=c.prepareStatement("""
            SELECT t.PROCESSLIST_ID,l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE,l.LOCK_STATUS,l.LOCK_DATA
            FROM performance_schema.data_locks l JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID
            WHERE l.LOCK_TYPE='RECORD' AND l.THREAD_ID IN (
                SELECT w.REQUESTING_THREAD_ID FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks waited ON waited.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                WHERE waited.OBJECT_SCHEMA=? AND waited.OBJECT_NAME='event_boundary')
            ORDER BY t.PROCESSLIST_ID,l.OBJECT_NAME,l.INDEX_NAME
            """)){q.setString(1,MYSQL.getDatabaseName());Map<Long,List<String>> sets=new LinkedHashMap<>();
            try(var r=q.executeQuery()){while(r.next())sets.computeIfAbsent(r.getLong(1),key -> new ArrayList<>())
                .add(r.getString(2)+"|"+r.getString(3)+"|"+r.getString(4)+"|"+r.getString(5)+"|"+r.getString(6));}return sets;}
    }
    private static byte[] bytes(UUID id){return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    private static void await(CountDownLatch gate){try{if(!gate.await(8,TimeUnit.SECONDS))throw new AssertionError("gate timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    record Fixture(Tenant tenant,Venue venue,SlotInventory slot,AuthenticatedPrincipal customer,ReservationId reservation){}
    @TestConfiguration static class Configuration {@Bean @Primary MutableClock activationClock(){return new MutableClock();}}
    static class MutableClock extends Clock {final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        public Instant instant(){return now.get();}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}}
}
