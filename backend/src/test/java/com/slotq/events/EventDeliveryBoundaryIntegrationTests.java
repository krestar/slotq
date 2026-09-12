package com.slotq.events;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.slotq.booking.application.HoldIdempotencyCleanup;
import com.slotq.events.application.*;
import com.slotq.events.persistence.JdbcEventDeliveryStore;
import com.slotq.tenancy.domain.TenantId;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class EventDeliveryBoundaryIntegrationTests {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("event_delivery_boundaries");

    private static final DeliveryPolicy POLICY = new DeliveryPolicy(3, Duration.ofSeconds(6),
        Duration.ofSeconds(3), Duration.ofSeconds(1), 100, List.of(Duration.ZERO, Duration.ZERO));

    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired EventAppendService append;
    @Autowired EventRegistrationService registrations;
    @Autowired EventCanonicalizer canonicalizer;
    @Autowired ApplicationContext context;

    private JdbcEventDeliveryStore store;
    private ConsumerRoute route;
    private DeliveryKey key;

    @BeforeEach
    void prepare() {
        for (String table : List.of("event_replay_audit", "event_deliveries", "event_registrations", "event_records")) {
            db.update("DELETE FROM " + table);
        }
        db.update("UPDATE event_discovery SET boundary_sequence = 0");
        store = new JdbcEventDeliveryStore(db);
        db.execute("""
            CREATE TABLE IF NOT EXISTS boundary_test_effect (
                event_id BINARY(16) PRIMARY KEY, value INT NOT NULL
            ) ENGINE=InnoDB
            """);
        db.execute("CREATE TABLE IF NOT EXISTS boundary_test_lock (id INT PRIMARY KEY, value INT NOT NULL)");
        db.update("DELETE FROM boundary_test_effect");
        db.update("DELETE FROM boundary_test_lock");
        db.update("INSERT INTO boundary_test_lock VALUES (1, 0), (2, 0)");
        route = new ConsumerRoute("BoundaryProbe", "SyntheticChanged", 1);
        UUID registration = registrations.activate(route);
        TenantId tenant = TenantId.newId();
        db.update("INSERT INTO tenants (id, status) VALUES (?, 'ACTIVE')", bytes(tenant.value()));
        EventEnvelope event = new EventEnvelope(EventId.newId(), tenant, "SyntheticOwner", UUID.randomUUID(),
            route.eventType(), 1, Instant.parse("2026-09-10T00:00:00Z"), "{}");
        new TransactionTemplate(manager).executeWithoutResult(status -> append.append(event));
        key = new DeliveryKey(tenant, event.eventId(), registration);
        worker(ignored -> { }).materialize();
    }

    @Test
    void defaultSchedulerIsDisabledWhileManualWorkerAndExistingHoldCleanupRemainAvailable() {
        assertThat(context.getBeansOfType(EventDeliveryScheduler.class)).isEmpty();
        assertThat(context.getBean(HoldIdempotencyCleanup.class)).isNotNull();
        assertThat(worker(this::effect).runCycle()).isEqualTo(1);
        assertThat(snapshot().state()).isEqualTo(DeliverySnapshot.State.DONE);
        assertThat(effectCount()).isEqualTo(1);
    }

    @Test
    void waitingForDeliveryLockUsesFreshTimeAfterAcquisitionRatherThanStatementStart() throws Exception {
        AtomicBoolean handled = new AtomicBoolean();
        EventDeliveryWorker worker = worker(event -> handled.set(true));
        DeliveryClaim claim = worker.claim(key).orElseThrow();
        shortenLease();
        CountDownLatch locking = new CountDownLatch(1);
        JdbcEventDeliveryStore observed = org.mockito.Mockito.spy(new JdbcEventDeliveryStore(db));
        org.mockito.Mockito.doAnswer(invocation -> {
            locking.countDown();
            return invocation.callRealMethod();
        }).when(observed).lock(key);
        EventDeliveryWorker waiting = worker(observed, manager, POLICY, event -> handled.set(true));
        try (Connection blocker = connection(); var threads = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT state FROM event_deliveries WHERE event_id = ? FOR UPDATE")) {
                lock.setBytes(1, bytes(key.eventId().value()));
                lock.executeQuery().close();
            }
            var result = threads.submit(() -> waiting.process(claim));
            assertThat(locking.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            try (var delay = blocker.createStatement()) {
                delay.executeQuery("SELECT SLEEP(0.4)").close();
            }
            blocker.commit();
            result.get(5, TimeUnit.SECONDS);
        }
        assertThat(handled).isFalse();
        assertThat(snapshot().state()).isEqualTo(DeliverySnapshot.State.PROCESSING);
        assertThat(snapshot().fencingToken()).isEqualTo(claim.fencingToken());
    }

    @Test
    void finalFreshTimeCheckRollsBackEffectWhenLeaseExpiresInsideHandler() {
        EventDeliveryWorker worker = worker(event -> {
            effect(event);
            db.queryForObject("SELECT SLEEP(0.4)", Integer.class);
        });
        DeliveryClaim claim = worker.claim(key).orElseThrow();
        shortenLease();
        worker.process(claim);

        assertThat(effectCount()).isZero();
        assertThat(snapshot().state()).isEqualTo(DeliverySnapshot.State.PROCESSING);
        assertThat(snapshot().fencingToken()).isEqualTo(claim.fencingToken());
        EventDeliveryWorker recovered = worker(this::effect);
        recovered.process(recovered.claim(key).orElseThrow());
        assertThat(effectCount()).isEqualTo(1);
        assertThat(snapshot().state()).isEqualTo(DeliverySnapshot.State.DONE);
    }

    @Test
    void actualMysqlLockTimeoutRollsBackTheWholeEffectBeforeSchedulingRetry() throws Exception {
        EventDeliveryWorker worker = worker(event -> {
            effect(event);
            db.update("UPDATE boundary_test_lock SET value = value + 1 WHERE id = 1");
        });
        DeliveryClaim claim = worker.claim(key).orElseThrow();
        try (Connection blocker = connection(); var lock = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            lock.executeUpdate("UPDATE boundary_test_lock SET value = value + 1 WHERE id = 1");
            long start = System.nanoTime();
            worker.process(claim);
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsed).isBetween(800L, 5000L);
            blocker.rollback();
        }
        assertThat(effectCount()).isZero();
        assertThat(snapshot().state()).isEqualTo(DeliverySnapshot.State.PENDING);
        assertThat(snapshot().failureCode()).isEqualTo("DB_LOCK_TRANSIENT");
        assertThat(snapshot().cycleAttempts()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT value FROM boundary_test_lock WHERE id = 1", Integer.class)).isZero();
    }

    @Test
    void rawJpaLockTimeoutIsAlsoRetryableOnlyAfterTheWholeEffectRollsBack() throws Exception {
        EventDeliveryWorker worker = worker(event -> {
            effect(event);
            var entityManager = org.springframework.orm.jpa.EntityManagerFactoryUtils
                .getTransactionalEntityManager(entityManagerFactory);
            entityManager.createNativeQuery("UPDATE boundary_test_lock SET value = value + 1 WHERE id = 1")
                .executeUpdate();
        });
        DeliveryClaim claim = worker.claim(key).orElseThrow();
        try (Connection blocker = connection(); var lock = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            lock.executeUpdate("UPDATE boundary_test_lock SET value = value + 1 WHERE id = 1");
            worker.process(claim);
            blocker.rollback();
        }
        assertThat(effectCount()).isZero();
        assertThat(snapshot().state()).isEqualTo(DeliverySnapshot.State.PENDING);
        assertThat(snapshot().failureCode()).isEqualTo("DB_LOCK_TRANSIENT");
    }

    @Test
    void actualMysqlDeadlockVictimRollsBackEffectAndSchedulesBoundedRetry() throws Exception {
        // InnoDB chooses the smaller transaction as victim; give the competing transaction more undo work.
        for (int id = 3; id <= 30; id++) db.update("INSERT INTO boundary_test_lock VALUES (?, 0)", id);
        CountDownLatch firstLocked = new CountDownLatch(1);
        EventDeliveryWorker worker = worker(event -> {
            effect(event);
            db.update("UPDATE boundary_test_lock SET value = value + 1 WHERE id = 1");
            firstLocked.countDown();
            db.update("UPDATE boundary_test_lock SET value = value + 1 WHERE id = 2");
        });
        DeliveryClaim claim = worker.claim(key).orElseThrow();
        try (Connection blocker = connection(); var statement = blocker.createStatement();
             var threads = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            statement.executeUpdate("UPDATE boundary_test_lock SET value = value + 1 WHERE id >= 2");
            var result = threads.submit(() -> worker.process(claim));
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
            statement.executeUpdate("UPDATE boundary_test_lock SET value = value + 1 WHERE id = 1");
            blocker.commit();
            result.get(5, TimeUnit.SECONDS);
        }
        assertThat(effectCount()).isZero();
        assertThat(snapshot().state()).isEqualTo(DeliverySnapshot.State.PENDING);
        assertThat(snapshot().failureCode()).isEqualTo("DB_LOCK_TRANSIENT");
    }

    @Test
    void statementAndNetworkTimeoutBoundRealBlockingSqlAndDoNotCommitPartialEffect() {
        DeliveryPolicy shortPolicy = new DeliveryPolicy(3, Duration.ofSeconds(4), Duration.ofSeconds(2),
            Duration.ofSeconds(1), 100, List.of(Duration.ZERO, Duration.ZERO));
        EventDeliveryWorker worker = worker(store, manager, shortPolicy, event -> {
            effect(event);
            db.queryForObject("SELECT SLEEP(10)", Integer.class);
        });
        DeliveryClaim claim = worker.claim(key).orElseThrow();
        long start = System.nanoTime();
        worker.process(claim);
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsed).isLessThan(6000L);
        assertThat(effectCount()).isZero();
        assertThat(snapshot().state()).isIn(DeliverySnapshot.State.PENDING, DeliverySnapshot.State.PROCESSING);
        assertThat(snapshot().cycleAttempts()).isEqualTo(1);
    }

    @Test
    void sessionTimeoutsAreRestoredBeforeConnectionReturnsToPool() {
        AtomicBoolean checked = new AtomicBoolean();
        worker(event -> {
            assertThat(db.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class)).isEqualTo(1);
            checked.set(true);
        }).runCycle();
        assertThat(checked).isTrue();
        // Hold all pool connections concurrently so the previously used connection is included.
        var connections = new java.util.ArrayList<Connection>();
        try {
            for (int index = 0; index < 10; index++) {
                Connection connection = db.getDataSource().getConnection();
                connections.add(connection);
                try (var query = connection.createStatement();
                     var rows = query.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
                    rows.next();
                    assertThat(rows.getInt(1)).isEqualTo(50);
                }
                assertThat(connection.getNetworkTimeout()).isZero();
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        } finally {
            connections.forEach(connection -> { try { connection.close(); } catch (java.sql.SQLException ignored) { } });
        }
    }

    @Test
    void deeperRequiresNewCannotSuspendAndIndependentlyCommitAnEffect() {
        TransactionTemplate independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        EventDeliveryWorker worker = worker(event -> independent.executeWithoutResult(status -> effect(event)));
        worker.runCycle();

        assertThat(effectCount()).isZero();
        assertThat(snapshot().state()).isEqualTo(DeliverySnapshot.State.DEAD);
        assertThat(snapshot().failureCode()).isEqualTo("UNCLASSIFIED_FAILURE");
    }

    @Test
    void commitResponseLostAfterActualCommitPreservesDoneAndNeverRepeatsEffect() {
        CommitFaultManager faulty = new CommitFaultManager(true);
        EventDeliveryWorker worker = worker(store, faulty, POLICY, this::effect);
        DeliveryClaim claim = worker.claim(key).orElseThrow();
        faulty.armed.set(true);
        worker.process(claim);

        assertThat(effectCount()).isEqualTo(1);
        assertThat(snapshot().state()).isEqualTo(DeliverySnapshot.State.DONE);
        assertThat(snapshot().failureCode()).isNull();
        assertThat(worker.claim(key)).isEmpty();
        worker.process(claim);
        assertThat(effectCount()).isEqualTo(1);
        assertThat(snapshot().lifetimeAttempts()).isEqualTo(1);
    }

    @Test
    void unknownCommitBeforeCommitPreservesClaimUntilLeaseRecovery() {
        CommitFaultManager faulty = new CommitFaultManager(false);
        EventDeliveryWorker worker = worker(store, faulty, POLICY, this::effect);
        DeliveryClaim claim = worker.claim(key).orElseThrow();
        faulty.armed.set(true);
        worker.process(claim);

        assertThat(effectCount()).isZero();
        assertThat(snapshot().state()).isEqualTo(DeliverySnapshot.State.PROCESSING);
        assertThat(snapshot().failureCode()).isNull();
        assertThat(snapshot().cycleAttempts()).isEqualTo(1);
        expireLease();
        DeliveryClaim reclaimed = worker.claim(key).orElseThrow();
        worker.process(reclaimed);
        assertThat(effectCount()).isEqualTo(1);
        assertThat(snapshot().state()).isEqualTo(DeliverySnapshot.State.DONE);
        assertThat(snapshot().lifetimeAttempts()).isEqualTo(2);
    }

    private EventDeliveryWorker worker(Consumer<StoredEvent> effect) {
        return worker(store, manager, POLICY, effect);
    }

    private EventDeliveryWorker worker(EventDeliveryStore persistence, PlatformTransactionManager transactions,
                                       DeliveryPolicy policy, Consumer<StoredEvent> effect) {
        EventHandler handler = new EventHandler() {
            @Override public ConsumerRoute route() { return route; }
            @Override public void handle(StoredEvent event) { effect.accept(event); }
        };
        return new EventDeliveryWorker(persistence, new DeliveryTransactions(transactions, persistence, policy),
            policy, new EventHandlers(List.of(handler)), canonicalizer, entityManagerFactory);
    }

    private void effect(StoredEvent event) {
        db.update("INSERT INTO boundary_test_effect VALUES (?, 1)", bytes(event.envelope().eventId().value()));
    }

    private int effectCount() {
        return db.queryForObject("SELECT COUNT(*) FROM boundary_test_effect", Integer.class);
    }

    private DeliverySnapshot snapshot() {
        return new DeliveryTransactions(manager, store, POLICY).execute(() -> store.lock(key).orElseThrow());
    }

    private void shortenLease() {
        db.update("UPDATE event_deliveries SET lease_until = TIMESTAMPADD(MICROSECOND, 250000, UTC_TIMESTAMP(6)) WHERE event_id = ?",
            bytes(key.eventId().value()));
    }

    private void expireLease() {
        db.update("UPDATE event_deliveries SET lease_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE event_id = ?",
            bytes(key.eventId().value()));
    }

    private Connection connection() throws java.sql.SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private final class CommitFaultManager extends JpaTransactionManager {
        final AtomicBoolean armed = new AtomicBoolean();
        private final boolean commitFirst;

        CommitFaultManager(boolean commitFirst) {
            super(entityManagerFactory);
            setDataSource(db.getDataSource());
            afterPropertiesSet();
            this.commitFirst = commitFirst;
        }

        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (armed.compareAndSet(true, false)) {
                if (commitFirst) super.doCommit(status);
                else super.doRollback(status); // Server rolled back; the caller receives no confirmed outcome.
                throw new TransactionSystemException("Synthetic commit outcome unknown");
            }
            super.doCommit(status);
        }
    }
}
