package com.slotq;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.CapacityReleaseReadiness;
import com.slotq.booking.application.ReservationCommand;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.booking.application.ReservationRepository;
import com.slotq.booking.application.CapacityUnavailableException;
import com.slotq.booking.application.SlotInventoryRepository;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryClaim;
import com.slotq.events.application.DeliveryKey;
import com.slotq.events.application.DeliveryPolicy;
import com.slotq.events.application.DeliveryTransactions;
import com.slotq.events.application.EventAppendService;
import com.slotq.events.application.EventCanonicalizer;
import com.slotq.events.application.EventDeliveryStore;
import com.slotq.events.application.EventDeliveryWorker;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventHandler;
import com.slotq.events.application.EventHandlers;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRecordStore;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.events.application.EventReplayService;
import com.slotq.events.application.StoredEvent;
import com.slotq.integration.waitlist.BookingCapacityReleasedHandler;
import com.slotq.integration.waitlist.WaitlistPromotionRequestedHandler;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.WeeklyOperatingHours;
import com.slotq.waitlist.application.WaitlistOfferUseCase;
import com.slotq.waitlist.application.WaitlistEntryRepository;
import com.slotq.waitlist.application.WaitlistRegistrationKey;
import com.slotq.waitlist.application.WaitlistUseCase;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistOfferId;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

@Testcontainers
@SpringBootTest(properties = {"slotq.waitlist.promotion.enabled=true",
    "slotq.events.delivery.scheduler-enabled=false", "slotq.waitlist.promotion.candidate-batch-size=2",
    "slotq.waitlist.promotion.candidate-time-limit=PT0.2S"})
@Import(WaitlistPromotionDeliveryIntegrationTests.FixtureConfiguration.class)
class WaitlistPromotionDeliveryIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-08-30T09:00:00Z");
    private static final Instant START = Instant.parse("2026-08-30T11:00:00Z");
    private static final ConsumerRoute RELEASE = BookingCapacityReleasedHandler.ROUTE;
    private static final ConsumerRoute REQUEST = WaitlistPromotionRequestedHandler.ROUTE;
    private static final DeliveryPolicy POLICY = new DeliveryPolicy(3, Duration.ofSeconds(8),
        Duration.ofSeconds(4), Duration.ofSeconds(1), 100, List.of(Duration.ZERO, Duration.ZERO));

    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL = new org.testcontainers.mysql.MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq_promotion").withCommand("--log-bin-trust-function-creators=1");

    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired AccessControlProvisioning access;
    @Autowired ReservationUseCase booking;
    @Autowired WaitlistUseCase waitlist;
    @Autowired WaitlistOfferUseCase offers;
    @Autowired EventAppendService append;
    @Autowired EventRegistrationService registrations;
    @Autowired EventRecordStore records;
    @Autowired EventDeliveryStore deliveries;
    @Autowired EventCanonicalizer canonicalizer;
    @Autowired BookingCapacityReleasedHandler releaseHandler;
    @Autowired WaitlistPromotionRequestedHandler requestHandler;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @MockitoSpyBean WaitlistEntryRepository entryRepository;
    @MockitoSpyBean SlotInventoryRepository slotRepository;
    @MockitoSpyBean ReservationRepository reservationRepository;
    private final Map<ConsumerRoute, UUID> active = new java.util.HashMap<>();

    @BeforeEach void prepare() {
        clock.set(NOW);
        active.put(RELEASE, registrations.activate(RELEASE));
        active.put(REQUEST, registrations.activate(REQUEST));
    }
    @AfterEach void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_promotion_effect");
        active.values().forEach(registrations::deactivate);
        active.clear();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bothProductionRoutesCommitOneEffectReceiptNotificationAndDone(boolean release) {
        Fixture fixture = fixture();
        StoredEvent event = release ? release(fixture) : request(fixture);
        Entry entry = entry(fixture, 2);
        DeliveryKey key = key(event);
        process(key);
        assertPromoted(fixture, key, entry.id());
        assertThat(jdbc.queryForObject("SELECT consumer_id FROM waitlist_promotion_receipts WHERE event_id = ?",
            String.class, bytes(key.eventId().value()))).isEqualTo("waitlist.promotion");
    }

    @Test void eligibleFifoPrefiltersMoreThanTheBatchAndUsesDatabaseBinaryIdTieBreak() {
        Fixture fixture = fixture();
        Fixture larger = extraSlot(fixture, 8, START);
        for (int index = 0; index < 7; index++) entry(larger, 7);
        Fixture later = extraSlot(fixture, 4, START.plusSeconds(1800));
        entry(later, 2);
        Entry terminal = entry(fixture, 2);
        waitlist.cancel(fixture.venue.id(), new WaitlistEntryId(terminal.id()), terminal.customer());
        Entry first = entry(fixture, 2);
        Entry second = entry(fixture, 2);
        UUID expected = jdbc.queryForObject("""
            SELECT id FROM waitlist_entries WHERE id IN (?, ?) ORDER BY joined_at, id LIMIT 1
            """, (row, n) -> uuid(row.getBytes(1)), bytes(first.id()), bytes(second.id()));
        DeliveryKey key = key(request(fixture));
        process(key);
        assertPromoted(fixture, key, expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_entries WHERE venue_id = ? AND state = 'WAITING'",
            Integer.class, bytes(fixture.venue.id().value()))).isEqualTo(9);
    }

    @ParameterizedTest @ValueSource(strings = {"NO_CANDIDATE", "NO_CAPACITY", "NOT_ELIGIBLE", "SLOT_PAST"})
    void normalNoOpIsACompletedReceiptAndDoesNotBecomeANewEffectOnRedelivery(String outcome) {
        Fixture fixture = fixture();
        if (!outcome.equals("NO_CANDIDATE")) entry(fixture, 2);
        if (outcome.equals("NO_CAPACITY")) hold(fixture, customer());
        if (outcome.equals("NOT_ELIGIBLE")) jdbc.update("UPDATE resources SET status = 'INACTIVE' WHERE id = ?", bytes(fixture.resource.id().value()));
        if (outcome.equals("SLOT_PAST")) clock.set(START);
        DeliveryKey key = key(request(fixture));
        process(key);
        assertOutcome(key, outcome);
        clock.set(NOW);
        jdbc.update("UPDATE resources SET status = 'ACTIVE' WHERE id = ?", bytes(fixture.resource.id().value()));
        if (outcome.equals("NO_CANDIDATE")) entry(fixture, 2);
        redeliver(key);
        assertOutcome(key, outcome);
        assertThat(count("waitlist_offers", fixture)).isZero();
        assertThat(count("waitlist_notification_requests", fixture)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"ACCEPTED", "DECLINED", "EXPIRED"})
    void terminalOfferAndNewDemandCannotReuseTheSameCompletedEvent(String terminal) {
        Fixture fixture = fixture();
        Entry first = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        process(key);
        UUID offer = uuid((byte[]) receipt(key).get("offer_id"));
        switch (terminal) {
            case "ACCEPTED" -> offers.accept(fixture.venue.id(), new WaitlistOfferId(offer), first.customer());
            case "DECLINED" -> offers.reject(fixture.venue.id(), new WaitlistOfferId(offer), first.customer());
            case "EXPIRED" -> {
                clock.set(NOW.plusSeconds(301));
                offers.reconcileTarget(SystemPrincipal.INSTANCE, fixture.venue.id(), new WaitlistOfferId(offer));
            }
        }
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?", String.class, bytes(offer))).isEqualTo(terminal);
        entry(fixture, 2);
        redeliver(key);
        assertThat(count("waitlist_offers", fixture)).isEqualTo(1);
        assertThat(count("waitlist_notification_requests", fixture)).isEqualTo(1);
        assertOutcome(key, "PROMOTED");
    }

    @ParameterizedTest @ValueSource(strings = {"extra-field", "missing-field", "bad-id", "uppercase-id", "wrong-state",
        "wrong-type", "wrong-aggregate", "wrong-source", "missing-slot", "wrong-resource", "wrong-tenant"})
    void illegalSchemaAndOwnershipAreFailuresNotNoOpReceipts(String failure) {
        Fixture fixture = fixture();
        StoredEvent source = release(fixture);
        var payload = new java.util.HashMap<String, Object>(new JsonMapper().readValue(source.envelope().payload(), Map.class));
        var tenant = fixture.tenant.id();
        String aggregateType = "Reservation";
        UUID sourceId = source.envelope().aggregateId();
        switch (failure) {
            case "extra-field" -> payload.put("customer", "forbidden");
            case "missing-field" -> payload.remove("resourceId");
            case "bad-id" -> payload.put("venueId", "1-1-1-1-1");
            case "uppercase-id" -> payload.put("venueId", fixture.venue.id().value().toString().toUpperCase());
            case "wrong-state" -> payload.put("toState", "CONFIRMED");
            case "wrong-type" -> payload.put("fromState", 1);
            case "wrong-aggregate" -> aggregateType = "SlotInventory";
            case "wrong-source" -> sourceId = UUID.randomUUID();
            case "missing-slot" -> payload.put("slotInventoryId", UUID.randomUUID().toString());
            case "wrong-resource" -> payload.put("resourceId", UUID.randomUUID().toString());
            case "wrong-tenant" -> tenant = tenants.createTenant().id();
        }
        var envelope = new EventEnvelope(EventId.newId(), tenant, aggregateType, sourceId,
            RELEASE.eventType(), 1, NOW, new JsonMapper().writeValueAsString(payload));
        DeliveryKey key = key(append(envelope));
        process(key);
        assertFailure(key, failure.equals("wrong-tenant") ? "TENANT_MISMATCH" : "PAYLOAD_INVALID");
        assertThat(receiptCount(key)).isZero();
    }

    @Test void requestAggregateMustMatchSlotAndRouteCaseAndVersionAreExact() {
        Fixture fixture = fixture();
        var valid = request(fixture).envelope();
        DeliveryKey mismatch = key(append(new EventEnvelope(EventId.newId(), valid.tenantId(), "SlotInventory", UUID.randomUUID(),
            REQUEST.eventType(), 1, NOW, valid.payload())));
        process(mismatch);
        assertFailure(mismatch, "IDENTITY_CORRUPTION");
        for (var route : List.of(new ConsumerRoute("waitlist.promotion", REQUEST.eventType(), 2),
            new ConsumerRoute("waitlist.promotion", "Waitlist.promotion-requested", 1))) {
            active.put(route, registrations.activate(route));
            DeliveryKey wrong = key(append(new EventEnvelope(EventId.newId(), valid.tenantId(), valid.aggregateType(), valid.aggregateId(),
                route.eventType(), route.schemaVersion(), NOW, valid.payload())));
            process(wrong);
            assertFailure(wrong, route.schemaVersion() == 2 ? "UNSUPPORTED_VERSION" : "TARGET_HANDLER_MISSING");
        }
    }

    @Test void sameIdentityWithDifferentImmutableMeaningIsCorruptionEvenAfterTerminalOutcome() {
        Fixture fixture = fixture();
        DeliveryKey key = key(request(fixture));
        process(key);
        assertOutcome(key, "NO_CANDIDATE");
        jdbc.update("UPDATE event_records SET occurred_at = TIMESTAMPADD(SECOND, 1, occurred_at) WHERE event_id = ?", bytes(key.eventId().value()));
        redeliver(key);
        assertFailure(key, "IDENTITY_CORRUPTION");
        assertThat(receipt(key).get("outcome")).isEqualTo("NO_CANDIDATE");
    }

    @ParameterizedTest @ValueSource(strings = {"capacity_allocations", "waitlist_offers", "waitlist_entries",
        "waitlist_notification_requests", "waitlist_promotion_receipts", "event_deliveries"})
    void anyEffectOrFinalDoneFailureRollsBackEveryBusinessRow(String table) {
        Fixture fixture = fixture();
        Entry entry = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        boolean update = List.of("waitlist_entries", "waitlist_promotion_receipts", "event_deliveries").contains(table);
        String condition = switch (table) {
            case "waitlist_entries" -> "NEW.state = 'OFFERED'";
            case "waitlist_promotion_receipts" -> "NEW.outcome IS NOT NULL";
            case "event_deliveries" -> "NEW.state = 'DONE'";
            default -> "TRUE";
        };
        jdbc.execute("CREATE TRIGGER fail_promotion_effect BEFORE " + (update ? "UPDATE" : "INSERT") + " ON " + table + " FOR EACH ROW "
            + "BEGIN IF " + condition + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected promotion effect failure'; END IF; END");
        process(key);
        assertFailure(key, "UNCLASSIFIED_FAILURE");
        assertNoEffect(fixture, key);
        assertThat(entryState(entry.id())).isEqualTo("WAITING");
        jdbc.execute("DROP TRIGGER fail_promotion_effect");
        redeliver(key);
        assertPromoted(fixture, key, entry.id());
    }

    @Test void finalLeaseLossRollsBackRealPromotionThenSameIdentityRecovers() {
        Fixture fixture = fixture();
        Entry entry = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        EventDeliveryWorker slow = worker(event -> { }, event -> jdbc.queryForObject("SELECT SLEEP(0.4)", Integer.class));
        DeliveryClaim claim = slow.claim(key).orElseThrow();
        jdbc.update("UPDATE event_deliveries SET lease_until = TIMESTAMPADD(MICROSECOND, 250000, UTC_TIMESTAMP(6)) WHERE event_id = ?",
            bytes(key.eventId().value()));
        slow.process(claim);
        assertThat(deliveryState(key)).isEqualTo("PROCESSING");
        assertNoEffect(fixture, key);
        process(key);
        assertPromoted(fixture, key, entry.id());
    }

    @Test void finalDoneFencingPredicateRollsBackTheWholeEffectAndStaleClaimCannotApplyIt() {
        Fixture fixture = fixture();
        Entry entry = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        var worker = worker(event -> { }, event -> jdbc.update(
            "UPDATE event_deliveries SET fencing_token = fencing_token + 1 WHERE event_id = ?", bytes(key.eventId().value())));
        DeliveryClaim stale = worker.claim(key).orElseThrow();
        worker.process(stale);
        assertThat(deliveryState(key)).isEqualTo("PROCESSING");
        assertNoEffect(fixture, key);
        jdbc.update("UPDATE event_deliveries SET lease_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE event_id = ?",
            bytes(key.eventId().value()));
        var replacement = worker().claim(key).orElseThrow();
        worker().process(stale);
        assertNoEffect(fixture, key);
        worker().process(replacement);
        assertPromoted(fixture, key, entry.id());
    }

    @Test void sameClaimConcurrentProcessingCommitsOnlyOneEffect() throws Exception {
        Fixture fixture = fixture();
        Entry entry = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        var worker = worker();
        DeliveryClaim claim = worker.claim(key).orElseThrow();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> worker.process(claim));
            var second = pool.submit(() -> worker.process(claim));
            first.get(8, TimeUnit.SECONDS); second.get(8, TimeUnit.SECONDS);
        }
        assertPromoted(fixture, key, entry.id());
    }

    @Test void replacementWorkerUsesDurableClaimAndTerminalReceiptWithoutLocalState() {
        Fixture fixture = fixture();
        Entry first = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        EventDeliveryWorker stopped = worker();
        DeliveryClaim oldClaim = stopped.claim(key).orElseThrow();
        // Component restart fixture, not evidence of a JVM/process crash (owned by the later checkpoint).
        jdbc.update("UPDATE event_deliveries SET lease_until=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE event_id=?",
            bytes(key.eventId().value()));
        EventDeliveryWorker restarted = worker();
        DeliveryClaim current = restarted.claim(key).orElseThrow();
        assertThat(current.fencingToken()).isGreaterThan(oldClaim.fencingToken());
        stopped.process(oldClaim);
        assertNoEffect(fixture, key);
        restarted.process(current);
        assertPromoted(fixture, key, first.id());
        UUID offerId = uuid((byte[]) receipt(key).get("offer_id"));
        offers.reject(fixture.venue.id(), new WaitlistOfferId(offerId), first.customer());
        Entry later = entry(fixture, 2);
        redeliver(key); // constructs a new worker; all idempotency evidence comes from MySQL
        assertOutcome(key, "PROMOTED");
        assertThat(entryState(later.id())).isEqualTo("WAITING");
        assertThat(count("waitlist_offers", fixture)).isEqualTo(1);
        assertThat(count("waitlist_notification_requests", fixture)).isEqualTo(1);
        assertThat(worker().claim(key)).isEmpty();
    }

    @Test void promotionAndRegistrationHaveNoEventBoundaryToBusinessInverseEdge() throws Exception {
        Fixture fixture = fixture();
        Entry entry = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        AtomicReference<Long> registrationConnection = new AtomicReference<>();
        AtomicReference<List<String>> effectLocks = new AtomicReference<>();
        var consumer = worker(event -> { }, event -> {
            try { effectLocks.set(recordLocks(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class))); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        DeliveryClaim claim = consumer.claim(key).orElseThrow();
        try (Connection fenceOwner = connection(); var pool = Executors.newSingleThreadExecutor()) {
            fenceOwner.setAutoCommit(false);
            try (var query = fenceOwner.createStatement(); var rows = query.executeQuery(
                "SELECT singleton_id FROM event_boundary WHERE singleton_id=1 FOR UPDATE")) { assertThat(rows.next()).isTrue(); }
            var cutover = pool.submit(() -> transaction().execute(status -> {
                registrationConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                return registrations.deactivate(active.get(RELEASE));
            }));
            try {
                awaitWait("event_boundary");
                List<String> registrationLocks = recordLocks(registrationConnection.get());
                assertThat(registrationLocks).allMatch(row -> row.startsWith("event_boundary|"));
                consumer.process(claim); // completes while the append/registration fence is still held
                assertPromoted(fixture, key, entry.id());
                assertThat(effectLocks.get()).anyMatch(row -> row.startsWith("slot_inventories|PRIMARY|X"));
                // Uncontended new Allocation/notification rows may use implicit INSERT locks and
                // are not necessarily listed in data_locks. Their committed pair is checked above.
                for (String table : List.of("waitlist_entries", "reservations", "waitlist_offers",
                    "waitlist_promotion_receipts", "event_deliveries")) {
                    assertThat(effectLocks.get()).as(table).anyMatch(row -> row.startsWith(table + "|"));
                }
                assertThat(effectLocks.get()).noneMatch(row -> row.startsWith("event_boundary|")
                    || row.startsWith("event_registrations|") || row.startsWith("event_records|"));
                assertThat(cutover.isDone()).isFalse();
                var output = java.nio.file.Path.of("build", "waitlist-promotion-lock-evidence.txt");
                java.nio.file.Files.writeString(output, "REGISTRATION WAIT:\n" + String.join("\n", registrationLocks)
                    + "\nPROMOTION AFTER JPA/JDBC EFFECT, BEFORE DONE:\n" + String.join("\n", effectLocks.get()));
            } finally { fenceOwner.rollback(); }
            assertThat(cutover.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void aLockedEligiblePredecessorCannotBeSkippedAndNowaitIsRetryable() throws Exception {
        Fixture fixture = fixture();
        Entry first = entry(fixture, 2);
        clock.set(NOW.plusSeconds(1));
        Entry later = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        try (Connection owner = connection()) {
            lock(owner, "SELECT id FROM waitlist_entries WHERE id = ? FOR UPDATE", first.id());
            process(key);
            assertThat(deliveryState(key)).isEqualTo("PENDING");
            assertThat(failureCode(key)).isEqualTo("DB_LOCK_TRANSIENT");
            assertNoEffect(fixture, key);
            assertThat(entryState(later.id())).isEqualTo("WAITING");
            owner.commit();
        }
        process(key);
        assertPromoted(fixture, key, first.id());
    }

    @Test void boundedCandidateTimeIsDeferredAndANewEvaluationMakesProgress() {
        Fixture fixture = fixture();
        Entry first = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        doAnswer(call -> {
            Object candidate = call.callRealMethod();
            // Only inject elapsed time, retaining the real MySQL candidate query and locks.
            jdbc.queryForObject("SELECT SLEEP(0.3)", Integer.class);
            return candidate;
        }).when(entryRepository).firstEligibleForUpdate(any(), any(), any(), any(), anyInt());
        process(key);
        assertOutcome(key, "DEFERRED");
        assertThat(count("waitlist_offers", fixture)).isZero();
        org.mockito.Mockito.reset(entryRepository);
        DeliveryKey next = key(request(fixture));
        process(next);
        assertPromoted(fixture, next, first.id());
    }

    @ParameterizedTest @ValueSource(strings = {"cancel", "other-slot", "inactive", "ordinary-hold", "new-demand", "seating", "policy"})
    void currentGuardsObserveChangesAfterM3TargetHasCreatedAnEarlyRrSnapshot(String change) throws Exception {
        Fixture fixture = fixture();
        Fixture otherSlot = extraSlot(fixture, 4, START);
        Entry first = entry(fixture, 2);
        clock.set(NOW.plusSeconds(1));
        Entry next = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        CountDownLatch snapshot = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        var worker = worker(event -> { snapshot.countDown(); await(proceed); }, event -> { });
        var claim = worker.claim(key).orElseThrow();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var work = pool.submit(() -> worker.process(claim));
            try {
                await(snapshot);
                switch (change) {
                    case "cancel" -> waitlist.cancel(fixture.venue.id(), new WaitlistEntryId(first.id()), first.customer());
                    case "other-slot" -> offers.createTarget(SystemPrincipal.INSTANCE, fixture.venue.id(), new WaitlistEntryId(first.id()), otherSlot.slot.id());
                    case "inactive" -> jdbc.update("UPDATE resources SET status = 'INACTIVE' WHERE id = ?", bytes(fixture.resource.id().value()));
                    case "ordinary-hold" -> hold(fixture, customer());
                    case "new-demand" -> {
                        waitlist.cancel(fixture.venue.id(), new WaitlistEntryId(first.id()), first.customer());
                        waitlist.cancel(fixture.venue.id(), new WaitlistEntryId(next.id()), next.customer());
                        entry(fixture, 3);
                    }
                    case "seating" -> jdbc.update("UPDATE resources SET seating_capacity = 1 WHERE id = ?", bytes(fixture.resource.id().value()));
                    case "policy" -> venues.updateBookingPolicy(new VenueConfigurationUseCase.UpdateBookingPolicy(
                        fixture.tenant.id(), fixture.venue.id(), new BookingPolicyTerms(30, 7, 20, 10)));
                }
            } finally { proceed.countDown(); }
            work.get(8, TimeUnit.SECONDS);
        }
        if (change.equals("inactive")) assertOutcome(key, "NOT_ELIGIBLE");
        else if (change.equals("ordinary-hold")) assertOutcome(key, "NO_CAPACITY");
        else if (change.equals("seating")) assertOutcome(key, "NO_CANDIDATE");
        else if (change.equals("new-demand")) assertOutcome(key, "PROMOTED");
        else if (change.equals("policy")) {
            assertOutcome(key, "PROMOTED");
            assertThat(jdbc.queryForObject("SELECT applied_policy_version FROM reservations WHERE id = ?",
                Long.class, receipt(key).get("reservation_id"))).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT expires_at FROM waitlist_offers WHERE id = ?",
                LocalDateTime.class, receipt(key).get("offer_id"))).isEqualTo(LocalDateTime.ofInstant(clock.instant().plusSeconds(420), ZoneOffset.UTC));
        }
        else {
            assertOutcome(key, "PROMOTED");
            assertThat((byte[]) receipt(key).get("entry_id")).isEqualTo(bytes(next.id()));
        }
        assertEffectiveCapacity(fixture);
    }

    @Test void candidateQueryDoesNotLockUnrelatedTimeOrExclusivelyLockImmutableDemand() throws Exception {
        Fixture fixture = fixture();
        Fixture later = extraSlot(fixture, 4, START.plusSeconds(1800));
        Entry unrelated = entry(later, 2);
        clock.set(NOW.plusSeconds(1));
        Entry eligible = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        var worker = worker(event -> { }, event -> {
            try (Connection observer = connection()) {
                lock(observer, "SELECT id FROM waitlist_entries WHERE id = ? FOR UPDATE NOWAIT", unrelated.id());
                UUID demand = jdbc.queryForObject("SELECT demand_id FROM waitlist_entries WHERE id = ?",
                    (row, n) -> uuid(row.getBytes(1)), bytes(eligible.id()));
                lock(observer, "SELECT id FROM waitlist_demands WHERE id = ? FOR SHARE NOWAIT", demand);
                observer.rollback();
            } catch (Exception failure) { throw new IllegalStateException("Unrelated row was locked", failure); }
        });
        worker.process(worker.claim(key).orElseThrow());
        assertPromoted(fixture, key, eligible.id());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void releaseAndRequestWorkersSerializeSameSlotAndRevalidateSharedEntryOnDifferentSlots(boolean differentSlot) throws Exception {
        Fixture fixture = fixture();
        Fixture secondSlot = differentSlot ? extraSlot(fixture, 4, START) : fixture;
        StoredEvent release = release(fixture);
        Entry first = entry(fixture, 2);
        clock.set(NOW.plusSeconds(1));
        Entry next = entry(fixture, 2);
        DeliveryKey firstKey = key(release);
        DeliveryKey secondKey = key(request(secondSlot));
        CountDownLatch effect = new CountDownLatch(1), commit = new CountDownLatch(1);
        var firstWorker = worker(event -> { }, event -> { effect.countDown(); await(commit); });
        var firstClaim = firstWorker.claim(firstKey).orElseThrow();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> firstWorker.process(firstClaim));
            try {
                await(effect);
                var two = pool.submit(() -> process(secondKey));
                if (differentSlot) {
                    two.get(5, TimeUnit.SECONDS);
                    assertThat(deliveryState(secondKey)).isEqualTo("PENDING");
                    assertThat(failureCode(secondKey)).isEqualTo("DB_LOCK_TRANSIENT");
                } else awaitWait("slot_inventories");
                commit.countDown();
                one.get(8, TimeUnit.SECONDS); two.get(8, TimeUnit.SECONDS);
            } finally { commit.countDown(); }
        }
        assertOutcome(firstKey, "PROMOTED");
        assertThat((byte[]) receipt(firstKey).get("entry_id")).isEqualTo(bytes(first.id()));
        if (differentSlot) {
            process(secondKey);
            assertOutcome(secondKey, "PROMOTED");
            assertThat((byte[]) receipt(secondKey).get("entry_id")).isEqualTo(bytes(next.id()));
        } else assertOutcome(secondKey, "NO_CAPACITY");
        assertThat(count("waitlist_offers", fixture)).isEqualTo(differentSlot ? 2 : 1);
        assertEffectiveCapacity(fixture); assertEffectiveCapacity(secondSlot);
    }

    @Test void finalJpaFlushFailureRollsBackJdbcEffectsAndDone() {
        Fixture fixture = fixture();
        Entry entry = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        var worker = worker(event -> { }, event -> {
            var entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory);
            Object reservation = entityManager.createQuery("from ReservationJpaEntity where promotionalRequestId = :entry")
                .setParameter("entry", entry.id()).getSingleResult();
            ReflectionTestUtils.setField(reservation, "partySize", 0);
        });
        worker.process(worker.claim(key).orElseThrow());
        assertFailure(key, "UNCLASSIFIED_FAILURE");
        assertNoEffect(fixture, key);
        assertThat(entryState(entry.id())).isEqualTo("WAITING");
        redeliver(key);
        assertPromoted(fixture, key, entry.id());
    }

    @Test void handlersRequireJoinedTransactionAndOuterRollbackUndoesCompleteBusinessEffect() {
        Fixture fixture = fixture();
        Entry entry = entry(fixture, 2);
        StoredEvent event = request(fixture);
        DeliveryKey key = key(event);
        assertThatThrownBy(() -> requestHandler.handle(event))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        transaction().executeWithoutResult(status -> {
            requestHandler.handle(event);
            assertThat(count("waitlist_offers", fixture)).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertNoEffect(fixture, key);
        assertThat(deliveryState(key)).isEqualTo("PENDING");
        process(key);
        assertPromoted(fixture, key, entry.id());
    }

    @Test void lateAndReverseReleaseDeliveryReevaluatesCurrentStateRatherThanApplyingDeltas() {
        Fixture fixture = fixture();
        StoredEvent older = release(fixture);
        StoredEvent newer = release(fixture);
        Entry first = entry(fixture, 2);
        clock.set(NOW.plusSeconds(1));
        entry(fixture, 2);
        DeliveryKey late = key(older), reverse = key(newer);
        process(reverse);
        assertPromoted(fixture, reverse, first.id());
        process(late);
        assertOutcome(late, "NO_CAPACITY");
        assertThat(count("waitlist_offers", fixture)).isEqualTo(1);
        clock.set(START);
        DeliveryKey past = key(request(fixture));
        process(past);
        assertOutcome(past, "SLOT_PAST");
    }

    @ParameterizedTest @ValueSource(strings = {"hold", "replacement", "confirm"})
    void ordinaryWinnerIsAuthoritativeAfterPromotionTargetSnapshot(String command) throws Exception {
        Fixture fixture = fixture();
        var customer = customer();
        ReservationId old = command.equals("hold") ? null : hold(fixture, customer);
        Entry entry = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        clock.set(NOW.plusSeconds(command.equals("confirm") ? 299 : 301));
        CountDownLatch effect = new CountDownLatch(1), commit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ordinary = pool.submit(() -> transaction().executeWithoutResult(status -> {
                if (command.equals("confirm")) booking.transition(fixture.venue.id(), old, customer, ReservationCommand.CONFIRM);
                else hold(fixture, customer);
                effect.countDown(); await(commit);
            }));
            try {
                await(effect);
                clock.set(NOW.plusSeconds(301));
                var promotion = pool.submit(() -> process(key));
                awaitWait("slot_inventories");
                commit.countDown();
                ordinary.get(8, TimeUnit.SECONDS); promotion.get(8, TimeUnit.SECONDS);
            } finally { commit.countDown(); }
        }
        assertOutcome(key, "NO_CAPACITY");
        assertThat(entryState(entry.id())).isEqualTo("WAITING");
        assertEffectiveCapacity(fixture);
    }

    @ParameterizedTest @ValueSource(strings = {"hold", "replacement", "confirm"})
    void promotionWinnerPreventsOrdinaryHoldReplacementAndPreExpiryCapturedConfirm(String command) throws Exception {
        Fixture fixture = fixture();
        var customer = customer();
        ReservationId old = command.equals("hold") ? null : hold(fixture, customer);
        Entry entry = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        CountDownLatch captured = new CountDownLatch(1), enterSlot = new CountDownLatch(1);
        CountDownLatch effect = new CountDownLatch(1), commit = new CountDownLatch(1);
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("ordinary-contender")) { captured.countDown(); await(enterSlot); }
            return call.callRealMethod();
        }).when(slotRepository).findForUpdate(any(), any());
        clock.set(NOW.plusSeconds(command.equals("confirm") ? 299 : 301));
        try (var ordinaryPool = Executors.newSingleThreadExecutor(r -> new Thread(r, "ordinary-contender"));
             var promotionPool = Executors.newSingleThreadExecutor()) {
            var ordinary = ordinaryPool.submit(() -> {
                if (command.equals("confirm")) return booking.transition(fixture.venue.id(), old, customer, ReservationCommand.CONFIRM).reservation().id();
                return hold(fixture, customer);
            });
            try {
                await(captured);
                clock.set(NOW.plusSeconds(301));
                var worker = worker(event -> { }, event -> { effect.countDown(); await(commit); });
                var claim = worker.claim(key).orElseThrow();
                var promotion = promotionPool.submit(() -> worker.process(claim));
                await(effect);
                enterSlot.countDown();
                awaitWait("slot_inventories");
                commit.countDown();
                promotion.get(8, TimeUnit.SECONDS);
                assertThatThrownBy(() -> ordinary.get(8, TimeUnit.SECONDS)).hasCauseInstanceOf(CapacityUnavailableException.class);
            } finally { enterSlot.countDown(); commit.countDown(); }
        }
        assertPromoted(fixture, key, entry.id());
    }

    @Test void resourceAndDemandNowaitConflictsRemainRetryableWithoutPartialReceipts() throws Exception {
        Fixture fixture = fixture();
        Entry entry = entry(fixture, 2);
        for (String table : List.of("resources", "waitlist_demands")) {
            DeliveryKey key = key(request(fixture));
            UUID id = table.equals("resources") ? fixture.resource.id().value()
                : jdbc.queryForObject("SELECT demand_id FROM waitlist_entries WHERE id = ?", (row, n) -> uuid(row.getBytes(1)), bytes(entry.id()));
            try (Connection owner = connection()) {
                if (table.equals("waitlist_demands")) {
                    // The immutable covering identity index is the real #94 Demand lock. A PK-only
                    // X lock without changing any identity value need not conflict with its S lock.
                    owner.setAutoCommit(false);
                    try (var query = owner.prepareStatement("SELECT id FROM waitlist_demands FORCE INDEX (uk_waitlist_demands_identity) WHERE tenant_id = ? AND venue_id = ? FOR UPDATE")) {
                        query.setBytes(1, bytes(fixture.tenant.id().value())); query.setBytes(2, bytes(fixture.venue.id().value()));
                        try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); }
                    }
                } else lock(owner, "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE", id);
                process(key);
                assertThat(deliveryState(key)).as("%s conflict: failure=%s receipt=%s", table, failureCode(key),
                    receiptCount(key) == 0 ? null : receipt(key).get("outcome")).isEqualTo("PENDING");
                assertThat(failureCode(key)).isEqualTo("DB_LOCK_TRANSIENT");
                assertNoEffect(fixture, key);
                owner.rollback();
            }
        }
    }

    @Test void fifoLocksOnlyTheHeadNotTheWaitingQueueAndTheIndexAvoidsInnerFilesort() throws Exception {
        Fixture firstSlot = fixture();
        Fixture secondSlot = extraSlot(firstSlot, 4, START.plusSeconds(1800));
        Entry first = entry(firstSlot, 2);
        clock.set(NOW.plusSeconds(1));
        for (int i = 0; i < 8; i++) entry(firstSlot, 2);
        Entry second = entry(secondSlot, 2);
        String sql = (String) ReflectionTestUtils.getField(Class.forName("com.slotq.waitlist.persistence.JdbcWaitlistEntryRepository"), "PROMOTION_SELECT");
        var plan = new JsonMapper().readTree(jdbc.queryForObject("EXPLAIN FORMAT=JSON " + sql, String.class,
            bytes(firstSlot.tenant.id().value()), bytes(firstSlot.venue.id().value()),
            java.sql.Timestamp.from(START), java.sql.Timestamp.from(START.plusSeconds(1800)), 4));
        assertThat(plan.toString()).contains("idx_waitlist_entries_promotion");
        var innerSort = plan.at("/query_block/ordering_operation/nested_loop/1/table/materialized_from_subquery/query_block/ordering_operation/using_filesort");
        assertThat(innerSort.isBoolean()).isTrue();
        assertThat(innerSort.asBoolean()).isFalse();
        DeliveryKey oneKey = key(request(firstSlot)), twoKey = key(request(secondSlot));
        var worker = worker(event -> { }, event -> {
            long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            try (var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                 var query = observer.prepareStatement("""
                     SELECT COUNT(*) FROM performance_schema.data_locks locks
                     JOIN performance_schema.threads thread ON thread.THREAD_ID = locks.THREAD_ID
                     WHERE thread.PROCESSLIST_ID = ? AND locks.OBJECT_NAME = 'waitlist_entries'
                       AND locks.INDEX_NAME = 'PRIMARY' AND locks.LOCK_TYPE = 'RECORD' AND locks.LOCK_MODE LIKE 'X%'
                     """)) {
                query.setLong(1, connectionId);
                try (var rows = query.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        worker.process(worker.claim(oneKey).orElseThrow());
        worker.process(worker.claim(twoKey).orElseThrow());
        assertOutcome(oneKey, "PROMOTED"); assertOutcome(twoKey, "PROMOTED");
        assertThat((byte[]) receipt(oneKey).get("entry_id")).isEqualTo(bytes(first.id()));
        assertThat((byte[]) receipt(twoKey).get("entry_id")).isEqualTo(bytes(second.id()));
        assertThat(count("waitlist_offers", firstSlot)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fifoAcrossEligiblePartySizesLocksOneHeadPerDemandRatherThanEveryWaitingEntry(boolean tied) {
        Fixture fixture = fixture();
        List<Entry> heads = new java.util.ArrayList<>();
        for (int partySize = 1; partySize <= 3; partySize++) {
            clock.set(tied ? NOW : NOW.plusSeconds(partySize == 2 ? 0 : partySize));
            heads.add(entry(fixture, partySize));
        }
        clock.set(NOW.plusSeconds(4));
        for (int partySize = 1; partySize <= 3; partySize++) {
            for (int tail = 0; tail < 4; tail++) entry(fixture, partySize);
        }
        UUID expected = jdbc.queryForObject("""
            SELECT id FROM waitlist_entries WHERE id IN (?, ?, ?) ORDER BY joined_at, id LIMIT 1
            """, (row, n) -> uuid(row.getBytes(1)),
            bytes(heads.get(0).id()), bytes(heads.get(1).id()), bytes(heads.get(2).id()));
        if (!tied) assertThat(expected).isEqualTo(heads.get(1).id());
        DeliveryKey key = key(request(fixture));
        var worker = worker(event -> { }, event -> {
            try {
                var locks = recordLocks(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                assertThat(locks.stream().filter(row -> row.startsWith("waitlist_entries|PRIMARY|X")))
                    .as("three eligible Demand heads, not the fifteen-entry queue").hasSize(3);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        worker.process(worker.claim(key).orElseThrow());
        assertPromoted(fixture, key, expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_entries WHERE venue_id = ? AND state = 'WAITING'",
            Integer.class, bytes(fixture.venue.id().value()))).isEqualTo(14);
    }

    // Opt-in baseline evidence; engine gap deadlock occurrence is not a general regression success criterion.
    @Test @EnabledIfEnvironmentVariable(named = "SLOTQ_CAPACITY_LOCK_EVIDENCE", matches = "true")
    void diagnosticBaselineCapacityGapIsEngineTransient() throws Exception {
        Fixture fixture = fixture();
        Fixture other = extraSlot(fixture, 4, START.plusSeconds(1800));
        Entry first = entry(fixture, 2), second = entry(other, 2);
        DeliveryKey oneKey = key(request(fixture)), twoKey = key(request(other));
        CountDownLatch bothCapacityReads = new CountDownLatch(2);
        doAnswer(call -> {
            Object occupied = call.callRealMethod();
            bothCapacityReads.countDown(); await(bothCapacityReads);
            return occupied;
        }).when(reservationRepository).existsEffectiveCapacityConsumerCurrent(any(), any(), any(), any(), any(), any());
        var worker = worker();
        var oneClaim = worker.claim(oneKey).orElseThrow();
        var twoClaim = worker.claim(twoKey).orElseThrow();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> worker.process(oneClaim));
            var two = pool.submit(() -> worker.process(twoClaim));
            org.junit.jupiter.api.Assertions.assertAll(() -> one.get(8, TimeUnit.SECONDS), () -> two.get(8, TimeUnit.SECONDS));
        }
        assertThat(List.of(deliveryState(oneKey), deliveryState(twoKey))).containsExactlyInAnyOrder("DONE", "PENDING");
        DeliveryKey loser = deliveryState(oneKey).equals("PENDING") ? oneKey : twoKey;
        assertThat(failureCode(loser)).isEqualTo("DB_LOCK_TRANSIENT");
        assertThat(receiptCount(loser)).isZero();
        assertThat(entryState(loser.equals(oneKey) ? first.id() : second.id())).isEqualTo("WAITING");
        assertThat(count("waitlist_offers", fixture)).isEqualTo(1);
        assertThat(count("waitlist_notification_requests", fixture)).isEqualTo(1);
        try (var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = observer.createStatement(); var rows = statement.executeQuery("SHOW ENGINE INNODB STATUS")) {
            rows.next();
            assertThat(rows.getString("Status")).contains("LATEST DETECTED DEADLOCK", "insert intention waiting")
                .containsAnyOf("uk_reservations_scope_id", "idx_capacity_allocations_effective");
        }
        process(loser);
        assertOutcome(oneKey, "PROMOTED"); assertOutcome(twoKey, "PROMOTED");
        assertThat(count("waitlist_offers", fixture)).isEqualTo(2);
        assertThat(count("waitlist_notification_requests", fixture)).isEqualTo(2);
        assertEffectiveCapacity(fixture); assertEffectiveCapacity(other);
    }

    @ParameterizedTest @ValueSource(ints = {1, 3})
    void independentMysql1213RollsBackWholeEffectAndBoundedRetryCommitsOnce(int failures) throws Exception {
        Fixture fixture = fixture();
        Entry entry = entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        jdbc.execute("CREATE TABLE promotion_deadlock_probe (id INT PRIMARY KEY, value INT NOT NULL) ENGINE=InnoDB");
        for (int id = 1; id <= 130; id++) jdbc.update("INSERT INTO promotion_deadlock_probe VALUES (?,0)", id);
        try {
            for (int cycle = 0; cycle < failures; cycle++) {
                long deadlocksBefore = mysqlDeadlockCount();
                CountDownLatch effectWritten = new CountDownLatch(1);
                try (var blocker = connection(); var pool = Executors.newSingleThreadExecutor()) {
                    blocker.setAutoCommit(false);
                    // A heavier test-only transaction makes the real promotion transaction the InnoDB victim.
                    try (var query = blocker.createStatement()) {
                        query.executeUpdate("UPDATE promotion_deadlock_probe SET value=value+1 WHERE id<>2");
                    }
                    var competing = pool.submit(() -> {
                        await(effectWritten);
                        try (var query = blocker.createStatement()) {
                            query.executeUpdate("UPDATE promotion_deadlock_probe SET value=2 WHERE id=2");
                        }
                        blocker.commit();
                        return null;
                    });
                    var worker = worker(event -> { }, event -> {
                        jdbc.update("UPDATE promotion_deadlock_probe SET value=3 WHERE id=2");
                        effectWritten.countDown();
                        try { awaitWait("promotion_deadlock_probe"); }
                        catch (Exception failure) { throw new IllegalStateException(failure); }
                        jdbc.update("UPDATE promotion_deadlock_probe SET value=3 WHERE id=1");
                    });
                    worker.process(worker.claim(key).orElseThrow());
                    competing.get(5, TimeUnit.SECONDS);
                }
                assertThat(mysqlDeadlockCount()).isEqualTo(deadlocksBefore + 1);
                assertThat(deliveryState(key)).isEqualTo(cycle == 2 ? "DEAD" : "PENDING");
                assertThat(failureCode(key)).isEqualTo("DB_LOCK_TRANSIENT");
                assertNoEffect(fixture, key);
                assertThat(entryState(entry.id())).isEqualTo("WAITING");
                try (var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                     var statement = observer.createStatement(); var rows = statement.executeQuery("SHOW ENGINE INNODB STATUS")) {
                    rows.next();
                    String trace = rows.getString("Status");
                    assertThat(trace).contains("LATEST DETECTED DEADLOCK", "promotion_deadlock_probe");
                    var evidence = java.nio.file.Path.of("build", "waitlist-promotion-deadlocks");
                    java.nio.file.Files.createDirectories(evidence);
                    java.nio.file.Files.writeString(evidence.resolve("budget-" + failures + "-attempt-" + (cycle + 1) + ".txt"),
                        trace.substring(trace.indexOf("LATEST DETECTED DEADLOCK"), trace.indexOf("TRANSACTIONS\n")));
                }
            }
        } finally { jdbc.execute("DROP TABLE promotion_deadlock_probe"); }
        assertThat(jdbc.queryForObject("SELECT cycle_attempts FROM event_deliveries WHERE event_id=?",
            Integer.class, bytes(key.eventId().value()))).isEqualTo(failures);
        if (failures == 3) {
            assertThat(worker().claim(key)).isEmpty();
            new EventReplayService(deliveries, deliveryTransactions()).replay(SystemPrincipal.INSTANCE, key, "test 1213 recovery");
        }
        process(key);
        assertPromoted(fixture, key, entry.id());
    }

    @Test void receiptResultAndNotificationForeignKeysCannotDescribeAnotherEffect() {
        Fixture fixture = fixture();
        entry(fixture, 2);
        DeliveryKey key = key(request(fixture));
        process(key);
        assertThatThrownBy(() -> jdbc.update("UPDATE waitlist_promotion_receipts SET reservation_id = ? WHERE event_id = ?",
            bytes(UUID.randomUUID()), bytes(key.eventId().value())))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE waitlist_promotion_receipts SET outcome = NULL WHERE event_id = ?",
            bytes(key.eventId().value())))
            .hasMessageContaining("chk_promotion_result");
        assertThatThrownBy(() -> jdbc.update("UPDATE waitlist_notification_requests SET entry_id = ? WHERE offer_id = ?",
            bytes(UUID.randomUUID()), receipt(key).get("offer_id")))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertOutcome(key, "PROMOTED");
    }

    private Fixture fixture() {
        Tenant tenant = tenants.createTenant();
        Venue venue = venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(), "Promotion", "UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY, new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(14, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10)));
        Resource resource = resources.createResource(new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Table", 4));
        SlotInventory slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(), venue.id(), resource.id(), START.toString()));
        return new Fixture(tenant, venue, resource, slot);
    }
    private Fixture extraSlot(Fixture source, int capacity, Instant start) {
        Resource resource = resources.createResource(new ResourceUseCase.CreateResource(source.tenant.id(), source.venue.id(), "Other", capacity));
        SlotInventory slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(source.tenant.id(), source.venue.id(), resource.id(), start.toString()));
        return new Fixture(source.tenant, source.venue, resource, slot);
    }
    private AuthenticatedPrincipal customer() {
        var customer = new AuthenticatedPrincipal(PrincipalId.newId());
        access.registerPrincipal(customer.principalId());
        return customer;
    }
    private Entry entry(Fixture fixture, int partySize) {
        var customer = customer();
        var entry = waitlist.register(new WaitlistUseCase.CreateRegistration(fixture.venue.id(), fixture.slot.id(), partySize,
            new WaitlistRegistrationKey(UUID.randomUUID()), customer)).entry();
        return new Entry(entry.id(), customer);
    }
    private ReservationId hold(Fixture fixture, AuthenticatedPrincipal customer) {
        return booking.createHold(new ReservationUseCase.CreateHold(fixture.venue.id(), fixture.slot.id(), customer, 2)).reservation().id();
    }
    private StoredEvent release(Fixture fixture) {
        var customer = customer();
        ReservationId id = hold(fixture, customer);
        booking.transition(fixture.venue.id(), id, customer, ReservationCommand.CANCEL);
        EventId event = jdbc.queryForObject("SELECT event_id FROM event_records WHERE aggregate_id = ?",
            (row, n) -> new EventId(uuid(row.getBytes(1))), bytes(id.value()));
        return transaction().execute(status -> records.findEventForAppend(event).orElseThrow());
    }
    private StoredEvent request(Fixture fixture) {
        String payload = new JsonMapper().writeValueAsString(Map.of("venueId", fixture.venue.id().value().toString(),
            "resourceId", fixture.resource.id().value().toString(), "slotInventoryId", fixture.slot.id().value().toString()));
        return append(new EventEnvelope(EventId.newId(), fixture.tenant.id(), "SlotInventory", fixture.slot.id().value(), REQUEST.eventType(), 1, NOW, payload));
    }
    private StoredEvent append(EventEnvelope event) { return transaction().execute(status -> append.append(event)); }
    private DeliveryKey key(StoredEvent event) {
        worker().materialize();
        return new DeliveryKey(event.envelope().tenantId(), event.envelope().eventId(),
            active.get(new ConsumerRoute("waitlist.promotion", event.envelope().eventType(), event.envelope().schemaVersion())));
    }
    private void process(DeliveryKey key) {
        var worker = worker();
        worker.process(worker.claim(key).orElseThrow());
    }
    private EventDeliveryWorker worker() { return worker(event -> { }, event -> { }); }
    private EventDeliveryWorker worker(Consumer<StoredEvent> before, Consumer<StoredEvent> after) {
        var wrapped = List.of(releaseHandler, requestHandler).stream().map(actual -> (EventHandler) new EventHandler() {
            @Override public ConsumerRoute route() { return actual.route(); }
            @Override public void handle(StoredEvent event) { before.accept(event); actual.handle(event); after.accept(event); }
        }).toList();
        return new EventDeliveryWorker(deliveries, deliveryTransactions(), POLICY, new EventHandlers(wrapped), canonicalizer, entityManagerFactory);
    }
    private DeliveryTransactions deliveryTransactions() { return new DeliveryTransactions(manager, deliveries, POLICY); }
    private TransactionTemplate transaction() { return new TransactionTemplate(manager); }
    private void redeliver(DeliveryKey key) {
        // Test-only transport reset simulates redelivery of a previously applied identity. Production
        // exposes only trusted DEAD replay, never DONE reset or receipt deletion.
        jdbc.update("UPDATE event_deliveries SET state = 'DEAD', lease_until = NULL, next_attempt_at = NULL WHERE event_id = ?", bytes(key.eventId().value()));
        new EventReplayService(deliveries, deliveryTransactions()).replay(SystemPrincipal.INSTANCE, key, "test identity replay");
        process(key);
    }
    private String deliveryState(DeliveryKey key) { return jdbc.queryForObject("SELECT state FROM event_deliveries WHERE event_id = ? AND registration_id = ?", String.class, bytes(key.eventId().value()), bytes(key.registrationId())); }
    private String failureCode(DeliveryKey key) { return jdbc.queryForObject("SELECT failure_code FROM event_deliveries WHERE event_id = ? AND registration_id = ?", String.class, bytes(key.eventId().value()), bytes(key.registrationId())); }
    private Map<String, Object> receipt(DeliveryKey key) { return jdbc.queryForMap("SELECT * FROM waitlist_promotion_receipts WHERE tenant_id = ? AND event_id = ?", bytes(key.tenantId().value()), bytes(key.eventId().value())); }
    private int receiptCount(DeliveryKey key) { return jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_promotion_receipts WHERE tenant_id = ? AND event_id = ?", Integer.class, bytes(key.tenantId().value()), bytes(key.eventId().value())); }
    private long count(String table, Fixture fixture) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE venue_id = ?", Long.class, bytes(fixture.venue.id().value())); }
    private String entryState(UUID id) { return jdbc.queryForObject("SELECT state FROM waitlist_entries WHERE id = ?", String.class, bytes(id)); }
    private void assertOutcome(DeliveryKey key, String outcome) {
        assertThat(deliveryState(key)).isEqualTo("DONE");
        assertThat(receiptCount(key)).isEqualTo(1);
        assertThat(receipt(key).get("outcome")).isEqualTo(outcome);
    }
    private void assertFailure(DeliveryKey key, String failure) {
        assertThat(deliveryState(key)).isEqualTo("DEAD");
        assertThat(failureCode(key)).isEqualTo(failure);
    }
    private void assertPromoted(Fixture fixture, DeliveryKey key, UUID entryId) {
        assertOutcome(key, "PROMOTED");
        assertThat((byte[]) receipt(key).get("entry_id")).isEqualTo(bytes(entryId));
        assertThat(entryState(entryId)).isEqualTo("OFFERED");
        assertThat(count("waitlist_offers", fixture)).isEqualTo(1);
        assertThat(count("waitlist_notification_requests", fixture)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM waitlist_promotion_receipts p
            JOIN waitlist_offers o ON o.tenant_id = p.tenant_id AND o.id = p.offer_id AND o.entry_id = p.entry_id AND o.reservation_id = p.reservation_id
            JOIN reservations r ON r.id = o.reservation_id AND r.promotional_request_id = o.entry_id
            JOIN capacity_allocations a ON a.reservation_id = r.id AND a.active = TRUE
            JOIN waitlist_notification_requests n ON n.tenant_id = o.tenant_id AND n.offer_id = o.id AND n.expires_at = o.expires_at
            WHERE p.event_id = ? AND o.state = 'PENDING' AND r.state = 'HELD'
            """, Integer.class, bytes(key.eventId().value()))).isEqualTo(1);
        assertEffectiveCapacity(fixture);
    }
    private void assertNoEffect(Fixture fixture, DeliveryKey key) {
        for (String table : List.of("reservations", "capacity_allocations", "waitlist_offers", "waitlist_notification_requests")) assertThat(count(table, fixture)).as(table).isZero();
        assertThat(receiptCount(key)).isZero();
    }
    private void assertEffectiveCapacity(Fixture fixture) {
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM reservations r JOIN capacity_allocations a ON a.reservation_id = r.id
            WHERE r.slot_inventory_id = ? AND a.active = TRUE
              AND (r.state IN ('CONFIRMED','CHECKED_IN') OR (r.state = 'HELD' AND r.expires_at > ?))
            """, Integer.class, bytes(fixture.slot.id().value()), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))).isLessThanOrEqualTo(1);
    }
    private Connection connection() throws Exception { return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); }
    private List<String> recordLocks(long connectionId) throws Exception {
        try (var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var query = observer.prepareStatement("""
                 SELECT l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_MODE,l.LOCK_STATUS,l.LOCK_DATA
                 FROM performance_schema.data_locks l JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID
                 WHERE l.OBJECT_SCHEMA=? AND l.LOCK_TYPE='RECORD' AND t.PROCESSLIST_ID=?
                 ORDER BY l.OBJECT_NAME,l.INDEX_NAME,l.LOCK_DATA
                 """)) {
            query.setString(1, MYSQL.getDatabaseName()); query.setLong(2, connectionId);
            try (var rows = query.executeQuery()) {
                List<String> result = new java.util.ArrayList<>();
                while (rows.next()) result.add(rows.getString(1) + "|" + rows.getString(2) + "|" + rows.getString(3)
                    + "|" + rows.getString(4) + "|" + rows.getString(5));
                return result;
            }
        }
    }
    private long mysqlDeadlockCount() throws Exception {
        try (var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var query = observer.createStatement(); var rows = query.executeQuery(
                 "SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME='lock_deadlocks'")) {
            assertThat(rows.next()).isTrue(); return rows.getLong(1);
        }
    }
    private void lock(Connection connection, String sql, UUID id) throws Exception {
        connection.setAutoCommit(false);
        try (var query = connection.prepareStatement(sql)) { query.setBytes(1, bytes(id)); try (var rows = query.executeQuery()) { assertThat(rows.next()).isTrue(); } }
    }
    private void awaitWait(String table) throws Exception {
        try (var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var query = observer.prepareStatement("""
                 SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l
                 ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA = ? AND l.OBJECT_NAME = ?
                 """)) {
            query.setString(1, MYSQL.getDatabaseName()); query.setString(2, table);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                try (var rows = query.executeQuery()) { rows.next(); if (rows.getLong(1) > 0) return; }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            throw new AssertionError("No MySQL lock wait on " + table);
        }
    }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static UUID uuid(byte[] bytes) { var value = ByteBuffer.wrap(bytes); return new UUID(value.getLong(), value.getLong()); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(6, TimeUnit.SECONDS)) throw new AssertionError("gate timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    record Fixture(Tenant tenant, Venue venue, Resource resource, SlotInventory slot) { }
    record Entry(UUID id, AuthenticatedPrincipal customer) { }
    @TestConfiguration static class FixtureConfiguration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
        @Bean CapacityReleaseReadiness testReadiness() { return () -> true; }
    }
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        void set(Instant value) { now.set(value); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
