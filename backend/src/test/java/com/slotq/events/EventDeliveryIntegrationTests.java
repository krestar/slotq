package com.slotq.events;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryClaim;
import com.slotq.events.application.DeliveryFailure;
import com.slotq.events.application.DeliveryKey;
import com.slotq.events.application.DeliveryPolicy;
import com.slotq.events.application.DeliverySnapshot;
import com.slotq.events.application.DeliveryTransactions;
import com.slotq.events.application.EventAppendService;
import com.slotq.events.application.EventCanonicalizer;
import com.slotq.events.application.EventDeliveryStore;
import com.slotq.events.application.EventDeliveryWorker;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventHandler;
import com.slotq.events.application.EventHandlers;
import com.slotq.events.application.EventHandlingException;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventOwnershipLostException;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.events.application.EventReplayService;
import com.slotq.events.application.StoredEvent;
import com.slotq.tenancy.domain.TenantId;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.slotq.events.application.DeliverySnapshot.State.DEAD;
import static com.slotq.events.application.DeliverySnapshot.State.DONE;
import static com.slotq.events.application.DeliverySnapshot.State.PENDING;
import static com.slotq.events.application.DeliverySnapshot.State.PROCESSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = "slotq.events.delivery.scheduler-enabled=false")
class EventDeliveryIntegrationTests {

    @Container
    @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_event_delivery");

    private static final ConsumerRoute ROUTE = new ConsumerRoute("SyntheticProjection", "SyntheticChanged", 1);
    private static final DeliveryPolicy POLICY = new DeliveryPolicy(3, Duration.ofSeconds(6),
        Duration.ofSeconds(3), Duration.ofSeconds(1), 100, List.of(Duration.ZERO, Duration.ZERO));

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired EventAppendService append;
    @Autowired EventRegistrationService registrations;
    @Autowired EventDeliveryStore store;
    @Autowired EventCanonicalizer canonicalizer;

    private TransactionTemplate transaction;
    private DeliveryTransactions deliveryTransactions;
    private EventReplayService replay;

    @BeforeEach
    void prepareIsolatedSyntheticConsumer() {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        transaction = new TransactionTemplate(transactionManager);
        deliveryTransactions = new DeliveryTransactions(transactionManager, store, POLICY);
        replay = new EventReplayService(store, deliveryTransactions);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS event_delivery_test_owner (
                owner_id BINARY(16) PRIMARY KEY,
                tenant_id BINARY(16) NOT NULL,
                current_revision INT NOT NULL,
                UNIQUE KEY (tenant_id, owner_id)
            ) ENGINE=InnoDB
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS event_delivery_test_effect (
                consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                owner_id BINARY(16) NOT NULL,
                tenant_id BINARY(16) NOT NULL,
                current_revision INT NOT NULL,
                applications INT NOT NULL,
                PRIMARY KEY (consumer_id, owner_id),
                FOREIGN KEY (tenant_id, owner_id) REFERENCES event_delivery_test_owner (tenant_id, owner_id)
            ) ENGINE=InnoDB
            """);
        // This receipt belongs only to this synthetic consumer; no production Inbox is introduced.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS event_delivery_test_receipt (
                consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                event_id BINARY(16) NOT NULL,
                tenant_id BINARY(16) NOT NULL,
                aggregate_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                aggregate_id BINARY(16) NOT NULL,
                event_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                schema_version INT NOT NULL,
                occurred_at DATETIME(6) NOT NULL,
                payload MEDIUMTEXT NOT NULL,
                PRIMARY KEY (consumer_id, event_id)
            ) ENGINE=InnoDB
            """);
        jdbc.update("DELETE FROM event_delivery_test_receipt");
        jdbc.update("DELETE FROM event_delivery_test_effect");
        jdbc.update("DELETE FROM event_delivery_test_owner");
        jdbc.update("DELETE FROM event_replay_audit");
        jdbc.update("DELETE FROM event_deliveries");
        jdbc.update("DELETE FROM event_records");
        jdbc.update("DELETE FROM event_registrations");
        jdbc.update("UPDATE event_discovery SET boundary_sequence = 0 WHERE singleton_id = 1");
        jdbc.update("UPDATE event_boundary SET sequence_value = 0 WHERE singleton_id = 1");
    }

    @Test
    void restartAndRepeatedScanRecoverOnlyDurableTargetsWithoutConsultingRuntimeHandlers() {
        Owner owner = owner();
        StoredEvent historical = append(owner);
        UUID registration = registrations.activate(ROUTE);
        StoredEvent committed = append(owner);
        assertThat(deliveryCount()).isZero();

        EventDeliveryWorker restartedWithoutHandlers = worker();
        assertThat(restartedWithoutHandlers.materialize()).isEqualTo(1);
        assertThat(worker(handler(ROUTE, this::apply)).materialize()).isZero();
        assertThat(worker().materialize()).isZero();

        assertThat(deliveriesFor(historical)).isZero();
        DeliveryKey key = key(committed, registration);
        assertThat(snapshot(key).state()).isEqualTo(PENDING);
        assertThat(snapshot(key).cycleAttempts()).isZero();
        assertThat(deliveryCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT boundary_sequence FROM event_discovery", Long.class))
            .isEqualTo(committed.boundarySequence());

        // Installing the exact handler later changes resolution, never the durable target identity.
        assertThat(worker(handler(ROUTE, this::apply)).runCycle()).isEqualTo(1);
        assertThat(snapshot(key).state()).isEqualTo(DONE);
        assertEffect(owner, 1);
        assertThat(receipt(ROUTE.consumerId(), committed.envelope().eventId()))
            .contains(committed.envelope());
    }

    @Test
    void concurrentMaterializationCreatesOneRowPerOriginalRegistrationTarget() throws Exception {
        UUID registration = registrations.activate(ROUTE);
        StoredEvent event = append(owner());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> { await(start); return worker().materialize(); });
            Future<Integer> second = executor.submit(() -> { await(start); return worker().materialize(); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(snapshot(key(event, registration)).state()).isEqualTo(PENDING);
        assertThat(deliveryCount()).isEqualTo(1);
    }

    @Test
    void deactivationPreservesUnfinishedAndNotYetMaterializedTargetsAcrossNewRegistrationGeneration() {
        UUID original = registrations.activate(ROUTE);
        Owner owner = owner();
        StoredEvent pending = append(owner);
        StoredEvent processing = append(owner);
        StoredEvent dead = append(owner);
        StoredEvent undiscovered = append(owner);
        DeliveryPolicy firstThree = new DeliveryPolicy(3, Duration.ofSeconds(6), Duration.ofSeconds(3),
            Duration.ofSeconds(1), 3, List.of(Duration.ZERO, Duration.ZERO));
        assertThat(worker(firstThree).materialize()).isEqualTo(3);
        worker().claim(key(processing, original)).orElseThrow();
        process(worker(), key(dead, original));
        List<DeliverySnapshot> before = List.of(snapshot(key(pending, original)),
            snapshot(key(processing, original)), snapshot(key(dead, original)));
        assertThat(before).extracting(DeliverySnapshot::state).containsExactly(PENDING, PROCESSING, DEAD);

        assertThat(registrations.deactivate(original)).isTrue();
        StoredEvent afterDeactivation = append(owner);
        assertThat(worker().materialize()).isEqualTo(1);
        assertThat(List.of(snapshot(key(pending, original)), snapshot(key(processing, original)),
            snapshot(key(dead, original)))).isEqualTo(before);
        assertThat(snapshot(key(undiscovered, original)).state()).isEqualTo(PENDING);
        assertThat(deliveriesFor(afterDeactivation)).isZero();

        UUID reactivated = registrations.activate(ROUTE);
        assertThat(reactivated).isNotEqualTo(original);
        StoredEvent newGeneration = append(owner);
        assertThat(worker().materialize()).isEqualTo(1);
        assertThat(snapshot(key(newGeneration, reactivated)).state()).isEqualTo(PENDING);
        assertThat(deliveriesFor(undiscovered)).isEqualTo(1);
        assertThat(deliveryCount()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE registration_id = ?",
            Integer.class, bytes(original))).isEqualTo(4);

        process(worker(handler(ROUTE, this::apply)), key(undiscovered, original));
        assertThat(snapshot(key(undiscovered, original)).state()).isEqualTo(DONE);
    }

    @Test
    void targetedMissingHandlerIsDeadWhileEventOutsideTheRegistrationIntervalHasNoFailure() {
        Owner owner = owner();
        StoredEvent historical = append(owner);
        UUID registration = registrations.activate(ROUTE);
        StoredEvent target = append(owner);
        assertThat(worker().runCycle()).isEqualTo(1);
        assertDead(key(target, registration), DeliveryFailure.TARGET_HANDLER_MISSING, 1);
        assertThat(deliveriesFor(historical)).isZero();
        assertThat(effectCount()).isZero();
    }

    @Test
    void onlyTheExactConsumerTypeAndVersionCanHandleThePersistedTarget() {
        Target target = target();
        AtomicInteger wrongHandlerCalls = new AtomicInteger();
        EventDeliveryWorker worker = worker(
            handler(new ConsumerRoute(ROUTE.consumerId().toLowerCase(), ROUTE.eventType(), 1),
                event -> wrongHandlerCalls.incrementAndGet()),
            handler(new ConsumerRoute(ROUTE.consumerId(), ROUTE.eventType().toLowerCase(), 1),
                event -> wrongHandlerCalls.incrementAndGet()),
            handler(new ConsumerRoute(ROUTE.consumerId(), ROUTE.eventType(), 2),
                event -> wrongHandlerCalls.incrementAndGet()));
        process(worker, target.key());
        assertDead(target.key(), DeliveryFailure.UNSUPPORTED_VERSION, 1);
        assertThat(wrongHandlerCalls).hasValue(0);
        assertThat(receiptCount()).isZero();
    }

    @Test
    void registrationRouteCorruptionAndInvalidPayloadAreDistinctTerminalFailures() {
        Target corruptedRoute = target();
        jdbc.update("UPDATE event_registrations SET event_type = 'WrongType' WHERE registration_id = ?",
            bytes(corruptedRoute.key().registrationId()));
        process(worker(handler(ROUTE, this::apply)), corruptedRoute.key());
        assertDead(corruptedRoute.key(), DeliveryFailure.TARGET_ROUTE_CORRUPTION, 1);

        UUID registration = registrations.activate(ROUTE);
        Owner owner = owner();
        StoredEvent invalidPayload = append(owner, "{\"signal\":false}");
        worker().materialize();
        DeliveryKey key = key(invalidPayload, registration);
        process(worker(handler(ROUTE, this::apply)), key);
        assertDead(key, DeliveryFailure.PAYLOAD_INVALID, 1);
        assertThat(effectCount()).isZero();
        assertThat(receiptCount()).isZero();
    }

    @Test
    void persistedNoncanonicalMeaningIsRejectedBeforeConsumerEffect() {
        Target target = target();
        jdbc.update("UPDATE event_records SET payload = ' { \"signal\" : true } ' WHERE event_id = ?",
            bytes(target.event().envelope().eventId().value()));
        process(worker(handler(ROUTE, this::apply)), target.key());
        assertDead(target.key(), DeliveryFailure.IDENTITY_CORRUPTION, 1);
        assertThat(effectCount()).isZero();
        assertThat(receiptCount()).isZero();
    }

    @Test
    void persistedDuplicateJsonKeysAreIdentityCorruptionEvenWhenMysqlAcceptsValidJson() {
        Target target = target();
        String corrupted = "{\"signal\":true,\"signal\":false}";
        assertThat(jdbc.queryForObject("SELECT JSON_VALID(?)", Integer.class, corrupted)).isEqualTo(1);
        jdbc.update("UPDATE event_records SET payload = ? WHERE event_id = ?", corrupted,
            bytes(target.event().envelope().eventId().value()));
        process(worker(handler(ROUTE, this::apply)), target.key());
        assertDead(target.key(), DeliveryFailure.IDENTITY_CORRUPTION, 1);
        assertThat(effectCount()).isZero();
        assertThat(receiptCount()).isZero();
    }

    @Test
    void concurrentClaimHasOneOwnerAndExpiredReclaimRejectsAllStaleEffectsAndTransitions() throws Exception {
        Target target = target();
        CountDownLatch start = new CountDownLatch(1);
        List<DeliveryClaim> claims = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<Optional<DeliveryClaim>>> futures = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                futures.add(executor.submit(() -> { await(start); return worker().claim(target.key()); }));
            }
            start.countDown();
            for (Future<Optional<DeliveryClaim>> future : futures) {
                future.get(10, TimeUnit.SECONDS).ifPresent(claims::add);
            }
        }
        assertThat(claims).hasSize(1);
        DeliveryClaim old = claims.getFirst();
        DeliverySnapshot first = snapshot(target.key());
        assertThat(first.state()).isEqualTo(PROCESSING);
        assertThat(first.cycleAttempts()).isEqualTo(1);
        assertThat(first.lifetimeAttempts()).isEqualTo(1);
        assertThat(first.fencingToken()).isEqualTo(1);
        assertThat(first.leaseUntil()).isAfter(databaseNow());
        assertThat(worker().claim(target.key())).isEmpty();

        expire(target.key());
        DeliveryClaim current = worker().claim(target.key()).orElseThrow();
        DeliverySnapshot reclaimed = snapshot(target.key());
        assertThat(current.fencingToken()).isEqualTo(old.fencingToken() + 1);
        assertThat(reclaimed.cycleAttempts()).isEqualTo(2);
        assertThat(reclaimed.lifetimeAttempts()).isEqualTo(2);
        worker(handler(ROUTE, this::apply)).process(old);
        assertThatThrownBy(() -> deliveryTransactions.execute(() -> {
            store.done(old, store.databaseNow());
            return null;
        })).isInstanceOf(EventOwnershipLostException.class);
        assertThatThrownBy(() -> deliveryTransactions.execute(() -> {
            store.fail(old, DeliveryFailure.TRANSIENT_HANDLER, store.databaseNow(), store.databaseNow());
            return null;
        })).isInstanceOf(EventOwnershipLostException.class);
        assertThat(snapshot(target.key())).isEqualTo(reclaimed);
        assertThat(effectCount()).isZero();
        assertThat(receiptCount()).isZero();

        worker(handler(ROUTE, this::apply)).process(current);
        assertThat(snapshot(target.key()).state()).isEqualTo(DONE);
        assertEffect(target.owner(), 1);
    }

    @Test
    void repeatedCrashAfterCommittedClaimExhaustsFiniteAttemptBudgetWithoutRunningHandler() {
        Target target = target();
        for (int expected = 1; expected <= POLICY.maxAttempts(); expected++) {
            DeliveryClaim claim = worker().claim(target.key()).orElseThrow();
            assertThat(claim.fencingToken()).isEqualTo(expected);
            assertThat(snapshot(target.key()).cycleAttempts()).isEqualTo(expected);
            expire(target.key());
        }
        assertThat(worker().claim(target.key())).isEmpty();
        assertDead(target.key(), DeliveryFailure.CRASH_EXHAUSTED, POLICY.maxAttempts());
        DeliverySnapshot exhausted = snapshot(target.key());
        assertThat(exhausted.lifetimeAttempts()).isEqualTo(POLICY.maxAttempts());
        assertThat(exhausted.fencingToken()).isEqualTo(POLICY.maxAttempts());
        assertThat(worker().claim(target.key())).isEmpty();
        assertThat(worker().runCycle()).isZero();
        assertThat(snapshot(target.key())).isEqualTo(exhausted);
        assertThat(effectCount()).isZero();
    }

    @Test
    void confirmedTransientRollbackRetriesAndCommitsEffectReceiptAndDoneTogether() {
        Target target = target();
        AtomicInteger executions = new AtomicInteger();
        EventDeliveryWorker worker = worker(handler(ROUTE, event -> {
            apply(event);
            if (executions.incrementAndGet() < 3) {
                throw new EventHandlingException(DeliveryFailure.TRANSIENT_HANDLER);
            }
        }));
        for (int attempt = 1; attempt < 3; attempt++) {
            assertThat(worker.runCycle()).isEqualTo(1);
            DeliverySnapshot pending = snapshot(target.key());
            assertThat(pending.state()).isEqualTo(PENDING);
            assertThat(pending.cycleAttempts()).isEqualTo(attempt);
            assertThat(pending.lifetimeAttempts()).isEqualTo(attempt);
            assertThat(pending.failureCode()).isEqualTo("TRANSIENT_HANDLER");
            assertThat(pending.nextAttemptAt()).isBeforeOrEqualTo(databaseNow());
            assertThat(effectCount()).isZero();
            assertThat(receiptCount()).isZero();
        }
        assertThat(worker.runCycle()).isEqualTo(1);
        DeliverySnapshot done = snapshot(target.key());
        assertThat(done.state()).isEqualTo(DONE);
        assertThat(done.cycleAttempts()).isEqualTo(3);
        assertThat(done.failureCode()).isNull();
        assertThat(done.failureDetail()).isNull();
        assertEffect(target.owner(), 1);
        assertThat(receipt(ROUTE.consumerId(), target.event().envelope().eventId()))
            .contains(target.event().envelope());
    }

    @Test
    void productionRetryScheduleUsesDatabaseTimeAndRejectsClaimsUntilEachDelayIsDue() {
        Target target = target();
        DeliveryPolicy production = new DeliveryPolicy(5, Duration.ofSeconds(30), Duration.ofSeconds(10),
            Duration.ofSeconds(5), 100, List.of(Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofMinutes(2)));
        EventDeliveryWorker worker = worker(production, handler(ROUTE, event -> {
            throw new EventHandlingException(DeliveryFailure.TRANSIENT_HANDLER);
        }));
        for (int attempt = 1; attempt <= 5; attempt++) {
            process(worker, target.key());
            DeliverySnapshot current = snapshot(target.key());
            assertThat(current.cycleAttempts()).isEqualTo(attempt);
            if (attempt < 5) {
                assertThat(current.state()).isEqualTo(PENDING);
                assertThat(jdbc.queryForObject("""
                    SELECT TIMESTAMPDIFF(MICROSECOND, updated_at, next_attempt_at)
                      FROM event_deliveries WHERE event_id = ? AND registration_id = ?
                    """, Long.class, bytes(target.key().eventId().value()), bytes(target.key().registrationId())))
                    .isEqualTo(production.retryDelay(attempt).toNanos() / 1000);
                assertThat(worker.claim(target.key())).isEmpty();
                assertThat(snapshot(target.key())).isEqualTo(current);
                jdbc.update("UPDATE event_deliveries SET next_attempt_at = UTC_TIMESTAMP(6) WHERE event_id = ?",
                    bytes(target.key().eventId().value()));
            } else {
                assertThat(current.state()).isEqualTo(DEAD);
                assertThat(worker.claim(target.key())).isEmpty();
            }
        }
    }

    @Test
    void retryExhaustionAndUnclassifiedProgrammingFailureAreDurableDeadWithoutUnboundedRetry() {
        Target transientTarget = target();
        AtomicInteger executions = new AtomicInteger();
        EventDeliveryWorker transientWorker = worker(handler(ROUTE, event -> {
            executions.incrementAndGet();
            throw new EventHandlingException(DeliveryFailure.TRANSIENT_HANDLER);
        }));
        for (int index = 0; index < POLICY.maxAttempts(); index++) {
            assertThat(transientWorker.runCycle()).isEqualTo(1);
        }
        assertDead(transientTarget.key(), DeliveryFailure.TRANSIENT_HANDLER, 3);
        assertThat(transientWorker.runCycle()).isZero();
        assertThat(executions).hasValue(3);

        StoredEvent programmingEvent = append(transientTarget.owner());
        worker().materialize();
        DeliveryKey programmingKey = key(programmingEvent, transientTarget.key().registrationId());
        process(worker(handler(ROUTE, event -> { throw new IllegalStateException("private diagnostic text"); })),
            programmingKey);
        assertDead(programmingKey, DeliveryFailure.UNCLASSIFIED_FAILURE, 1);
        assertThat(snapshot(programmingKey).failureDetail()).isEqualTo("UNCLASSIFIED_FAILURE");
        assertThat(worker().runCycle()).isZero();
    }

    @Test
    void concurrentAndSequentialProcessingOfOneClaimCommitsOnlyOneEffectReceiptAndDone() throws Exception {
        Target target = target();
        EventDeliveryWorker worker = worker(handler(ROUTE, this::apply));
        DeliveryClaim claim = worker.claim(target.key()).orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                futures.add(executor.submit(() -> { await(start); worker.process(claim); }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        }
        worker.process(claim);
        assertThat(snapshot(target.key()).state()).isEqualTo(DONE);
        assertThat(snapshot(target.key()).cycleAttempts()).isEqualTo(1);
        assertEffect(target.owner(), 1);
        assertThat(receiptCount()).isEqualTo(1);
    }

    @Test
    void syntheticConsumerReceiptChecksImmutableMeaningAndAbsorbsSequentialAndConcurrentRedelivery() throws Exception {
        Target target = target();
        applyInTransaction(target.event());
        applyInTransaction(target.event());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                futures.add(executor.submit(() -> { await(start); applyInTransaction(target.event()); }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(10, TimeUnit.SECONDS);
        }
        process(worker(handler(ROUTE, this::apply)), target.key());
        assertThat(snapshot(target.key()).state()).isEqualTo(DONE);
        assertEffect(target.owner(), 1);
        assertThat(receiptCount()).isEqualTo(1);

        jdbc.update("UPDATE event_delivery_test_receipt SET aggregate_type = 'DifferentOwner' WHERE event_id = ?",
            bytes(target.event().envelope().eventId().value()));
        assertThatThrownBy(() -> applyInTransaction(target.event()))
            .isInstanceOfSatisfying(EventHandlingException.class,
                failure -> assertThat(failure.failure()).isEqualTo(DeliveryFailure.IDENTITY_CORRUPTION));
        assertEffect(target.owner(), 1);
    }

    @Test
    void tenantMismatchCannotApplyAnotherOwnersEffectAndCrossTenantDeliveryFailsForeignKey() {
        UUID registration = registrations.activate(ROUTE);
        Owner authoritativeOwner = owner();
        TenantId forgedTenant = tenant();
        EventEnvelope forged = new EventEnvelope(EventId.newId(), forgedTenant, "SyntheticOwner",
            authoritativeOwner.id(), ROUTE.eventType(), 1, Instant.parse("2026-09-10T00:00:00Z"), "{\"signal\":true}");
        StoredEvent event = transaction.execute(status -> append.append(forged));
        assertThat(worker(handler(ROUTE, this::apply)).runCycle()).isEqualTo(1);
        DeliveryKey key = key(event, registration);
        assertDead(key, DeliveryFailure.TENANT_MISMATCH, 1);
        assertThat(effectCount()).isZero();
        assertThat(receiptCount()).isZero();

        UUID anotherRegistration = registrations.activate(new ConsumerRoute("OtherConsumer", ROUTE.eventType(), 1));
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO event_deliveries (tenant_id, event_id, registration_id, state, next_attempt_at)
            VALUES (?, ?, ?, 'PENDING', UTC_TIMESTAMP(6))
            """, bytes(authoritativeOwner.tenantId().value()), bytes(event.envelope().eventId().value()),
            bytes(anotherRegistration))).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_event_delivery_event");
        assertThat(worker().claim(new DeliveryKey(authoritativeOwner.tenantId(), key.eventId(), registration)))
            .isEmpty();
        assertThat(deliveryCount()).isEqualTo(1);
    }

    @Test
    void replayIsScopedImmediatelyDueAndPreservesImmutableEventLifetimeReceiptAndAppendOnlyAudit() {
        Target target = target();
        EventDeliveryWorker transientWorker = worker(handler(ROUTE,
            event -> { throw new EventHandlingException(DeliveryFailure.TRANSIENT_HANDLER); }));
        for (int index = 0; index < POLICY.maxAttempts(); index++) transientWorker.runCycle();
        DeliverySnapshot dead = snapshot(target.key());
        assertDead(target.key(), DeliveryFailure.TRANSIENT_HANDLER, 3);
        // Existing application evidence is deliberately retained across a new recovery cycle.
        applyInTransaction(target.event());
        Instant before = databaseNow();
        replay.replay(SystemPrincipal.INSTANCE, target.key(), "compatible handler restored");
        Instant after = databaseNow();
        DeliverySnapshot pending = snapshot(target.key());
        assertThat(pending.key()).isEqualTo(dead.key());
        assertThat(pending.state()).isEqualTo(PENDING);
        assertThat(pending.cycleAttempts()).isZero();
        assertThat(pending.lifetimeAttempts()).isEqualTo(dead.lifetimeAttempts());
        assertThat(pending.fencingToken()).isEqualTo(dead.fencingToken() + 1);
        assertThat(pending.leaseUntil()).isNull();
        assertThat(pending.nextAttemptAt()).isBetween(before, after);
        assertThat(pending.failureCode()).isNull();
        assertThat(pending.failureDetail()).isNull();
        assertThat(stored(target.key())).isEqualTo(target.event());
        assertThat(receipt(ROUTE.consumerId(), target.event().envelope().eventId()))
            .contains(target.event().envelope());
        List<Audit> firstHistory = audit(target.key());
        assertThat(firstHistory).hasSize(1);
        Audit first = firstHistory.getFirst();
        assertThat(first.key()).isEqualTo(target.key());
        assertThat(first.consumerId()).isEqualTo(ROUTE.consumerId());
        assertThat(first.reason()).isEqualTo("compatible handler restored");
        assertThat(first.origin()).isEqualTo("TRUSTED_INTERNAL");
        assertThat(first.recordedAt()).isEqualTo(pending.nextAttemptAt());
        assertThat(first.priorState()).isEqualTo("DEAD");
        assertThat(first.priorCycleAttempts()).isEqualTo(dead.cycleAttempts());
        assertThat(first.lifetimeAttempts()).isEqualTo(dead.lifetimeAttempts());
        assertThat(first.priorFencingToken()).isEqualTo(dead.fencingToken());
        assertThat(first.priorFailureCode()).isEqualTo(dead.failureCode());
        assertThat(first.priorFailureDetail()).isEqualTo(dead.failureDetail());

        process(worker(), target.key());
        assertDead(target.key(), DeliveryFailure.TARGET_HANDLER_MISSING, 1);
        replay.replay(SystemPrincipal.INSTANCE, target.key(), "exact handler installed");
        List<Audit> secondHistory = audit(target.key());
        assertThat(secondHistory).hasSize(2).contains(first);
        Audit second = secondHistory.stream().filter(row -> !row.id().equals(first.id())).findFirst().orElseThrow();
        assertThat(second.priorFailureCode()).isEqualTo("TARGET_HANDLER_MISSING");
        assertThat(second.priorCycleAttempts()).isEqualTo(1);
        assertThat(second.lifetimeAttempts()).isEqualTo(4);

        process(worker(handler(ROUTE, this::apply)), target.key());
        assertThat(snapshot(target.key()).state()).isEqualTo(DONE);
        assertThat(snapshot(target.key()).lifetimeAttempts()).isEqualTo(5);
        assertThat(stored(target.key())).isEqualTo(target.event());
        assertEffect(target.owner(), 1);
        assertThat(receiptCount()).isEqualTo(1);
    }

    @Test
    void replayRejectsWrongTenantEventTargetStateReasonAndMissingInternalAuthorityWithoutAudit() {
        Target dead = target();
        process(worker(), dead.key());
        DeliverySnapshot before = snapshot(dead.key());
        for (DeliveryKey wrong : List.of(
            new DeliveryKey(tenant(), dead.key().eventId(), dead.key().registrationId()),
            new DeliveryKey(dead.key().tenantId(), EventId.newId(), dead.key().registrationId()),
            new DeliveryKey(dead.key().tenantId(), dead.key().eventId(), UUID.randomUUID()))) {
            assertThatThrownBy(() -> replay.replay(SystemPrincipal.INSTANCE, wrong, "wrong scope"))
                .isInstanceOf(NoSuchElementException.class);
        }
        for (String invalidReason : List.of("", "  \n\t", "x".repeat(501))) {
            assertThatThrownBy(() -> replay.replay(SystemPrincipal.INSTANCE, dead.key(), invalidReason))
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> replay.replay(SystemPrincipal.INSTANCE, dead.key(), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replay.replay(null, dead.key(), "missing authority"))
            .isInstanceOf(NullPointerException.class);
        assertThat(snapshot(dead.key())).isEqualTo(before);

        StoredEvent event = append(dead.owner());
        worker().materialize();
        DeliveryKey other = key(event, dead.key().registrationId());
        assertThatThrownBy(() -> replay.replay(SystemPrincipal.INSTANCE, other, "pending"))
            .isInstanceOf(IllegalStateException.class);
        EventDeliveryWorker good = worker(handler(ROUTE, this::apply));
        DeliveryClaim claim = good.claim(other).orElseThrow();
        assertThatThrownBy(() -> replay.replay(SystemPrincipal.INSTANCE, other, "processing"))
            .isInstanceOf(IllegalStateException.class);
        good.process(claim);
        assertThat(snapshot(other).state()).isEqualTo(DONE);
        assertThatThrownBy(() -> replay.replay(SystemPrincipal.INSTANCE, other, "done"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_replay_audit", Integer.class)).isZero();
    }

    @Test
    void workerClaimsOnlyItsFreeExecutionSlotInsteadOfReservingTheWholeCandidateBatch() throws Exception {
        UUID registration = registrations.activate(ROUTE);
        Owner owner = owner();
        for (int index = 0; index < 4; index++) append(owner);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger handled = new AtomicInteger();
        EventDeliveryWorker worker = worker(handler(ROUTE, event -> {
            if (handled.getAndIncrement() == 0) {
                entered.countDown();
                await(release);
            }
            apply(event);
        }));
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> cycle = executor.submit(worker::runCycle);
            try {
                await(entered);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE state = 'PROCESSING'",
                    Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE state = 'PENDING' "
                    + "AND cycle_attempts = 0 AND lifetime_attempts = 0 AND fencing_token = 0",
                    Integer.class)).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT SUM(cycle_attempts) FROM event_deliveries", Integer.class))
                    .isEqualTo(1);
            } finally {
                release.countDown();
            }
            assertThat(cycle.get(10, TimeUnit.SECONDS)).isEqualTo(4);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE registration_id = ? AND state = 'DONE'",
            Integer.class, bytes(registration))).isEqualTo(4);
        assertEffect(owner, 4);
        assertThat(receiptCount()).isEqualTo(4);
    }

    private EventDeliveryWorker worker(EventHandler... handlers) {
        return worker(POLICY, handlers);
    }

    private EventDeliveryWorker worker(DeliveryPolicy policy, EventHandler... handlers) {
        return new EventDeliveryWorker(store, new DeliveryTransactions(transactionManager, store, policy), policy,
            new EventHandlers(List.of(handlers)), canonicalizer, entityManagerFactory);
    }

    private EventHandler handler(ConsumerRoute route, Consumer<StoredEvent> action) {
        return new EventHandler() {
            @Override public ConsumerRoute route() { return route; }
            @Override public void handle(StoredEvent event) { action.accept(event); }
        };
    }

    private Target target() {
        UUID registration = registrations.activate(ROUTE);
        Owner owner = owner();
        StoredEvent event = append(owner);
        worker().materialize();
        return new Target(owner, event, key(event, registration));
    }

    private Owner owner() {
        Owner owner = new Owner(UUID.randomUUID(), tenant(), 7);
        jdbc.update("INSERT INTO event_delivery_test_owner VALUES (?, ?, ?)",
            bytes(owner.id()), bytes(owner.tenantId().value()), owner.revision());
        return owner;
    }

    private TenantId tenant() {
        TenantId tenant = TenantId.newId();
        jdbc.update("INSERT INTO tenants (id, status) VALUES (?, 'ACTIVE')", bytes(tenant.value()));
        return tenant;
    }

    private StoredEvent append(Owner owner) {
        return append(owner, "{\"signal\":true}");
    }

    private StoredEvent append(Owner owner, String payload) {
        return transaction.execute(status -> append.append(new EventEnvelope(EventId.newId(), owner.tenantId(),
            "SyntheticOwner", owner.id(), ROUTE.eventType(), ROUTE.schemaVersion(),
            Instant.parse("2026-09-10T00:00:00.123456789Z"), payload)));
    }

    private DeliveryKey key(StoredEvent event, UUID registration) {
        return new DeliveryKey(event.envelope().tenantId(), event.envelope().eventId(), registration);
    }

    private DeliverySnapshot snapshot(DeliveryKey key) {
        return deliveryTransactions.execute(() -> store.lock(key).orElseThrow());
    }

    private StoredEvent stored(DeliveryKey key) {
        return deliveryTransactions.execute(() -> store.target(key).event());
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",
            (row, n) -> row.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    private void process(EventDeliveryWorker worker, DeliveryKey key) {
        worker.process(worker.claim(key).orElseThrow());
    }

    private void expire(DeliveryKey key) {
        assertThat(jdbc.update("""
            UPDATE event_deliveries SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
             WHERE tenant_id = ? AND event_id = ? AND registration_id = ? AND state = 'PROCESSING'
            """, bytes(key.tenantId().value()), bytes(key.eventId().value()), bytes(key.registrationId()))).isEqualTo(1);
    }

    private void assertDead(DeliveryKey key, DeliveryFailure failure, int attempts) {
        DeliverySnapshot dead = snapshot(key);
        assertThat(dead.state()).isEqualTo(DEAD);
        assertThat(dead.cycleAttempts()).isEqualTo(attempts);
        assertThat(dead.failureCode()).isEqualTo(failure.name());
        assertThat(dead.failureDetail()).isNotBlank();
        assertThat(dead.leaseUntil()).isNull();
        assertThat(dead.nextAttemptAt()).isNull();
    }

    private int deliveryCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_deliveries", Integer.class);
    }

    private int deliveriesFor(StoredEvent event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE event_id = ?", Integer.class,
            bytes(event.envelope().eventId().value()));
    }

    private int effectCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_delivery_test_effect", Integer.class);
    }

    private int receiptCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_delivery_test_receipt", Integer.class);
    }

    private void applyInTransaction(StoredEvent event) {
        transaction.executeWithoutResult(status -> apply(event));
    }

    private void apply(StoredEvent event) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        EventEnvelope envelope = event.envelope();
        Owner owner = jdbc.queryForObject("""
            SELECT tenant_id, current_revision FROM event_delivery_test_owner WHERE owner_id = ? FOR UPDATE
            """, (row, n) -> new Owner(envelope.aggregateId(), new TenantId(uuid(row.getBytes("tenant_id"))),
            row.getInt("current_revision")), bytes(envelope.aggregateId()));
        if (!owner.tenantId().equals(envelope.tenantId())) {
            throw new EventHandlingException(DeliveryFailure.TENANT_MISMATCH);
        }
        Optional<EventEnvelope> applied = receipt(ROUTE.consumerId(), envelope.eventId());
        if (applied.isPresent()) {
            if (!applied.get().equals(envelope)) {
                throw new EventHandlingException(DeliveryFailure.IDENTITY_CORRUPTION);
            }
            return;
        }
        if (!envelope.payload().equals("{\"signal\":true}")) {
            throw new EventHandlingException(DeliveryFailure.PAYLOAD_INVALID);
        }
        // Read the locked owner's current state; the event is only a reevaluation signal.
        jdbc.update("""
            INSERT INTO event_delivery_test_effect (consumer_id, owner_id, tenant_id, current_revision, applications)
            VALUES (?, ?, ?, ?, 1) AS incoming
            ON DUPLICATE KEY UPDATE current_revision = incoming.current_revision,
                applications = event_delivery_test_effect.applications + 1
            """, ROUTE.consumerId(), bytes(owner.id()), bytes(owner.tenantId().value()), owner.revision());
        jdbc.update("""
            INSERT INTO event_delivery_test_receipt (consumer_id, event_id, tenant_id, aggregate_type,
                aggregate_id, event_type, schema_version, occurred_at, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, ROUTE.consumerId(), bytes(envelope.eventId().value()), bytes(envelope.tenantId().value()),
            envelope.aggregateType(), bytes(envelope.aggregateId()), envelope.eventType(), envelope.schemaVersion(),
            LocalDateTime.ofInstant(envelope.occurredAt(), ZoneOffset.UTC), envelope.payload());
    }

    private Optional<EventEnvelope> receipt(String consumerId, EventId eventId) {
        return jdbc.query("""
            SELECT * FROM event_delivery_test_receipt WHERE consumer_id = ? AND event_id = ?
            """, (row, n) -> new EventEnvelope(new EventId(uuid(row.getBytes("event_id"))),
            new TenantId(uuid(row.getBytes("tenant_id"))), row.getString("aggregate_type"),
            uuid(row.getBytes("aggregate_id")), row.getString("event_type"), row.getInt("schema_version"),
            row.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), row.getString("payload")),
            consumerId, bytes(eventId.value())).stream().findFirst();
    }

    private void assertEffect(Owner owner, int applications) {
        assertThat(jdbc.queryForObject("""
            SELECT applications FROM event_delivery_test_effect WHERE consumer_id = ? AND owner_id = ? AND tenant_id = ?
            """, Integer.class, ROUTE.consumerId(), bytes(owner.id()), bytes(owner.tenantId().value())))
            .isEqualTo(applications);
        assertThat(jdbc.queryForObject("SELECT current_revision FROM event_delivery_test_effect WHERE owner_id = ?",
            Integer.class, bytes(owner.id()))).isEqualTo(owner.revision());
    }

    private List<Audit> audit(DeliveryKey key) {
        return jdbc.query("""
            SELECT * FROM event_replay_audit WHERE tenant_id = ? AND event_id = ? AND registration_id = ?
            ORDER BY recorded_at, replay_id
            """, (row, n) -> new Audit(uuid(row.getBytes("replay_id")),
            new DeliveryKey(new TenantId(uuid(row.getBytes("tenant_id"))), new EventId(uuid(row.getBytes("event_id"))),
                uuid(row.getBytes("registration_id"))), row.getString("consumer_id"), row.getString("reason"),
            row.getString("recovery_origin"), row.getObject("recorded_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            row.getString("prior_state"), row.getInt("prior_cycle_attempts"), row.getLong("lifetime_attempts"),
            row.getLong("prior_fencing_token"), row.getString("prior_failure_code"), row.getString("prior_failure_detail")),
            bytes(key.tenantId().value()), bytes(key.eventId().value()), bytes(key.registrationId()));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] bytes) {
        ByteBuffer value = ByteBuffer.wrap(bytes);
        return new UUID(value.getLong(), value.getLong());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic consumer gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private record Owner(UUID id, TenantId tenantId, int revision) { }
    private record Target(Owner owner, StoredEvent event, DeliveryKey key) { }
    private record Audit(UUID id, DeliveryKey key, String consumerId, String reason, String origin, Instant recordedAt,
                         String priorState, int priorCycleAttempts, long lifetimeAttempts, long priorFencingToken,
                         String priorFailureCode, String priorFailureDetail) { }
}
