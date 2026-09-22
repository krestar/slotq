package com.slotq;

import java.nio.ByteBuffer;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;

import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.*;
import com.slotq.booking.application.*;
import com.slotq.booking.domain.*;
import com.slotq.events.application.*;
import com.slotq.integration.waitlist.*;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import com.slotq.waitlist.application.*;
import com.slotq.waitlist.domain.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = {"slotq.waitlist.promotion.enabled=true",
    "slotq.waitlist.promotion.maintenance-enabled=true", "slotq.waitlist.promotion.maintenance-batch-size=2",
    "slotq.waitlist.promotion.discovery-batch-size=2", "slotq.events.delivery.scheduler-enabled=false"})
@Import(WaitlistMaintenanceIntegrationTests.Configuration.class)
class WaitlistMaintenanceIntegrationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.slotq.integration.waitlist.WaitlistPromotionBootstrap bootstrap;
    static final Instant NOW = Instant.parse("2026-08-30T09:00:00Z"), START = Instant.parse("2026-08-30T11:00:00Z");
    @Container @ServiceConnection static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_maintenance")
            .withCommand("--log-bin-trust-function-creators=1");
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired AccessControlProvisioning access;
    @Autowired ReservationUseCase booking;
    @MockitoSpyBean ReservationExpiryUseCase expiry;
    @Autowired WaitlistUseCase waitlist;
    @Autowired WaitlistOfferUseCase offers;
    @Autowired WaitlistOfferMaintenanceUseCase maintenanceOffers;
    @Autowired WaitlistEntryExpiryUseCase entryExpiry;
    @Autowired WaitlistMaintenanceRuntime runtime;
    @Autowired EventRegistrationService registrations;
    @Autowired EventDeliveryStore deliveries;
    @Autowired EventCanonicalizer canonicalizer;
    @Autowired BookingCapacityReleasedHandler releaseHandler;
    @Autowired WaitlistPromotionRequestedHandler requestHandler;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager manager;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired Readiness readiness;
    @MockitoSpyBean BookingMaintenanceQuery bookingScan;
    @MockitoSpyBean WaitlistMaintenanceQuery waitlistScan;
    @MockitoSpyBean WaitlistEntryRepository entries;
    @MockitoSpyBean WaitlistOfferRepository offerRows;
    @MockitoSpyBean WaitlistMaintenancePolicy policy;
    @MockitoSpyBean PromotionDiscoveryPolicy promotion;
    UUID releaseRegistration, requestRegistration;

    @BeforeEach void prepare() {
        clock.now.set(NOW); readiness.ready.set(true);
        jdbc.update("UPDATE tenants SET status='INACTIVE'");
        releaseRegistration = registrations.activate(BookingCapacityReleasedHandler.ROUTE);
        requestRegistration = registrations.activate(WaitlistPromotionRequestedHandler.ROUTE);
        runtime = freshRuntime();
    }
    @AfterEach void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_maintenance_append");
        reset(bookingScan, waitlistScan, entries, offerRows, policy, promotion, expiry);
        readiness.ready.set(true); clock.now.set(START.plusSeconds(3600));
        // Drain test fixtures through the same named commands, never by deleting durable rows.
        var cleanup = freshRuntime();
        drain(cleanup, () -> scalar("SELECT (SELECT COUNT(*) FROM reservations WHERE state='HELD') + "
            + "(SELECT COUNT(*) FROM waitlist_offers WHERE state='PENDING') + "
            + "(SELECT COUNT(*) FROM waitlist_entries WHERE state='WAITING')") == 0);
        registrations.deactivate(releaseRegistration); registrations.deactivate(requestRegistration);
    }

    @Test void backlogLargerThanBatchConvergesAfterComponentRestartWithoutGetSideEffects() {
        List<Fixture> ordinary = new ArrayList<>(), promotional = new ArrayList<>(), waiting = new ArrayList<>();
        for (int i=0; i<5; i++) {
            var a=fixture(); hold(a); ordinary.add(a);
            var b=fixture(); offer(b, entry(b)); promotional.add(b);
            var c=fixture(); entry(c); waiting.add(c);
        }
        clock.now.set(START);
        var first = runtime.runCycle(SystemPrincipal.INSTANCE);
        bounded(first);
        assertThat(scalar("SELECT COUNT(*) FROM reservations WHERE state='HELD'")).isGreaterThan(0);
        var restarted = freshRuntime();
        drain(restarted, () -> scalar("SELECT COUNT(*) FROM reservations WHERE state='HELD'") == 0
            && scalar("SELECT COUNT(*) FROM waitlist_offers WHERE state='PENDING'") == 0
            && scalar("SELECT COUNT(*) FROM waitlist_entries WHERE state IN ('WAITING','OFFERED')") == 0);
        for (var f : ordinary) { assertThat(releases(f)).isEqualTo(1); assertThat(count("capacity_allocations", f, "active=TRUE")).isZero(); }
        for (var f : promotional) { assertThat(releases(f)).isEqualTo(1); assertThat(count("waitlist_offers",f,"state='EXPIRED'")).isEqualTo(1); }
        for (var f : waiting) { assertThat(count("waitlist_entries",f,"state='EXPIRED'")).isEqualTo(1); assertThat(count("reservations",f,"1=1")).isZero(); }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void pendingBeforeDeadlineConvergesOrdinaryConfirmOrCancel(boolean confirm) {
        var f=fixture(); var entry=entry(f); var offer=offer(f,entry);
        booking.transition(f.venue.id(),offer.reservation().id(),entry.customer,
            confirm ? ReservationCommand.CONFIRM : ReservationCommand.CANCEL);
        assertThat(offer.expiresAt()).isAfter(clock.instant());
        drain(runtime, () -> !state("waitlist_offers",offer.id()).equals("PENDING"));
        assertThat(state("waitlist_offers",offer.id())).isEqualTo(confirm ? "ACCEPTED" : "DECLINED");
        assertThat(state("waitlist_entries",entry.id)).isEqualTo(confirm ? "FULFILLED" : "DECLINED");
        assertThat(releases(f)).isEqualTo(confirm ? 0 : 1);
    }

    @Test void ordinaryExpiryAndOfferReconcileRaceReleaseExactlyOnceWithoutSlotLock() throws Exception {
        var f=fixture(); var o=offer(f,entry(f)); clock.now.set(o.expiresAt());
        try (var gate=connection(); var slotGate=connection(); var pool=Executors.newFixedThreadPool(2)) {
            slotGate.setAutoCommit(false);
            try(var q=slotGate.prepareStatement("SELECT id FROM slot_inventories WHERE id=? FOR UPDATE")) {
                q.setBytes(1,bytes(f.slot.id().value())); q.executeQuery().close();
            }
            gate.setAutoCommit(false);
            try(var q=gate.prepareStatement("SELECT id FROM reservations WHERE id=? FOR UPDATE")) {
                q.setBytes(1,bytes(o.reservation().id().value())); q.executeQuery().close();
            }
            var ordinary=pool.submit(() -> expiry.expire(f.venue.id(),o.reservation().id(),SystemPrincipal.INSTANCE));
            var reconcile=pool.submit(() -> offers.reconcileTarget(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistOfferId(o.id())));
            awaitWait("reservations",2);
            clock.now.set(o.expiresAt().plusSeconds(60));
            gate.commit(); ordinary.get(8,TimeUnit.SECONDS); reconcile.get(8,TimeUnit.SECONDS);
        }
        assertThat(releases(f)).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM event_records WHERE aggregate_id=? AND occurred_at=?",
            bytes(o.reservation().id().value()),java.sql.Timestamp.from(o.expiresAt()))).isEqualTo(1);
        assertThat(state("waitlist_offers",o.id())).isEqualTo("EXPIRED");
        assertThat(count("capacity_allocations",f,"active=TRUE")).isZero();
    }

    @Test void twoMaintenanceComponentsDrainSameDurableBacklog() throws Exception {
        List<Fixture> fixtures=new ArrayList<>();
        for(int i=0;i<7;i++){var f=fixture(); offer(f,entry(f)); fixtures.add(f);}
        clock.now.set(START);
        var other=freshRuntime();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> {for(int i=0;i<8;i++) successful(runtime.runCycle(SystemPrincipal.INSTANCE));});
            var b=pool.submit(() -> {for(int i=0;i<8;i++) successful(other.runCycle(SystemPrincipal.INSTANCE));});
            a.get(30,TimeUnit.SECONDS); b.get(30,TimeUnit.SECONDS);
        }
        drain(freshRuntime(), () -> fixtures.stream().allMatch(f -> count("waitlist_offers",f,"state='PENDING'")==0));
        for(var f:fixtures){assertThat(releases(f)).isEqualTo(1); assertThat(count("waitlist_offers",f,"state='EXPIRED'")).isEqualTo(1);}
    }

    @Test void terminalReconcileAfterRoutingSnapshotUsesFreshTargetTransaction() throws Exception {
        var f=fixture();var o=offer(f,entry(f));clock.now.set(START);
        doReturn(List.of()).when(bookingScan).heldAfter(any(),anyInt());
        var routed=new CountDownLatch(1);var continueTarget=new CountDownLatch(1);var first=new AtomicBoolean(true);
        doAnswer(call -> {
            var result=call.callRealMethod();
            if(first.compareAndSet(true,false)){routed.countDown();await(continueTarget);}
            return result;
        }).when(offerRows).find(f.venue.id(),new WaitlistOfferId(o.id()));
        try(var pool=Executors.newSingleThreadExecutor()) {
            var cycle=pool.submit(() -> runtime.runCycle(SystemPrincipal.INSTANCE));
            await(routed);
            try {
                offers.reconcileTarget(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistOfferId(o.id()));
                assertThat(state("waitlist_offers",o.id())).isEqualTo("EXPIRED");
                assertThat(count("capacity_allocations",f,"active=TRUE")).isZero();
            } finally {continueTarget.countDown();}
            successful(cycle.get(8,TimeUnit.SECONDS));
        }
        assertThat(releases(f)).isEqualTo(1);
    }

    @Test void maintenanceReconcileKeepsTerminalBackingCorruptionGuard() {
        var f=fixture();var o=offer(f,entry(f));clock.now.set(START);
        // Deliberately corrupt a fixture, not a runtime compensation path.
        jdbc.update("UPDATE waitlist_offers SET state='EXPIRED',terminal_reason='HOLD_EXPIRED' WHERE id=?",bytes(o.id()));
        try {
            assertThatThrownBy(() -> maintenanceOffers.reconcileTarget(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistOfferId(o.id())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EXPIRED Offer backing is not released");
            assertThat(state("reservations",o.reservation().id().value())).isEqualTo("HELD");
            assertThat(releases(f)).isZero();
        } finally {
            jdbc.update("UPDATE waitlist_offers SET state='PENDING',terminal_reason=NULL WHERE id=?",bytes(o.id()));
        }
    }

    @Test void maintenanceReconcileAppendFailureRollsBackEntireOfferBackingPair() {
        var f=fixture();var e=entry(f);var o=offer(f,e);clock.now.set(START);
        jdbc.execute("CREATE TRIGGER fail_maintenance_append BEFORE INSERT ON event_records FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='maintenance append fault'");
        assertThatThrownBy(() -> maintenanceOffers.reconcileTarget(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistOfferId(o.id())))
            .isInstanceOf(RuntimeException.class);
        assertThat(state("reservations",o.reservation().id().value())).isEqualTo("HELD");
        assertThat(state("waitlist_offers",o.id())).isEqualTo("PENDING");
        assertThat(state("waitlist_entries",e.id)).isEqualTo("OFFERED");
        assertThat(count("capacity_allocations",f,"active=TRUE")).isEqualTo(1);
        assertThat(releases(f)).isZero();
        jdbc.execute("DROP TRIGGER fail_maintenance_append");
        maintenanceOffers.reconcileTarget(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistOfferId(o.id()));
        assertThat(releases(f)).isEqualTo(1);
        assertThat(state("waitlist_offers",o.id())).isEqualTo("EXPIRED");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void flushedExpiryWaitsForAppendFenceWithoutAcquiringSlot(boolean promotional) throws Exception {
        var f=fixture();
        if(promotional) {
            offer(f,entry(f));
            // Select the reconcile half of the production runtime, independently of ordinary expiry.
            doReturn(List.of()).when(bookingScan).heldAfter(any(),anyInt());
        } else hold(f);
        clock.now.set(START);
        try(var fence=connection();var slotGate=connection();var pool=Executors.newSingleThreadExecutor()) {
            fence.setAutoCommit(false);slotGate.setAutoCommit(false);
            try(var q=fence.prepareStatement("SELECT singleton_id FROM event_boundary WHERE singleton_id=1 FOR UPDATE")){q.executeQuery().close();}
            try(var q=slotGate.prepareStatement("SELECT id FROM slot_inventories WHERE id=? FOR UPDATE")) {
                q.setBytes(1,bytes(f.slot.id().value()));q.executeQuery().close();
            }
            var result=pool.submit(() -> runtime.runCycle(SystemPrincipal.INSTANCE));
            awaitWait("event_boundary",1);
            var locks=recordLocks(waitingConnection("event_boundary"));
            assertThat(locks).anySatisfy(row -> assertThat(row).startsWith("reservations|"));
            assertThat(locks).anySatisfy(row -> assertThat(row).startsWith("capacity_allocations|"));
            assertThat(locks).anySatisfy(row -> assertThat(row).startsWith("event_boundary|").contains("WAITING"));
            assertThat(locks).noneSatisfy(row -> assertThat(row).startsWith("slot_inventories|"));
            if(promotional) {
                assertThat(locks).anySatisfy(row -> assertThat(row).startsWith("waitlist_entries|"));
                assertThat(locks).anySatisfy(row -> assertThat(row).startsWith("waitlist_offers|"));
            } else assertThat(locks).noneSatisfy(row -> assertThat(row).startsWith("waitlist_"));
            java.nio.file.Files.write(java.nio.file.Path.of("build/waitlist-maintenance-"+(promotional ? "offer" : "hold")+"-locks.txt"),locks);
            fence.commit();
            assertThat(result.get(8,TimeUnit.SECONDS).failures()).isEmpty();
            // Slot remains externally locked until after the target commits.
        }
        assertThat(releases(f)).isEqualTo(1);
    }

    @Test void appendFailureRollsBackPairAndLaterTargetsAdvanceBeyondFailedPrefix() {
        List<Fixture> fs=new ArrayList<>(); for(int i=0;i<6;i++){var f=fixture();hold(f);fs.add(f);}
        var rows=bookingScan.heldAfter(null,100);
        UUID broken=rows.getFirst().id().value();
        jdbc.execute("CREATE TRIGGER fail_maintenance_append BEFORE INSERT ON event_records FOR EACH ROW BEGIN "
            + "IF NEW.aggregate_id=UNHEX('"+broken.toString().replace("-","")+"') THEN SIGNAL SQLSTATE '45000' "
            + "SET MESSAGE_TEXT='maintenance fault'; END IF; END");
        clock.now.set(NOW.plusSeconds(300));
        List<WaitlistMaintenanceRuntime.Failure> failures=new ArrayList<>();
        for(int i=0;i<5;i++) failures.addAll(runtime.runCycle(SystemPrincipal.INSTANCE).failures());
        assertThat(failures).anySatisfy(f -> assertThat(f.targetId()).isEqualTo(broken));
        assertThat(state("reservations",broken)).isEqualTo("HELD");
        assertThat(scalar("SELECT COUNT(*) FROM capacity_allocations WHERE reservation_id=? AND active=TRUE",bytes(broken))).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM event_records WHERE aggregate_id=?",bytes(broken))).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM reservations WHERE state='HELD'")).isEqualTo(1);
        jdbc.execute("DROP TRIGGER fail_maintenance_append");
        drain(freshRuntime(), () -> state("reservations",broken).equals("EXPIRED"));
        assertThat(scalar("SELECT COUNT(*) FROM event_records WHERE aggregate_id=?",bytes(broken))).isEqualTo(1);
    }

    @Test void scanNowDoesNotBecomeTargetCommandNowAndNextTargetCapturesAgain() {
        var a=fixture(); var aid=hold(a); var b=fixture(); var bid=hold(b);
        Instant scanNow=NOW.plusSeconds(300), commandNow=scanNow.plusSeconds(7);
        clock.now.set(scanNow);
        doAnswer(call -> {var result=call.callRealMethod();clock.now.set(commandNow);return result;})
            .when(bookingScan).heldAfter(any(),anyInt());
        doAnswer(call -> {var result=call.callRealMethod();clock.now.set(clock.instant().plusSeconds(1));return result;})
            .when(expiry).expire(any(),any(),any());
        drain(runtime, () -> state("reservations",aid.value()).equals("EXPIRED") && state("reservations",bid.value()).equals("EXPIRED"));
        assertThat(jdbc.queryForList("SELECT occurred_at FROM event_records WHERE aggregate_id IN (?,?)",bytes(aid.value()),bytes(bid.value())))
            .hasSize(2);
        assertThat(scalar("SELECT COUNT(*) FROM event_records WHERE aggregate_id IN (?,?) AND occurred_at=?",
            bytes(aid.value()),bytes(bid.value()),java.sql.Timestamp.from(commandNow))).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM event_records WHERE aggregate_id IN (?,?) AND occurred_at=?",
            bytes(aid.value()),bytes(bid.value()),java.sql.Timestamp.from(commandNow.plusSeconds(1)))).isEqualTo(1);
    }

    @Test void waitingExpiryLocksOnlyEntryAndRevalidatesCurrentStateAfterSnapshot() throws Exception {
        var f=fixture(); var e=entry(f); var other=entry(f); clock.now.set(START);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var connectionId=new AtomicLong();
        doAnswer(call -> {var result=call.callRealMethod();
            if (((WaitlistEntry)call.getArgument(1)).id().value().equals(e.id)) {
                connectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
                locked.countDown(); await(release);
            }
            return result;
        }).when(entries).updateState(eq(f.venue.id()),any());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var future=pool.submit(() -> entryExpiry.expireWaiting(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistEntryId(e.id)));
            await(locked);
            try {
                var locks=recordLocks(connectionId.get());
                assertThat(locks).isNotEmpty().allSatisfy(row -> assertThat(row).startsWith("waitlist_entries|"));
                java.nio.file.Files.write(java.nio.file.Path.of("build/waitlist-maintenance-entry-locks.txt"),locks);
                assertThat(entryExpiry.expireWaiting(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistEntryId(other.id))).isTrue();
            } finally { release.countDown(); }
            assertThat(future.get(8,TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("reservations",f,"1=1")).isZero(); assertThat(releases(f)).isZero();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_entries",Long.class);
            assertThat(entryExpiry.expireWaiting(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistEntryId(e.id))).isFalse();
        });
    }

    @Test void waitingExpiryOuterRollbackAndStaleOfferedCandidateCannotExpireAnOffer() {
        var f=fixture(); var e=entry(f); var o=offer(f,e); clock.now.set(START);
        assertThat(entryExpiry.expireWaiting(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistEntryId(e.id))).isFalse();
        assertThat(state("waitlist_entries",e.id)).isEqualTo("OFFERED");
        assertThat(state("waitlist_offers",o.id())).isEqualTo("PENDING");
        clock.now.set(NOW); var waiting=entry(f); clock.now.set(START);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(entryExpiry.expireWaiting(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistEntryId(waiting.id))).isTrue();
            status.setRollbackOnly();
        });
        assertThat(state("waitlist_entries",waiting.id)).isEqualTo("WAITING");
        assertThatThrownBy(() -> entryExpiry.expireWaiting(SystemPrincipal.INSTANCE,VenueId.newId(),new WaitlistEntryId(waiting.id)))
            .isInstanceOf(com.slotq.auth.application.ResourceNotFoundException.class);
    }

    @Test void currentWaitingLockDoesNotOverwriteCancelCommittedAfterEarlyRrSnapshot() throws Exception {
        var f=fixture(); var e=entry(f);
        try(var pool=Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                assertThat(state("waitlist_entries",e.id)).isEqualTo("WAITING");
                try {
                    pool.submit(() -> waitlist.cancel(f.venue.id(),new WaitlistEntryId(e.id),e.customer)).get(8,TimeUnit.SECONDS);
                } catch (Exception failure) { throw new AssertionError(failure); }
                clock.now.set(START);
                assertThat(entryExpiry.expireWaiting(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistEntryId(e.id))).isFalse();
            });
        }
        assertThat(state("waitlist_entries",e.id)).isEqualTo("CANCELLED");
    }

    @Test void notDueOfferPrefixDoesNotStarveLaterTargets() {
        List<WaitlistOfferUseCase.OfferView> created=new ArrayList<>();
        Map<UUID,Fixture> fixtures=new HashMap<>(); Map<UUID,Entry> customers=new HashMap<>();
        for(int i=0;i<6;i++){var f=fixture();var e=entry(f);var o=offer(f,e);created.add(o);fixtures.put(o.id(),f);customers.put(o.id(),e);}
        var ordered=waitlistScan.pendingAfter(null,100);
        // The first full page stays legitimately PENDING. A later ordinary cancellation must still converge.
        var last=ordered.getLast();var f=fixtures.get(last.id().value());
        var o=created.stream().filter(value -> value.id().equals(last.id().value())).findFirst().orElseThrow();
        booking.transition(f.venue.id(),o.reservation().id(),customers.get(o.id()).customer,ReservationCommand.CANCEL);
        drain(runtime, () -> state("waitlist_offers",o.id()).equals("DECLINED"));
        for(var row:ordered.subList(0,2))assertThat(state("waitlist_offers",row.id().value())).isEqualTo("PENDING");
        assertThat(releases(f)).isEqualTo(1);
    }

    @Test void scanFailureIsReportedAndDoesNotBlockOtherBacklogKinds() {
        var f=fixture(); var e=entry(f); clock.now.set(START);
        doThrow(new IllegalStateException("scan unavailable")).when(bookingScan).heldAfter(any(),anyInt());
        var cycle=runtime.runCycle(SystemPrincipal.INSTANCE);
        assertThat(cycle.failures()).anySatisfy(failure -> {
            assertThat(failure.kind()).isEqualTo(WaitlistMaintenanceRuntime.Kind.HOLD);
            assertThat(failure.targetId()).isNull();
        });
        assertThat(state("waitlist_entries",e.id)).isEqualTo("EXPIRED");
    }

    @Test void terminalReservationAfterScanIsReportedWithoutBlockingLaterExpiry() {
        var f=fixture(); var first=hold(f); var nextFixture=fixture(); var next=hold(nextFixture);
        clock.now.set(NOW.plusSeconds(300));
        // The query callback still runs in the read-only scan transaction, so inject the winning
        // command through an independent connection/thread rather than join that observation.
        doAnswer(call -> {
            var page=(List<BookingMaintenanceQuery.Held>)call.callRealMethod();
            if(page.stream().anyMatch(row -> row.id().equals(first))) {
                try(var pool=Executors.newSingleThreadExecutor()) {
                    clock.now.set(NOW);
                    pool.submit(() -> booking.transition(f.venue.id(),first,owner(f,first),ReservationCommand.CANCEL)).get(8,TimeUnit.SECONDS);
                    clock.now.set(NOW.plusSeconds(300));
                }
            }
            return page;
        }).when(bookingScan).heldAfter(any(),anyInt());
        var cycle=runtime.runCycle(SystemPrincipal.INSTANCE);
        assertThat(cycle.failures()).anySatisfy(failure -> assertThat(failure.targetId()).isEqualTo(first.value()));
        assertThat(state("reservations",first.value())).isEqualTo("CANCELLED");
        assertThat(state("reservations",next.value())).isEqualTo("EXPIRED");
        assertThat(releases(f)).isEqualTo(1);assertThat(releases(nextFixture)).isEqualTo(1);
    }

    @Test void boundedTargetLockTimeoutDoesNotRetryOrStarveFollowingTarget() throws Exception {
        hold(fixture());hold(fixture());
        var first=bookingScan.heldAfter(null,2).getFirst();
        clock.now.set(NOW.plusSeconds(300));
        doReturn(2).when(policy).transactionTimeoutSeconds();
        var worker=freshRuntime();
        try(var blocker=connection();var pool=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try(var q=blocker.prepareStatement("SELECT id FROM reservations WHERE id=? FOR UPDATE")) {
                q.setBytes(1,bytes(first.id().value()));q.executeQuery().close();
            }
            var result=pool.submit(() -> worker.runCycle(SystemPrincipal.INSTANCE));
            awaitWait("reservations",1);
            var cycle=result.get(10,TimeUnit.SECONDS);
            assertThat(cycle.failures()).hasSize(1);
            assertThat(cycle.failures().getFirst().targetId()).isEqualTo(first.id().value());
            assertThat(state("reservations",first.id().value())).isEqualTo("HELD");
            assertThat(scalar("SELECT COUNT(*) FROM reservations WHERE state='HELD'")).isEqualTo(1);
            blocker.commit();
        }
        drain(freshRuntime(), () -> state("reservations",first.id().value()).equals("EXPIRED"));
    }

    @Test void maintenanceDiscoversReleaseFreeOpportunityButOnlyM3CreatesHold() {
        var f=fixture(); var e=entry(f);
        drain(runtime, () -> scalar("SELECT COUNT(*) FROM waitlist_promotion_requests WHERE tenant_id=? AND last_event_id IS NOT NULL",bytes(f.tenant.id().value()))==1);
        assertThat(count("reservations",f,"1=1")).isZero();
        UUID event=jdbc.queryForObject("SELECT last_event_id FROM waitlist_promotion_requests WHERE tenant_id=?",(r,n)->uuid(r.getBytes(1)),bytes(f.tenant.id().value()));
        var worker=worker(); worker.materialize();
        var key=new DeliveryKey(f.tenant.id(),new EventId(event),requestRegistration);
        worker.process(worker.claim(key).orElseThrow());
        assertThat(state("waitlist_entries",e.id)).isEqualTo("OFFERED");
        assertThat(count("waitlist_offers",f,"state='PENDING'")).isEqualTo(1);
    }

    @Test void dueOfferReleaseThenM3PromotesNextEntry() {
        var f=fixture();var first=entry(f);var old=offer(f,first);var next=entry(f);
        clock.now.set(old.expiresAt());
        drain(runtime, () -> state("waitlist_offers",old.id()).equals("EXPIRED"));
        UUID event=jdbc.queryForObject("SELECT event_id FROM event_records WHERE aggregate_id=? AND event_type='booking.capacity-released'",
            (r,n)->uuid(r.getBytes(1)),bytes(old.reservation().id().value()));
        var worker=worker();worker.materialize();
        var key=new DeliveryKey(f.tenant.id(),new EventId(event),releaseRegistration);
        worker.process(worker.claim(key).orElseThrow());
        assertThat(state("waitlist_entries",next.id)).isEqualTo("OFFERED");
        assertThat(count("waitlist_offers",f,"1=1")).isEqualTo(2);
        assertThat(count("capacity_allocations",f,"active=TRUE")).isEqualTo(1);
    }

    @Test void disabledNotReadyAndOuterTransactionNeverStartMaintenance() {
        var f=fixture();var id=hold(f);clock.now.set(START);
        doReturn(false).when(policy).enabled();
        assertThat(runtime.runCycle(SystemPrincipal.INSTANCE).heldScanned()).isZero();
        doReturn(true).when(policy).enabled(); readiness.ready.set(false);
        assertThatThrownBy(() -> runtime.runCycle(SystemPrincipal.INSTANCE)).isInstanceOf(IllegalStateException.class);
        readiness.ready.set(true);doReturn(false).when(promotion).enabled();
        assertThat(runtime.runCycle(SystemPrincipal.INSTANCE).heldScanned()).isZero();
        doReturn(true).when(promotion).enabled();
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> runtime.runCycle(SystemPrincipal.INSTANCE)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(state("reservations",id.value())).isEqualTo("HELD");
        assertThat(releases(f)).isZero();
        for(int batch:new int[]{0,1001}) assertThatThrownBy(() -> new WaitlistMaintenancePolicy(true,batch,5)).isInstanceOf(IllegalArgumentException.class);
        for(int timeout:new int[]{0,31}) assertThatThrownBy(() -> new WaitlistMaintenancePolicy(true,2,timeout)).isInstanceOf(IllegalArgumentException.class);
    }

    private WaitlistMaintenanceRuntime freshRuntime(){return context.getAutowireCapableBeanFactory().createBean(WaitlistMaintenanceRuntime.class);}
    private void drain(WaitlistMaintenanceRuntime worker,BooleanSupplier done) {
        for(int i=0;i<100 && !done.getAsBoolean();i++) successful(worker.runCycle(SystemPrincipal.INSTANCE));
        assertThat(done.getAsBoolean()).as("finite backlog converges within bounded sweeps").isTrue();
    }
    private void bounded(WaitlistMaintenanceRuntime.Cycle cycle) {
        assertThat(cycle.heldScanned()).isBetween(0,2);assertThat(cycle.offersScanned()).isBetween(0,2);
        assertThat(cycle.entriesScanned()).isBetween(0,2);assertThat(cycle.slotsScanned()).isBetween(0,2);
    }
    private void successful(WaitlistMaintenanceRuntime.Cycle cycle) {
        bounded(cycle);assertThat(cycle.failures()).isEmpty();
    }
    private Fixture fixture() {
        var tenant=tenants.createTenant();
        var venue=venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(),"Maintenance","UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(14,0)))),
            new BookingPolicyTerms(30,5,20,10)));
        var resource=resources.createResource(new ResourceUseCase.CreateResource(tenant.id(),venue.id(),"Table",4));
        var slot=slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(),venue.id(),resource.id(),START.toString()));
        return new Fixture(tenant,venue,slot);
    }
    private AuthenticatedPrincipal customer(){var p=new AuthenticatedPrincipal(PrincipalId.newId());access.registerPrincipal(p.principalId());return p;}
    private Entry entry(Fixture f){var p=customer();return new Entry(waitlist.register(new WaitlistUseCase.CreateRegistration(f.venue.id(),f.slot.id(),2,new WaitlistRegistrationKey(UUID.randomUUID()),p)).entry().id(),p);}
    private ReservationId hold(Fixture f){return booking.createHold(new ReservationUseCase.CreateHold(f.venue.id(),f.slot.id(),customer(),2)).reservation().id();}
    private AuthenticatedPrincipal owner(Fixture f,ReservationId id){return jdbc.queryForObject("SELECT customer_principal_id FROM reservations WHERE venue_id=? AND id=?",(r,n)->new AuthenticatedPrincipal(new PrincipalId(uuid(r.getBytes(1)))),bytes(f.venue.id().value()),bytes(id.value()));}
    private WaitlistOfferUseCase.OfferView offer(Fixture f,Entry e){return offers.createTarget(SystemPrincipal.INSTANCE,f.venue.id(),new WaitlistEntryId(e.id),f.slot.id()).offer();}
    private long count(String table,Fixture f,String condition){return scalar("SELECT COUNT(*) FROM "+table+" WHERE venue_id=? AND "+condition,bytes(f.venue.id().value()));}
    private long releases(Fixture f){return scalar("SELECT COUNT(*) FROM event_records WHERE tenant_id=? AND event_type='booking.capacity-released'",bytes(f.tenant.id().value()));}
    private long scalar(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    private String state(String table,UUID id){return jdbc.queryForObject("SELECT state FROM "+table+" WHERE id=?",String.class,bytes(id));}
    private EventDeliveryWorker worker(){var p=new DeliveryPolicy(3,Duration.ofSeconds(8),Duration.ofSeconds(4),Duration.ofSeconds(1),100,List.of(Duration.ZERO,Duration.ZERO));
        return new EventDeliveryWorker(deliveries,new DeliveryTransactions(manager,deliveries,p),p,new EventHandlers(List.of(requestHandler,releaseHandler)),canonicalizer,emf);}
    private java.sql.Connection connection() throws Exception{return DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());}
    private List<String> recordLocks(long connection) throws Exception {
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var q=c.prepareStatement("""
            SELECT l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE,l.LOCK_STATUS,l.LOCK_DATA FROM performance_schema.data_locks l
            JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID WHERE t.PROCESSLIST_ID=? AND l.LOCK_TYPE='RECORD'
            ORDER BY l.OBJECT_NAME,l.INDEX_NAME
            """)){q.setLong(1,connection);List<String> rows=new ArrayList<>();try(var r=q.executeQuery()){while(r.next())rows.add(r.getString(1)+"|"+r.getString(2)+"|"+r.getString(3)+"|"+r.getString(4)+"|"+r.getString(5));}return rows;}
    }
    private void awaitWait(String table,int expected) throws Exception {
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var q=c.prepareStatement("""
            SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l
            ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=?
            """)){q.setString(1,MYSQL.getDatabaseName());q.setString(2,table);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<end){try(var r=q.executeQuery()){r.next();if(r.getLong(1)>=expected)return;}LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));}
            throw new AssertionError("Missing lock wait on "+table);}
    }
    private long waitingConnection(String table) throws Exception {
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var q=c.prepareStatement("""
            SELECT t.PROCESSLIST_ID FROM performance_schema.data_lock_waits w
            JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
            JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID
            WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=? LIMIT 1
            """)){q.setString(1,MYSQL.getDatabaseName());q.setString(2,table);try(var r=q.executeQuery()){assertThat(r.next()).isTrue();return r.getLong(1);}}
    }
    private static byte[] bytes(UUID id){return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    private static UUID uuid(byte[] b){var v=ByteBuffer.wrap(b);return new UUID(v.getLong(),v.getLong());}
    private static void await(CountDownLatch latch){try{if(!latch.await(8,TimeUnit.SECONDS))throw new AssertionError("Gate timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    record Fixture(Tenant tenant,Venue venue,SlotInventory slot){}
    record Entry(UUID id,AuthenticatedPrincipal customer){}
    @TestConfiguration static class Configuration {
        @Bean @Primary MutableClock maintenanceClock(){return new MutableClock();}
        @Bean @Primary Readiness maintenanceReadiness(){return new Readiness();}
    }
    static class Readiness implements CapacityReleaseReadiness {
        final AtomicBoolean ready=new AtomicBoolean(true);
        @Override public boolean isReady(){return ready.get();}
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(NOW);
        @Override public Instant instant(){return now.get();}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
    }
}
