package com.slotq;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.CapacityReleaseReadiness;
import com.slotq.booking.application.PromotionAvailabilityQuery;
import com.slotq.booking.application.ReservationCommand;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryFailure;
import com.slotq.events.application.DeliveryKey;
import com.slotq.events.application.DeliveryPolicy;
import com.slotq.events.application.DeliveryTransactions;
import com.slotq.events.application.EventAppendService;
import com.slotq.events.application.EventCanonicalizer;
import com.slotq.events.application.EventDeliveryStore;
import com.slotq.events.application.EventDeliveryWorker;
import com.slotq.events.application.EventHandler;
import com.slotq.events.application.EventHandlers;
import com.slotq.events.application.EventHandlingException;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRecordQuery;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.events.application.EventReplayService;
import com.slotq.events.application.StoredEvent;
import com.slotq.integration.waitlist.BookingCapacityReleasedHandler;
import com.slotq.integration.waitlist.WaitlistPromotionRequestAdmission;
import com.slotq.integration.waitlist.WaitlistPromotionRequestedHandler;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.WeeklyOperatingHours;
import com.slotq.waitlist.application.PromotionDiscoveryPolicy;
import com.slotq.waitlist.application.PromotionIdentityException;
import com.slotq.waitlist.application.WaitlistEntryRepository;
import com.slotq.waitlist.application.WaitlistPromotionDiscovery;
import com.slotq.waitlist.application.WaitlistPromotionRequestStore;
import com.slotq.waitlist.application.WaitlistPromotionRequestUseCase;
import com.slotq.waitlist.application.WaitlistRegistrationKey;
import com.slotq.waitlist.application.WaitlistUseCase;
import com.slotq.waitlist.domain.WaitlistEntryId;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

@Testcontainers
@SpringBootTest(properties = {"slotq.waitlist.promotion.enabled=true", "slotq.waitlist.promotion.discovery-batch-size=2",
    "slotq.waitlist.promotion.candidate-time-limit=PT0.1S", "slotq.events.delivery.scheduler-enabled=false"})
@Import(WaitlistPromotionRequestIntegrationTests.Configuration.class)
class WaitlistPromotionRequestIntegrationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.slotq.integration.waitlist.WaitlistPromotionBootstrap bootstrap;
    private static final Instant NOW = Instant.parse("2026-08-30T09:00:00Z");
    private static final Instant START = Instant.parse("2026-08-30T11:00:00Z");
    private static final ConsumerRoute REQUEST = WaitlistPromotionRequestedHandler.ROUTE;
    private static final ConsumerRoute RELEASE = BookingCapacityReleasedHandler.ROUTE;
    private static final DeliveryPolicy DELIVERY = new DeliveryPolicy(3, Duration.ofSeconds(8), Duration.ofSeconds(4),
        Duration.ofSeconds(1), 100, List.of(Duration.ZERO, Duration.ZERO));

    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL = new org.testcontainers.mysql.MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq_requests").withCommand("--log-bin-trust-function-creators=1");
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired AccessControlProvisioning access;
    @Autowired ReservationUseCase booking;
    @Autowired WaitlistUseCase waitlist;
    @Autowired WaitlistPromotionRequestUseCase admission;
    @Autowired WaitlistPromotionDiscovery discovery;
    @Autowired PromotionAvailabilityQuery availability;
    @Autowired EventRegistrationService registrations;
    @Autowired EventDeliveryStore deliveries;
    @Autowired EventRecordQuery records;
    @Autowired EventCanonicalizer canonicalizer;
    @Autowired WaitlistPromotionRequestedHandler requestHandler;
    @Autowired BookingCapacityReleasedHandler releaseHandler;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired Readiness readiness;
    @Autowired ApplicationContext context;
    @MockitoSpyBean WaitlistPromotionRequestStore requestStore;
    @MockitoSpyBean WaitlistEntryRepository entries;
    @MockitoSpyBean EventAppendService append;
    @MockitoSpyBean PromotionDiscoveryPolicy policy;
    UUID requestRegistration, releaseRegistration;

    @BeforeEach void prepare() {
        // Isolate global System discovery from completed fixtures without deleting durable evidence.
        jdbc.update("UPDATE tenants SET status='INACTIVE'");
        clock.now.set(NOW); readiness.ready.set(true);
        requestRegistration = registrations.activate(REQUEST);
        releaseRegistration = registrations.activate(RELEASE);
    }
    @AfterEach void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_request_append");
        registrations.deactivate(requestRegistration); registrations.deactivate(releaseRegistration);
    }

    @Test void durableEntryAfterLostRegistrationResponseIsFoundWithoutAReleaseAndOnlyM3CreatesEffect() {
        Fixture f = fixture();
        Entry entry = entry(f, 2); // Deliberately do not use registration response to wake discovery.
        assertThat(requestCount(f)).isZero();
        clock.now.set(NOW.plusNanos(123456789));
        var page = discovery.discover(SystemPrincipal.INSTANCE, null);
        assertThat(page.appended()).isEqualTo(1);
        UUID eventId = latest(f);
        var event = records.find(f.tenant.id(), new EventId(eventId)).orElseThrow().envelope();
        assertThat(event.aggregateType()).isEqualTo("SlotInventory");
        assertThat(event.aggregateId()).isEqualTo(f.slot.id().value());
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-30T09:00:00.123456Z"));
        assertThat(new tools.jackson.databind.json.JsonMapper().readTree(event.payload()).propertyNames())
            .containsExactlyInAnyOrder("venueId", "resourceId", "slotInventoryId");
        assertThat(count("reservations", f)).isZero(); assertThat(count("waitlist_offers", f)).isZero();
        assertThat(count("waitlist_promotion_receipts", f)).isZero();
        process(key(f, eventId));
        assertThat(outcome(eventId)).isEqualTo("PROMOTED");
        assertThat(entryState(entry.id)).isEqualTo("OFFERED");
        assertThat(count("waitlist_offers", f)).isEqualTo(1);
        assertThat(count("waitlist_notification_requests", f)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"UNMATERIALIZED", "PENDING", "CLAIMED", "RETRY", "DEAD"})
    void unfinishedRequestSurvivesNewComponentsAndCannotBypassDeliveryBudget(String state) {
        Fixture f = fixture(); entry(f, 2);
        UUID eventId = request(f).eventId();
        if (!state.equals("UNMATERIALIZED")) {
            var key = key(f, eventId);
            if (state.equals("CLAIMED")) worker().claim(key).orElseThrow();
            if (state.equals("RETRY") || state.equals("DEAD")) {
                var failing = worker(event -> { throw new EventHandlingException(state.equals("RETRY")
                    ? DeliveryFailure.DB_LOCK_TRANSIENT : DeliveryFailure.PAYLOAD_INVALID); }, event -> { });
                failing.process(failing.claim(key).orElseThrow());
                assertThat(deliveryState(eventId)).isEqualTo(state.equals("RETRY") ? "PENDING" : "DEAD");
            }
        }
        var restarted = context.getAutowireCapableBeanFactory().createBean(WaitlistPromotionRequestAdmission.class);
        for (int i = 0; i < 3; i++) {
            var result = restarted.request(SystemPrincipal.INSTANCE, f.venue.id(), f.slot.id());
            assertThat(result.outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.OUTSTANDING);
            assertThat(result.eventId()).isEqualTo(eventId);
        }
        assertThat(requestCount(f)).isEqualTo(1);
        assertThat(latest(f)).isEqualTo(eventId);
        assertThat(count("waitlist_offers", f)).isZero();
    }

    @Test void deadRequestNeedsTrustedReplayNotANewIdentity() {
        Fixture f = fixture(); entry(f, 2);
        UUID event = request(f).eventId(); var key = key(f, event);
        var failed = worker(ignored -> { throw new EventHandlingException(DeliveryFailure.DB_LOCK_TRANSIENT); }, ignored -> { });
        for (int i = 0; i < 3; i++) failed.process(failed.claim(key).orElseThrow());
        assertThat(deliveryState(event)).isEqualTo("DEAD");
        assertThat(failed.claim(key)).isEmpty();
        assertThat(request(f).eventId()).isEqualTo(event);
        new EventReplayService(deliveries, transactions()).replay(SystemPrincipal.INSTANCE, key, "request regression recovery");
        process(key);
        assertThat(outcome(event)).isEqualTo("PROMOTED");
        assertThat(request(f).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.NO_OP);
        assertThat(requestCount(f)).isEqualTo(1);
        assertThat(count("waitlist_offers", f)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"NO_CANDIDATE", "NO_CAPACITY", "NOT_ELIGIBLE", "DEFERRED"})
    void completedReceiptAllowsOnlyAFreshCurrentlyObservedOpportunity(String previousOutcome) {
        Fixture f = fixture(); Entry initial = entry(f, 2);
        UUID original = request(f).eventId();
        ReservationId held = null;
        if (previousOutcome.equals("NO_CANDIDATE")) waitlist.cancel(f.venue.id(), new WaitlistEntryId(initial.id), initial.customer);
        if (previousOutcome.equals("NO_CAPACITY")) held = hold(f, customer());
        if (previousOutcome.equals("NOT_ELIGIBLE")) updateResource(f, 4, ResourceStatus.INACTIVE);
        AtomicBoolean delay = new AtomicBoolean(previousOutcome.equals("DEFERRED"));
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (delay.getAndSet(false)) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(150));
            return result;
        }).when(entries).firstEligibleForUpdate(any(), any(), any(), any(), anyInt());
        process(key(f, original)); assertThat(outcome(original)).isEqualTo(previousOutcome);
        if (!previousOutcome.equals("DEFERRED")) assertThat(request(f).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.NO_OP);
        if (previousOutcome.equals("NO_CANDIDATE")) entry(f, 2);
        if (held != null) clock.now.set(NOW.plusSeconds(301)); // Effective capacity hint, not stored expiry/maintenance.
        if (previousOutcome.equals("NOT_ELIGIBLE")) updateResource(f, 4, ResourceStatus.ACTIVE);
        var next = request(f);
        assertThat(next.outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.APPENDED);
        assertThat(next.eventId()).isNotEqualTo(original);
        process(key(f, next.eventId()));
        assertThat(outcome(next.eventId())).isEqualTo("PROMOTED");
        assertThat(count("waitlist_offers", f)).isEqualTo(1);
        assertThat(requestCount(f)).isEqualTo(2);
    }

    @Test void completedReleaseBeforeRegistrationDoesNotHideLaterDemand() {
        Fixture f = fixture(); var customer = customer(); var held = hold(f, customer);
        booking.transition(f.venue.id(), held, customer, ReservationCommand.CANCEL);
        UUID released = jdbc.queryForObject("SELECT event_id FROM event_records WHERE aggregate_id=?",
            (row, n) -> uuid(row.getBytes(1)), bytes(held.value()));
        worker().materialize(); process(new DeliveryKey(f.tenant.id(), new EventId(released), releaseRegistration));
        assertThat(outcome(released)).isEqualTo("NO_CANDIDATE");
        entry(f, 2); discovery.discover(SystemPrincipal.INSTANCE, null);
        assertThat(requestCount(f)).isEqualTo(1);
        process(key(f, latest(f))); assertThat(outcome(latest(f))).isEqualTo("PROMOTED");
    }

    @Test void completedPromotionNeedsANewAvailableObservationBeforeAnotherEventCanBeAdmitted() {
        Fixture f = fixture(); entry(f,2); UUID first = request(f).eventId(); process(key(f,first));
        entry(f,2);
        assertThat(request(f).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.NO_OP);
        assertThat(latest(f)).isEqualTo(first);
        clock.now.set(NOW.plusSeconds(301));
        var next = request(f);
        assertThat(next.outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.APPENDED);
        assertThat(next.eventId()).isNotEqualTo(first);
        process(key(f,next.eventId()));
        assertThat(outcome(first)).isEqualTo("PROMOTED"); assertThat(outcome(next.eventId())).isEqualTo("PROMOTED");
        assertThat(count("waitlist_offers",f)).isEqualTo(2);
        assertThat(count("waitlist_notification_requests",f)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM reservations r JOIN capacity_allocations a ON a.reservation_id=r.id
            WHERE r.venue_id=? AND a.active=TRUE AND (r.state IN ('CONFIRMED','CHECKED_IN') OR (r.state='HELD' AND r.expires_at>?))
            """,Long.class,bytes(f.venue.id().value()),java.sql.Timestamp.from(clock.instant()))).isEqualTo(1);
        // Old stored HELD/Offer expiry is deliberately not materialized by discovery.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE venue_id=? AND state='HELD'",Long.class,
            bytes(f.venue.id().value()))).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"PAST", "TENANT_INACTIVE", "VENUE_INACTIVE", "HELD", "CONFIRMED"})
    void discoveryDoesNotPublishForPastInactiveOrCurrentlyOccupiedSlots(String reason) {
        Fixture f = fixture(); entry(f,2);
        if (reason.equals("PAST")) clock.now.set(START);
        if (reason.equals("TENANT_INACTIVE")) jdbc.update("UPDATE tenants SET status='INACTIVE' WHERE id=?",bytes(f.tenant.id().value()));
        if (reason.equals("VENUE_INACTIVE")) jdbc.update("UPDATE venues SET status='INACTIVE' WHERE id=?",bytes(f.venue.id().value()));
        if (reason.equals("HELD") || reason.equals("CONFIRMED")) {
            var customer = customer(); var held = hold(f,customer);
            if (reason.equals("CONFIRMED")) booking.transition(f.venue.id(),held,customer,ReservationCommand.CONFIRM);
        }
        assertThat(discovery.discover(SystemPrincipal.INSTANCE,null).examined()).isZero();
        assertThat(request(f).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.NO_OP);
        assertThat(requestCount(f)).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void competingAdmissionsSerializeTheFirstOrNextIdentity(boolean completed) throws Exception {
        Fixture f = fixture(); Entry entry = entry(f, 2);
        int prior = 0;
        if (completed) {
            UUID old = request(f).eventId(); waitlist.cancel(f.venue.id(), new WaitlistEntryId(entry.id), entry.customer);
            process(key(f, old)); entry(f, 2); prior = 1;
        }
        CountDownLatch owns = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (first.getAndSet(false)) { owns.countDown(); await(release); }
            return result;
        }).when(requestStore).lockLatest(any(), any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> request(f));
            try {
                await(owns); var two = pool.submit(() -> request(f)); awaitWait("waitlist_promotion_requests");
                release.countDown();
                var winner = one.get(6, TimeUnit.SECONDS); var loser = two.get(6, TimeUnit.SECONDS);
                assertThat(winner.outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.APPENDED);
                assertThat(loser.outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.OUTSTANDING);
                assertThat(loser.eventId()).isEqualTo(winner.eventId());
            } finally { release.countDown(); }
        }
        assertThat(requestCount(f)).isEqualTo(prior + 1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void inflightReceiptCommitOrRollbackIsObservedBeforeANewOpportunity(boolean commit) throws Exception {
        Fixture f = fixture(); entry(f, 2);
        UUID original = request(f).eventId(); var key = key(f, original);
        CountDownLatch written = new CountDownLatch(1), finish = new CountDownLatch(1);
        var worker = worker(event -> { }, event -> {
            written.countDown(); await(finish);
            if (!commit) throw new EventHandlingException(DeliveryFailure.DB_LOCK_TRANSIENT);
        });
        var claim = worker.claim(key).orElseThrow();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var effect = pool.submit(() -> worker.process(claim));
            try {
                await(written); var contender = pool.submit(() -> request(f));
                awaitWait("waitlist_promotion_receipts"); finish.countDown();
                effect.get(6, TimeUnit.SECONDS);
                var result = contender.get(6, TimeUnit.SECONDS);
                assertThat(result.outcome()).isEqualTo(commit ? WaitlistPromotionRequestUseCase.Outcome.NO_OP
                    : WaitlistPromotionRequestUseCase.Outcome.OUTSTANDING);
                assertThat(result.eventId()).isEqualTo(original);
            } finally { finish.countDown(); }
        }
        assertThat(requestCount(f)).isEqualTo(1);
        assertThat(count("waitlist_offers", f)).isEqualTo(commit ? 1 : 0);
        assertThat(deliveryState(original)).isEqualTo(commit ? "DONE" : "PENDING");
    }

    @ParameterizedTest @ValueSource(strings = {"link", "append", "after-append", "caught-append"})
    void linkAndEventRollBackTogetherAndRetryDoesNotRetainAPhantomIdentity(String failure) {
        Fixture f = fixture(); entry(f, 2);
        long boundary = jdbc.queryForObject("SELECT sequence_value FROM event_boundary", Long.class);
        if (failure.equals("link")) jdbc.execute("""
            CREATE TRIGGER fail_request_append BEFORE UPDATE ON waitlist_promotion_requests FOR EACH ROW
            BEGIN IF NEW.last_event_id IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='link failure'; END IF; END
            """);
        if (failure.equals("append")) jdbc.execute("""
            CREATE TRIGGER fail_request_append BEFORE INSERT ON event_records FOR EACH ROW
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='append failure'
            """);
        if (failure.equals("after-append") || failure.equals("caught-append")) doAnswer(call -> {
            var stored = call.callRealMethod();
            if (failure.equals("after-append")) throw new IllegalStateException("failure after durable append write");
            try { append.append(null); } catch (NullPointerException expected) { /* MANDATORY marks rollback-only. */ }
            return stored;
        }).when(append).appendForActiveRoute(any(), any());
        assertThatThrownBy(() -> request(f)).isInstanceOf(failure.equals("caught-append")
            ? UnexpectedRollbackException.class : RuntimeException.class);
        assertThat(requestCount(f)).isZero();
        assertThat(linkCount(f)).isZero();
        assertThat(jdbc.queryForObject("SELECT sequence_value FROM event_boundary", Long.class)).isEqualTo(boundary);
        jdbc.execute("DROP TRIGGER IF EXISTS fail_request_append");
        org.mockito.Mockito.reset(append);
        var restarted = context.getAutowireCapableBeanFactory().createBean(WaitlistPromotionRequestAdmission.class);
        var result = restarted.request(SystemPrincipal.INSTANCE, f.venue.id(), f.slot.id());
        assertThat(result.outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.APPENDED);
        assertThat(requestCount(f)).isEqualTo(1); assertThat(latest(f)).isEqualTo(result.eventId());
    }

    @Test void failedReplacementKeepsTheCompletedPreviousLink() {
        Fixture f = fixture(); Entry entry = entry(f, 2); UUID old = request(f).eventId();
        waitlist.cancel(f.venue.id(), new WaitlistEntryId(entry.id), entry.customer); process(key(f, old)); entry(f, 2);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("response before transaction completion"); })
            .when(append).appendForActiveRoute(any(), any());
        assertThatThrownBy(() -> request(f)).isInstanceOf(IllegalStateException.class);
        assertThat(latest(f)).isEqualTo(old); assertThat(requestCount(f)).isEqualTo(1);
        assertThat(outcome(old)).isEqualTo("NO_CANDIDATE");
    }

    @ParameterizedTest @ValueSource(strings = {"event", "receipt", "slot"})
    void completedReceiptMustMatchImmutableEventAndSlotNotJustAnEventId(String corrupt) {
        Fixture f = fixture(); Entry entry = entry(f, 2); UUID old = request(f).eventId();
        waitlist.cancel(f.venue.id(), new WaitlistEntryId(entry.id), entry.customer); process(key(f, old)); entry(f, 2);
        if (corrupt.equals("event")) jdbc.update("UPDATE event_records SET occurred_at=TIMESTAMPADD(SECOND,1,occurred_at) WHERE event_id=?", bytes(old));
        if (corrupt.equals("receipt")) jdbc.update("UPDATE waitlist_promotion_receipts SET occurred_at=TIMESTAMPADD(SECOND,1,occurred_at) WHERE event_id=?", bytes(old));
        Fixture target = corrupt.equals("slot") ? extraSlot(f, 4, START) : f;
        if (corrupt.equals("slot")) jdbc.update("INSERT INTO waitlist_promotion_requests VALUES (?,?,?)",
            bytes(target.tenant.id().value()), bytes(target.slot.id().value()), bytes(old));
        assertThatThrownBy(() -> request(target)).isInstanceOf(PromotionIdentityException.class);
        assertThat(latest(f)).isEqualTo(old); assertThat(requestCount(f)).isEqualTo(1);
        assertThat(records.find(tenants.createTenant().id(), new EventId(old))).isEmpty();
    }

    @Test void corruptPrefixIsReportedWithoutStarvingTheNextScopedTarget() {
        Fixture one = fixture(), two = fixture();
        UUID firstId = jdbc.queryForObject("SELECT id FROM slot_inventories WHERE id IN (?,?) ORDER BY id LIMIT 1",
            (row,n) -> uuid(row.getBytes(1)), bytes(one.slot.id().value()), bytes(two.slot.id().value()));
        Fixture corrupt = one.slot.id().value().equals(firstId) ? one : two;
        Fixture healthy = corrupt == one ? two : one;
        Entry entry = entry(corrupt,2); UUID old = request(corrupt).eventId();
        waitlist.cancel(corrupt.venue.id(),new WaitlistEntryId(entry.id),entry.customer); process(key(corrupt,old));
        entry(corrupt,2); entry(healthy,2);
        jdbc.update("UPDATE event_records SET occurred_at=TIMESTAMPADD(SECOND,1,occurred_at) WHERE event_id=?",bytes(old));
        var page = discovery.discover(SystemPrincipal.INSTANCE,null);
        assertThat(page.examined()).isEqualTo(2); assertThat(page.appended()).isEqualTo(1);
        assertThat(page.failures()).hasSize(1);
        assertThat(page.failures().getFirst().slotId()).isEqualTo(corrupt.slot.id());
        assertThat(page.failures().getFirst().cause()).isInstanceOf(PromotionIdentityException.class);
        assertThat(page.nextCursor()).isEqualTo(healthy.slot.id());
        assertThat(latest(corrupt)).isEqualTo(old); assertThat(requestCount(healthy)).isEqualTo(1);
    }

    @Test void lostAppendResponseAndRestartRescanRetainTheCommittedIdentity() {
        Fixture f = fixture(); entry(f, 2);
        request(f); // Lost caller response after physical commit, not a simulated rollback.
        UUID durable = latest(f);
        var restarted = new WaitlistPromotionDiscovery(availability, admission, policy, clock, manager);
        assertThat(restarted.discover(SystemPrincipal.INSTANCE, null).outstanding()).isEqualTo(1);
        assertThat(latest(f)).isEqualTo(durable); assertThat(requestCount(f)).isEqualTo(1);
        process(key(f, durable)); assertThat(outcome(durable)).isEqualTo("PROMOTED");
    }

    @Test void keysetPagesPassNoDemandAndDeadPrefixesBeyondBatchAndRestartCanRescan() {
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 7; i++) fixtures.add(fixture());
        List<UUID> ids = jdbc.query("SELECT id FROM slot_inventories WHERE tenant_id IN (SELECT id FROM tenants WHERE status='ACTIVE') ORDER BY id",
            (row, n) -> uuid(row.getBytes(1)));
        Fixture last = fixtures.stream().filter(f -> f.slot.id().value().equals(ids.getLast())).findFirst().orElseThrow();
        Fixture first = fixtures.stream().filter(f -> f.slot.id().value().equals(ids.getFirst())).findFirst().orElseThrow();
        entry(first, 2); UUID dead = request(first).eventId();
        var failing = worker(e -> { throw new EventHandlingException(DeliveryFailure.PAYLOAD_INVALID); }, e -> { });
        var deadKey = key(first, dead); failing.process(failing.claim(deadKey).orElseThrow());
        entry(last, 2);
        SlotInventoryId cursor = null; int examined = 0, appended = 0, pages = 0;
        do {
            var page = discovery.discover(SystemPrincipal.INSTANCE, cursor);
            assertThat(page.examined()).isLessThanOrEqualTo(2);
            examined += page.examined(); appended += page.appended(); cursor = page.nextCursor(); pages++;
        } while (cursor != null && pages < 10);
        assertThat(cursor).isNull(); assertThat(examined).isEqualTo(7); assertThat(appended).isEqualTo(1);
        assertThat(latest(first)).isEqualTo(dead); assertThat(deliveryState(dead)).isEqualTo("DEAD");
        assertThat(requestCount(last)).isEqualTo(1);
        assertThat(new WaitlistPromotionDiscovery(availability, admission, policy, clock, manager)
            .discover(SystemPrincipal.INSTANCE, null).appended()).isZero();
    }

    @Test void advisoryMatchingObservesResourceChangesAndNewSlotWithoutCreatingBusinessState() {
        Fixture f = fixture();
        Fixture larger = extraSlot(f, 8, START); entry(larger, 7);
        assertThat(request(f).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.NO_OP);
        updateResource(f, 8, ResourceStatus.INACTIVE);
        assertThat(request(f).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.NO_OP);
        updateResource(f, 8, ResourceStatus.ACTIVE);
        assertThat(request(f).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.APPENDED);
        Fixture fresh = extraSlot(f, 8, START);
        discovery.discover(SystemPrincipal.INSTANCE, null);
        var page = discovery.discover(SystemPrincipal.INSTANCE, null);
        if (page.nextCursor() != null) discovery.discover(SystemPrincipal.INSTANCE, page.nextCursor());
        assertThat(latest(fresh)).isNotNull();
        Fixture otherTime = extraSlot(f, 8, START.plusSeconds(1800));
        assertThat(request(otherTime).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.NO_OP);
        assertThat(count("waitlist_offers", f)).isZero(); assertThat(count("reservations", f)).isZero();
    }

    @Test void advisoryReadsDoNotLockBookingEvenWhenSlotAndAppendFenceAreHeld() throws Exception {
        Fixture f = fixture(); entry(f, 2);
        AtomicReference<Long> requestConnection = new AtomicReference<>();
        doAnswer(call -> { Object result = call.callRealMethod(); requestConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)); return result; })
            .when(requestStore).connect(any(), any(), any());
        try (var slotOwner = connection(); var fenceOwner = connection(); var pool = Executors.newSingleThreadExecutor()) {
            lock(slotOwner, "SELECT id FROM slot_inventories WHERE id=? FOR UPDATE", f.slot.id().value());
            fenceOwner.setAutoCommit(false);
            try (var statement = fenceOwner.createStatement()) { statement.executeQuery("SELECT * FROM event_boundary FOR UPDATE").close(); }
            var future = pool.submit(() -> request(f));
            try {
                awaitWait("event_boundary");
                var locks = locks(requestConnection.get());
                assertThat(locks).anyMatch(value -> value.startsWith("waitlist_promotion_requests|"));
                assertThat(locks).anyMatch(value -> value.startsWith("event_boundary|") && value.contains("WAITING"));
                assertThat(locks).noneMatch(value -> value.startsWith("slot_inventories|") || value.startsWith("reservations|")
                    || value.startsWith("capacity_allocations|") || value.startsWith("waitlist_entries|") || value.startsWith("waitlist_offers|"));
                java.nio.file.Files.writeString(java.nio.file.Path.of("build", "waitlist-request-lock-evidence.txt"), String.join("\n", locks));
                fenceOwner.commit();
                assertThat(future.get(6, TimeUnit.SECONDS).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.APPENDED);
                // Slot is still X locked here: append was advisory and did not acquire Booking capacity.
            } finally { fenceOwner.rollback(); slotOwner.rollback(); }
        }
    }

    @Test void aBlockedAdmissionDoesNotSerializeAnotherSlot() throws Exception {
        Fixture f = fixture(); entry(f, 2); Fixture other = extraSlot(f, 4, START);
        UUID first = request(f).eventId();
        try (var owner = connection()) {
            owner.setAutoCommit(false);
            try (var query = owner.prepareStatement("SELECT slot_inventory_id FROM waitlist_promotion_requests WHERE tenant_id=? AND slot_inventory_id=? FOR UPDATE")) {
                query.setBytes(1,bytes(f.tenant.id().value())); query.setBytes(2,bytes(f.slot.id().value()));
                try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); }
            }
            assertThat(request(other).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.APPENDED);
            owner.rollback();
        }
        assertThat(latest(f)).isEqualTo(first); assertThat(latest(other)).isNotEqualTo(first);
    }

    @Test void disabledNotReadyMissingRouteAndOuterSnapshotCannotOpenProducer() {
        Fixture f = fixture(); entry(f, 2);
        doReturn(false).when(policy).enabled();
        assertThat(request(f).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.DISABLED);
        assertThat(discovery.discover(SystemPrincipal.INSTANCE, null).examined()).isZero();
        doReturn(true).when(policy).enabled(); readiness.ready.set(false);
        assertThatThrownBy(() -> request(f)).hasMessageContaining("not ready"); readiness.ready.set(true);
        registrations.deactivate(requestRegistration);
        assertThatThrownBy(() -> request(f)).hasMessageContaining("route is not active");
        assertThat(linkCount(f)).isZero(); assertThat(requestCount(f)).isZero();
        requestRegistration = registrations.activate(REQUEST);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_entries", Long.class);
            return request(f);
        })).hasMessageContaining("outside a caller transaction");
        assertThatThrownBy(() -> admission.request(null, f.venue.id(), f.slot.id())).isInstanceOf(NullPointerException.class);
        assertThat(request(f).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.APPENDED);
    }

    @Test void boundedAppendFenceTimeoutRollsBackTheAdmissionAnchor() throws Exception {
        Fixture f = fixture(); entry(f,2);
        try (var owner = connection()) {
            owner.setAutoCommit(false);
            try (var statement = owner.createStatement()) { statement.executeQuery("SELECT * FROM event_boundary FOR UPDATE").close(); }
            assertThatThrownBy(() -> request(f)).isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
            assertThat(linkCount(f)).isZero(); assertThat(requestCount(f)).isZero();
            owner.rollback();
        }
        assertThat(request(f).outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.APPENDED);
    }

    @Test void discoveryConfigurationHasPositiveFiniteBounds() {
        assertThatThrownBy(() -> new PromotionDiscoveryPolicy(true,0,5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromotionDiscoveryPolicy(true,1001,5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromotionDiscoveryPolicy(true,32,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromotionDiscoveryPolicy(true,32,31)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PromotionDiscoveryPolicy(false,32,5).enabled()).isFalse();
    }

    private Fixture fixture() {
        Tenant tenant = tenants.createTenant();
        Venue venue = venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(), "Discovery", "UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY, new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(14, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10)));
        Resource resource = resources.createResource(new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Table", 4));
        SlotInventory slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(), venue.id(), resource.id(), START.toString()));
        return new Fixture(tenant, venue, resource, slot);
    }
    private Fixture extraSlot(Fixture f, int capacity, Instant start) {
        var resource = resources.createResource(new ResourceUseCase.CreateResource(f.tenant.id(), f.venue.id(), "Other", capacity));
        var slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(f.tenant.id(), f.venue.id(), resource.id(), start.toString()));
        return new Fixture(f.tenant, f.venue, resource, slot);
    }
    private void updateResource(Fixture f, int capacity, ResourceStatus state) {
        resources.updateResource(new ResourceUseCase.UpdateResource(f.tenant.id(), f.venue.id(), f.resource.id(), "Table", capacity, state));
    }
    private AuthenticatedPrincipal customer() {
        var customer = new AuthenticatedPrincipal(PrincipalId.newId()); access.registerPrincipal(customer.principalId()); return customer;
    }
    private Entry entry(Fixture f, int partySize) {
        var customer = customer();
        var value = waitlist.register(new WaitlistUseCase.CreateRegistration(f.venue.id(), f.slot.id(), partySize,
            new WaitlistRegistrationKey(UUID.randomUUID()), customer)).entry();
        return new Entry(value.id(), customer);
    }
    private ReservationId hold(Fixture f, AuthenticatedPrincipal customer) {
        return booking.createHold(new ReservationUseCase.CreateHold(f.venue.id(), f.slot.id(), customer, 2)).reservation().id();
    }
    private WaitlistPromotionRequestUseCase.Result request(Fixture f) { return admission.request(SystemPrincipal.INSTANCE, f.venue.id(), f.slot.id()); }
    private long count(String table, Fixture f) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE venue_id=?", Long.class, bytes(f.venue.id().value())); }
    private long requestCount(Fixture f) { return jdbc.queryForObject("SELECT COUNT(*) FROM event_records WHERE tenant_id=? AND event_type=?", Long.class, bytes(f.tenant.id().value()), REQUEST.eventType()); }
    private long linkCount(Fixture f) { return jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_promotion_requests WHERE tenant_id=? AND slot_inventory_id=?", Long.class, bytes(f.tenant.id().value()), bytes(f.slot.id().value())); }
    private UUID latest(Fixture f) { return jdbc.queryForObject("SELECT last_event_id FROM waitlist_promotion_requests WHERE tenant_id=? AND slot_inventory_id=?", (row,n) -> row.getBytes(1) == null ? null : uuid(row.getBytes(1)), bytes(f.tenant.id().value()), bytes(f.slot.id().value())); }
    private String outcome(UUID event) { return jdbc.queryForObject("SELECT outcome FROM waitlist_promotion_receipts WHERE event_id=?", String.class, bytes(event)); }
    private String entryState(UUID entry) { return jdbc.queryForObject("SELECT state FROM waitlist_entries WHERE id=?", String.class, bytes(entry)); }
    private String deliveryState(UUID event) { return jdbc.queryForObject("SELECT state FROM event_deliveries WHERE event_id=?", String.class, bytes(event)); }
    private DeliveryKey key(Fixture f, UUID event) { worker().materialize(); return new DeliveryKey(f.tenant.id(), new EventId(event), requestRegistration); }
    private DeliveryTransactions transactions() { return new DeliveryTransactions(manager, deliveries, DELIVERY); }
    private EventDeliveryWorker worker() { return worker(e -> { }, e -> { }); }
    private EventDeliveryWorker worker(Consumer<StoredEvent> before, Consumer<StoredEvent> after) {
        var handlers = List.of(requestHandler, releaseHandler).stream().map(actual -> (EventHandler) new EventHandler() {
            @Override public ConsumerRoute route() { return actual.route(); }
            @Override public void handle(StoredEvent event) { before.accept(event); actual.handle(event); after.accept(event); }
        }).toList();
        return new EventDeliveryWorker(deliveries, transactions(), DELIVERY, new EventHandlers(handlers), canonicalizer, emf);
    }
    private void process(DeliveryKey key) { var worker = worker(); worker.process(worker.claim(key).orElseThrow()); }
    private Connection connection() throws Exception { return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); }
    private void lock(Connection connection, String sql, UUID id) throws Exception {
        connection.setAutoCommit(false);
        try (var query = connection.prepareStatement(sql)) { query.setBytes(1, bytes(id)); try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); } }
    }
    private List<String> locks(long id) throws Exception {
        try (var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var query = observer.prepareStatement("""
                SELECT l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE,l.LOCK_STATUS,l.LOCK_DATA FROM performance_schema.data_locks l
                JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID WHERE t.PROCESSLIST_ID=? AND l.LOCK_TYPE='RECORD'
                ORDER BY l.OBJECT_NAME,l.INDEX_NAME
                """)) {
            query.setLong(1,id); List<String> locks = new ArrayList<>();
            try (var rows = query.executeQuery()) { while (rows.next()) locks.add(rows.getString(1)+"|"+rows.getString(2)+"|"+rows.getString(3)+"|"+rows.getString(4)+"|"+rows.getString(5)); }
            return locks;
        }
    }
    private void awaitWait(String table) throws Exception {
        try (var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var query = observer.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l
                ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=?
                """)) {
            query.setString(1,MYSQL.getDatabaseName()); query.setString(2,table);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                try (var rows = query.executeQuery()) { rows.next(); if (rows.getLong(1)>0) return; }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            throw new AssertionError("No lock wait on " + table);
        }
    }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] bytes) { var value = ByteBuffer.wrap(bytes); return new UUID(value.getLong(),value.getLong()); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(6,TimeUnit.SECONDS)) throw new AssertionError("gate timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    record Fixture(Tenant tenant, Venue venue, Resource resource, SlotInventory slot) { }
    record Entry(UUID id, AuthenticatedPrincipal customer) { }
    @TestConfiguration static class Configuration {
        @Bean @Primary MutableClock requestClock() { return new MutableClock(); }
        @Bean @Primary Readiness readiness() { return new Readiness(); }
    }
    static class Readiness implements CapacityReleaseReadiness {
        final AtomicBoolean ready = new AtomicBoolean(true);
        @Override public boolean isReady() { return ready.get(); }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
