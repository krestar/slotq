package com.slotq.events;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.EventAppendService;
import com.slotq.events.application.EventCanonicalizer;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.events.application.StoredEvent;
import com.slotq.events.persistence.JdbcEventRecordStore;
import com.slotq.tenancy.domain.TenantId;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class EventAppendIntegrationTests {

    @Container
    @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_event_append");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired EventAppendService append;
    @Autowired EventRegistrationService registrations;

    private TransactionTemplate transaction;

    @BeforeEach
    void prepareSyntheticBusinessOwner() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS event_append_test_owner (
                owner_id BINARY(16) PRIMARY KEY,
                connection_id BIGINT NOT NULL
            ) ENGINE=InnoDB
            """);
    }

    @Test
    void mandatoryBoundaryRejectsStandaloneAppendBeforeEvenInvalidEnvelopeValidation() {
        assertThatThrownBy(() -> append.append(event("{}")))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> append.append(null))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void appendSharesProductPhysicalTransactionAndOnlyOuterCommitMakesStateVisible() throws Exception {
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        EventEnvelope event = event("{\"valid\":true}");
        UUID ownerId = UUID.randomUUID();
        long before = boundary();
        StoredEvent stored = transaction.execute(status -> {
            long jpaConnection = ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()")
                .getSingleResult()).longValue();
            long jdbcConnection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            assertThat(jdbcConnection).isEqualTo(jpaConnection);
            insertOwner(ownerId);
            StoredEvent result = append.append(event);
            assertThat(result.boundarySequence()).isEqualTo(before + 1);
            assertThat(eventCount(event.eventId())).isEqualTo(1);
            try (var observer = java.sql.DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
            ); var query = observer.prepareStatement("SELECT COUNT(*) FROM event_records WHERE event_id = ?")) {
                query.setBytes(1, bytes(event.eventId().value()));
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
            } catch (java.sql.SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return result;
        });

        assertThat(ownerCount(ownerId)).isEqualTo(1);
        assertThat(eventCount(event.eventId())).isEqualTo(1);
        assertThat(boundary()).isEqualTo(before + 1);
        assertThat(stored.recordedAt()).isNotNull();
        assertThat(stored.recordedAt().getNano() % 1_000).isZero();
    }

    @Test
    void outerRollbackRemovesBusinessEventAndBoundaryAllocation() {
        EventEnvelope event = event("{}");
        UUID ownerId = UUID.randomUUID();
        long before = boundary();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insertOwner(ownerId);
            append.append(event);
            assertThat(boundary()).isEqualTo(before + 1);
            throw new IllegalStateException("outer rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("outer rollback");

        assertThat(ownerCount(ownerId)).isZero();
        assertThat(eventCount(event.eventId())).isZero();
        assertThat(boundary()).isEqualTo(before);
    }

    @Test
    void caughtValidationAndCanonicalizationFailuresMakeCallerRollbackOnly() {
        EventEnvelope valid = event("{}");
        assertCaughtAppendFailureRollsBack(null);
        assertCaughtAppendFailureRollsBack(copy(valid, null, valid.tenantId(), "Fixture", valid.aggregateId(),
            "FixtureChanged", 1, valid.occurredAt(), "{}"));
        assertCaughtAppendFailureRollsBack(copy(valid, new EventId(null), valid.tenantId(), "Fixture", valid.aggregateId(),
            "FixtureChanged", 1, valid.occurredAt(), "{}"));
        assertCaughtAppendFailureRollsBack(copy(valid, valid.eventId(), null, "Fixture", valid.aggregateId(),
            "FixtureChanged", 1, valid.occurredAt(), "{}"));
        assertCaughtAppendFailureRollsBack(copy(valid, valid.eventId(), valid.tenantId(), "Fixture", null,
            "FixtureChanged", 1, valid.occurredAt(), "{}"));
        assertCaughtAppendFailureRollsBack(copy(valid, valid.eventId(), valid.tenantId(), "Fixture", valid.aggregateId(),
            "FixtureChanged", 0, valid.occurredAt(), "{}"));
        assertCaughtAppendFailureRollsBack(copy(valid, valid.eventId(), valid.tenantId(), "Fixture", valid.aggregateId(),
            "FixtureChanged", 1, null, "{}"));
        for (String identifier : List.of("", " ", "Fixture Changed", "예약", "a".repeat(101), "name\n")) {
            assertCaughtAppendFailureRollsBack(copy(valid, valid.eventId(), valid.tenantId(), identifier, valid.aggregateId(),
                "FixtureChanged", 1, valid.occurredAt(), "{}"));
        }
        for (Instant time : List.of(Instant.parse("0999-12-31T23:59:59Z"), Instant.parse("+10000-01-01T00:00:00Z"))) {
            assertCaughtAppendFailureRollsBack(copy(valid, valid.eventId(), valid.tenantId(), "Fixture", valid.aggregateId(),
                "FixtureChanged", 1, time, "{}"));
        }
        for (String payload : List.of("", "{", "{} {}", "{\"a\":1,\"a\":2}",
            "{\"nested\":{\"a\":1,\"a\":1}}", "{\"value\":\"\\uD800\"}")) {
            assertCaughtAppendFailureRollsBack(withPayload(valid, payload));
        }
        assertCaughtAppendFailureRollsBack(withPayload(valid, null));
        assertThat(eventCount(valid.eventId())).isZero();
    }

    @Test
    void caughtDatabaseFailureRollsBackBusinessAndAllocatedBoundary() {
        EventEnvelope valid = event("{}");
        EventEnvelope unknownTenant = copy(valid, valid.eventId(), TenantId.newId(), valid.aggregateType(),
            valid.aggregateId(), valid.eventType(), valid.schemaVersion(), valid.occurredAt(), valid.payload());
        assertCaughtAppendFailureRollsBack(unknownTenant);
        assertThat(eventCount(valid.eventId())).isZero();
    }

    @Test
    void readOnlyCallerCannotAppendAndCaughtFailureStillRollsBack() {
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        EventEnvelope event = event("{}");
        assertThatThrownBy(() -> readOnly.executeWithoutResult(status -> {
            assertThatThrownBy(() -> append.append(event)).isInstanceOf(IllegalStateException.class);
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(eventCount(event.eventId())).isZero();
    }

    @Test
    void canonicalJsonAndMicrosecondsRemainIdempotentAfterActualPersistenceRoundTrip() {
        EventEnvelope event = event("""
            {"z":[1.00,true,null,"a"], "decimal":123456789012345678901234567890.12345678901234567890,
             "nested":{"b":2e0,"a":-0.0}}
            """);
        StoredEvent first = transaction.execute(status -> append.append(event));
        assertThat(first.envelope().occurredAt()).isEqualTo(Instant.parse("2026-09-10T00:00:00.123456Z"));
        assertThat(first.envelope().payload()).contains("123456789012345678901234567890.1234567890123456789");
        long allocated = boundary();

        EventEnvelope equivalent = withPayload(event, """
            {"nested":{"a":0,"b":2.000},"decimal":123456789012345678901234567890.123456789012345678900,
             "z":[1e0,true,null,"\u0061"]}
            """);
        StoredEvent duplicate = transaction.execute(status -> append.append(equivalent));
        assertThat(duplicate).isEqualTo(first);
        assertThat(transaction.<StoredEvent>execute(status -> append.append(first.envelope()))).isEqualTo(first);
        assertThat(eventCount(event.eventId())).isEqualTo(1);
        assertThat(boundary()).isEqualTo(allocated);

        EventAppendService restarted = new EventAppendService(
            new JdbcEventRecordStore(jdbc), new EventCanonicalizer(), transactionManager
        );
        assertThat(transaction.<StoredEvent>execute(status -> restarted.append(equivalent))).isEqualTo(first);
        assertThat(boundary()).isEqualTo(allocated);
    }

    @Test
    void everyImmutableIdentityFieldAndArrayOrderRejectCollisionWithoutChangingOriginal() {
        EventEnvelope event = event("{\"values\":[1,2],\"number\":0.1000000000000000000001}");
        StoredEvent original = transaction.execute(status -> append.append(event));
        TenantId anotherTenant = tenant();
        List<EventEnvelope> collisions = List.of(
            copy(event, event.eventId(), anotherTenant, event.aggregateType(), event.aggregateId(), event.eventType(), 1,
                event.occurredAt(), event.payload()),
            copy(event, event.eventId(), event.tenantId(), "fixture", event.aggregateId(), event.eventType(), 1,
                event.occurredAt(), event.payload()),
            copy(event, event.eventId(), event.tenantId(), event.aggregateType(), UUID.randomUUID(), event.eventType(), 1,
                event.occurredAt(), event.payload()),
            copy(event, event.eventId(), event.tenantId(), event.aggregateType(), event.aggregateId(), "fixturechanged", 1,
                event.occurredAt(), event.payload()),
            copy(event, event.eventId(), event.tenantId(), event.aggregateType(), event.aggregateId(), event.eventType(), 2,
                event.occurredAt(), event.payload()),
            copy(event, event.eventId(), event.tenantId(), event.aggregateType(), event.aggregateId(), event.eventType(), 1,
                event.occurredAt().plusNanos(1_000), event.payload()),
            withPayload(event, "{\"values\":[2,1],\"number\":0.1000000000000000000001}"),
            withPayload(event, "{\"values\":[1,2],\"number\":0.1000000000000000000002}")
        );
        long allocated = boundary();
        for (EventEnvelope collision : collisions) {
            UUID ownerId = UUID.randomUUID();
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                insertOwner(ownerId);
                assertThatThrownBy(() -> append.append(collision))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("IDENTITY_CORRUPTION");
            })).isInstanceOf(UnexpectedRollbackException.class);
            assertThat(ownerCount(ownerId)).isZero();
            assertThat(boundary()).isEqualTo(allocated);
        }
        assertThat(transaction.<StoredEvent>execute(status -> append.append(event))).isEqualTo(original);
    }

    @Test
    void boundedAsciiIdentifiersHaveTheSameExactCaseSemanticsInJavaAndMySql() {
        EventEnvelope base = event("{}");
        EventEnvelope maximum = copy(base, base.eventId(), base.tenantId(), "A".repeat(100), base.aggregateId(),
            "E".repeat(100), 1, base.occurredAt(), "{}");
        transaction.executeWithoutResult(status -> append.append(maximum));
        ConsumerRoute upper = new ConsumerRoute("C".repeat(100), "E".repeat(100), 1);
        ConsumerRoute lower = new ConsumerRoute("c".repeat(100), "e".repeat(100), 1);
        UUID upperId = registrations.activate(upper);
        UUID lowerId = registrations.activate(lower);
        assertThat(upper).isNotEqualTo(lower);
        assertThat(upperId).isNotEqualTo(lowerId);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM event_registrations WHERE consumer_id = ? AND event_type = ?
            """, Integer.class, upper.consumerId(), upper.eventType())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_records WHERE event_type = ?",
            Integer.class, "e".repeat(100))).isZero();
        assertThatThrownBy(() -> registrations.activate(new ConsumerRoute("c".repeat(101), "FixtureChanged", 1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registrations.activate(new ConsumerRoute("bad consumer", "FixtureChanged", 1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE event_records SET payload = '{' WHERE event_id = ?",
            bytes(maximum.eventId().value()))).isInstanceOf(DataAccessException.class);
        for (String invalid : List.of("", "Trailing\n", "Trailing ", "한글", "A".repeat(101))) {
            assertThatThrownBy(() -> jdbc.update("UPDATE event_records SET event_type = ? WHERE event_id = ?",
                invalid, bytes(maximum.eventId().value()))).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE event_records SET aggregate_type = ? WHERE event_id = ?",
                invalid, bytes(maximum.eventId().value()))).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE event_registrations SET consumer_id = ? WHERE registration_id = ?",
                invalid, bytes(upperId))).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void registrationHistoryExcludesHistoricalEventsAndPreservesOriginalGeneration() {
        ConsumerRoute route = route();
        EventEnvelope before = event(route.eventType(), "{}");
        transaction.executeWithoutResult(status -> append.append(before));
        UUID original = registrations.activate(route);
        long activeBoundary = boundary();
        assertThatThrownBy(() -> registrations.activate(route)).isInstanceOf(IllegalStateException.class);
        assertThat(boundary()).isEqualTo(activeBoundary);
        EventEnvelope during = event(route.eventType(), "{}");
        transaction.executeWithoutResult(status -> append.append(during));
        assertThat(registrations.deactivate(original)).isTrue();
        long inactiveBoundary = boundary();
        assertThat(registrations.deactivate(original)).isFalse();
        assertThat(boundary()).isEqualTo(inactiveBoundary);
        EventEnvelope after = event(route.eventType(), "{}");
        transaction.executeWithoutResult(status -> append.append(after));
        UUID reactivated = registrations.activate(route);
        EventEnvelope nextGeneration = event(route.eventType(), "{}");
        transaction.executeWithoutResult(status -> append.append(nextGeneration));

        assertThat(reactivated).isNotEqualTo(original);
        assertThat(target(before, original)).isFalse();
        assertThat(target(during, original)).isTrue();
        assertThat(target(after, original)).isFalse();
        assertThat(target(nextGeneration, original)).isFalse();
        assertThat(target(before, reactivated)).isFalse();
        assertThat(target(during, reactivated)).isFalse();
        assertThat(target(after, reactivated)).isFalse();
        assertThat(target(nextGeneration, reactivated)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_registrations WHERE consumer_id = ?",
            Integer.class, route.consumerId())).isEqualTo(2);
    }

    @Test
    void concurrentAppendAndActivationLinearizeOnEitherSideOfOuterCommit() throws Exception {
        ConsumerRoute afterRoute = route();
        EventEnvelope beforeActivation = event(afterRoute.eventType(), "{}");
        AtomicReference<UUID> afterRegistration = new AtomicReference<>();
        assertSecondBoundaryBlocks(
            () -> append.append(beforeActivation),
            () -> afterRegistration.set(registrations.activate(afterRoute)), false
        );
        assertThat(target(beforeActivation, afterRegistration.get())).isFalse();

        ConsumerRoute beforeRoute = route();
        AtomicReference<UUID> beforeRegistration = new AtomicReference<>();
        EventEnvelope afterActivation = event(beforeRoute.eventType(), "{}");
        assertSecondBoundaryBlocks(
            () -> beforeRegistration.set(registrations.activate(beforeRoute)),
            () -> append.append(afterActivation), false
        );
        assertThat(target(afterActivation, beforeRegistration.get())).isTrue();
    }

    @Test
    void concurrentAppendAndDeactivationLinearizeOnEitherSideOfOuterCommit() throws Exception {
        ConsumerRoute routeBefore = route();
        UUID registrationBefore = registrations.activate(routeBefore);
        EventEnvelope beforeDeactivation = event(routeBefore.eventType(), "{}");
        assertSecondBoundaryBlocks(
            () -> append.append(beforeDeactivation), () -> registrations.deactivate(registrationBefore), false
        );
        assertThat(target(beforeDeactivation, registrationBefore)).isTrue();

        ConsumerRoute routeAfter = route();
        UUID registrationAfter = registrations.activate(routeAfter);
        EventEnvelope afterDeactivation = event(routeAfter.eventType(), "{}");
        assertSecondBoundaryBlocks(
            () -> registrations.deactivate(registrationAfter), () -> append.append(afterDeactivation), false
        );
        assertThat(target(afterDeactivation, registrationAfter)).isFalse();
    }

    @Test
    void rolledBackAppendAndCutoverReleaseFenceWithoutLeavingDurableSequenceOrMembership() throws Exception {
        ConsumerRoute route = route();
        EventEnvelope rolledBack = event(route.eventType(), "{}");
        AtomicReference<UUID> registered = new AtomicReference<>();
        long beforeAppend = boundary();
        assertSecondBoundaryBlocks(
            () -> append.append(rolledBack), () -> registered.set(registrations.activate(route)), true
        );
        assertThat(eventCount(rolledBack.eventId())).isZero();
        assertThat(boundary()).isEqualTo(beforeAppend + 1);

        ConsumerRoute discardedRoute = route();
        AtomicReference<UUID> discardedRegistration = new AtomicReference<>();
        EventEnvelope survives = event(discardedRoute.eventType(), "{}");
        long beforeActivation = boundary();
        assertSecondBoundaryBlocks(
            () -> discardedRegistration.set(registrations.activate(discardedRoute)), () -> append.append(survives), true
        );
        assertThat(boundary()).isEqualTo(beforeActivation + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_registrations WHERE registration_id = ?",
            Integer.class, bytes(discardedRegistration.get()))).isZero();
        assertThat(target(survives, discardedRegistration.get())).isFalse();

        EventEnvelope remainsTarget = event(route.eventType(), "{}");
        long beforeDeactivation = boundary();
        assertSecondBoundaryBlocks(
            () -> registrations.deactivate(registered.get()), () -> append.append(remainsTarget), true
        );
        assertThat(boundary()).isEqualTo(beforeDeactivation + 1);
        assertThat(target(remainsTarget, registered.get())).isTrue();
    }

    @Test
    void concurrentDuplicateAppendsAllocateOnlyOneDurableBoundary() throws Exception {
        EventEnvelope event = event("{\"value\":1.00}");
        long before = boundary();
        assertSecondBoundaryBlocks(() -> append.append(event), () -> append.append(withPayload(event, "{\"value\":1}")), false);
        assertThat(eventCount(event.eventId())).isEqualTo(1);
        assertThat(boundary()).isEqualTo(before + 1);
    }

    private void assertCaughtAppendFailureRollsBack(EventEnvelope event) {
        UUID ownerId = UUID.randomUUID();
        long before = boundary();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insertOwner(ownerId);
            assertThatThrownBy(() -> append.append(event)).isInstanceOf(RuntimeException.class);
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(ownerCount(ownerId)).isZero();
        assertThat(boundary()).isEqualTo(before);
    }

    private void assertSecondBoundaryBlocks(Runnable firstAction, Runnable secondAction, boolean rollbackFirst)
        throws Exception {
        CountDownLatch firstAllocated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                firstAction.run();
                firstAllocated.countDown();
                await(releaseFirst);
                if (rollbackFirst) throw new IllegalStateException("injected boundary rollback");
            }));
            try {
                assertThat(firstAllocated.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> transaction.executeWithoutResult(status -> {
                    secondAttempted.countDown();
                    secondAction.run();
                }));
                assertThat(secondAttempted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseFirst.countDown();
                if (rollbackFirst) {
                    assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasRootCauseMessage("injected boundary rollback");
                } else {
                    first.get(10, TimeUnit.SECONDS);
                }
                second.get(10, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    private boolean target(EventEnvelope event, UUID registrationId) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM event_records e JOIN event_registrations r
              ON r.event_type = e.event_type AND r.schema_version = e.schema_version
            WHERE e.event_id = ? AND r.registration_id = ?
              AND e.boundary_sequence > r.activation_boundary
              AND (r.deactivation_boundary IS NULL OR e.boundary_sequence < r.deactivation_boundary)
            """, Integer.class, bytes(event.eventId().value()), bytes(registrationId)) == 1;
    }

    private EventEnvelope event(String payload) {
        return event("FixtureChanged", payload);
    }

    private EventEnvelope event(String eventType, String payload) {
        return new EventEnvelope(EventId.newId(), tenant(), "Fixture", UUID.randomUUID(), eventType, 1,
            Instant.parse("2026-09-10T00:00:00.123456789Z"), payload);
    }

    private TenantId tenant() {
        TenantId tenantId = TenantId.newId();
        jdbc.update("INSERT INTO tenants (id, status) VALUES (?, 'ACTIVE')", bytes(tenantId.value()));
        return tenantId;
    }

    private ConsumerRoute route() {
        return new ConsumerRoute("fixture-" + UUID.randomUUID(), "Fixture-" + UUID.randomUUID(), 1);
    }

    private EventEnvelope withPayload(EventEnvelope source, String payload) {
        return copy(source, source.eventId(), source.tenantId(), source.aggregateType(), source.aggregateId(),
            source.eventType(), source.schemaVersion(), source.occurredAt(), payload);
    }

    private EventEnvelope copy(EventEnvelope source, EventId eventId, TenantId tenantId, String aggregateType,
                               UUID aggregateId, String eventType, int schemaVersion, Instant occurredAt, String payload) {
        return new EventEnvelope(eventId, tenantId, aggregateType, aggregateId, eventType, schemaVersion, occurredAt, payload);
    }

    private void insertOwner(UUID ownerId) {
        jdbc.update("INSERT INTO event_append_test_owner VALUES (?, CONNECTION_ID())", bytes(ownerId));
    }

    private int ownerCount(UUID ownerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_append_test_owner WHERE owner_id = ?", Integer.class, bytes(ownerId));
    }

    private int eventCount(EventId eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_records WHERE event_id = ?", Integer.class, bytes(eventId.value()));
    }

    private long boundary() {
        return jdbc.queryForObject("SELECT sequence_value FROM event_boundary WHERE singleton_id = 1", Long.class);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("boundary gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
