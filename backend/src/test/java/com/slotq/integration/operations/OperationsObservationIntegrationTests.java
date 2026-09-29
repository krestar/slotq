package com.slotq.integration.operations;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.application.AccessDeniedException;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.events.application.*;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(properties = {"slotq.events.delivery.scheduler-enabled=false",
    "slotq.operations.observation.enabled=false"})
class OperationsObservationIntegrationTests {
    private static final Instant OCCURRED = Instant.parse("2026-09-29T01:00:00Z");
    private static final DeliveryExecutionScope SCOPE = new DeliveryExecutionScope(
        "operations.event-observation", "DB_DIRECT", 1);
    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_operations_observation");

    @Autowired EventRegistrationService registrations;
    @Autowired EventAppendService append;
    @Autowired EventDeliveryStore deliveries;
    @Autowired DeliveryTransactions transactions;
    @Autowired DeliveryPolicy policy;
    @Autowired EventHandlers handlers;
    @Autowired EventCanonicalizer canonicalizer;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired AccessControlProvisioning access;
    @Autowired OperationsObservationReadService read;
    @Autowired OperationsObservationBootstrap bootstrap;
    @Autowired PromotionRequestedObservedHandler requestHandler;
    private UUID releaseRegistration;
    private UUID requestRegistration;

    @BeforeEach void register() {
        releaseRegistration = registrations.activate(BookingCapacityObservedHandler.ROUTE);
        requestRegistration = registrations.activate(PromotionRequestedObservedHandler.ROUTE);
    }
    @AfterEach void deactivate() {
        registrations.deactivate(releaseRegistration);
        registrations.deactivate(requestRegistration);
    }

    @Test void bothRoutesProjectOnceAndReadIsTenantVenueTimeScoped() {
        Fixture fixture = fixture();
        UUID slot = UUID.randomUUID();
        StoredEvent release = append(fixture.tenant(), BookingCapacityObservedHandler.ROUTE.eventType(),
            "Reservation", UUID.randomUUID(), fixture.venue().id().value(), slot,
            "\"fromState\":\"CONFIRMED\",\"toState\":\"CANCELLED\"");
        StoredEvent request = append(fixture.tenant(), PromotionRequestedObservedHandler.ROUTE.eventType(),
            "SlotInventory", slot, fixture.venue().id().value(), slot, "");
        worker().runCycle();
        worker().runCycle();
        assertThat(state(release, releaseRegistration)).isEqualTo(DeliverySnapshot.State.DONE);
        assertThat(state(request, requestRegistration)).isEqualTo(DeliverySnapshot.State.DONE);
        assertThat(count(release) + count(request)).isEqualTo(2);
        Instant from = Instant.now().minus(Duration.ofMinutes(5));
        Instant to = Instant.now().plus(Duration.ofMinutes(5));
        var page = read.list(fixture.venue().id(), from, to, 10, fixture.owner());
        assertThat(page.items()).hasSize(2).allSatisfy(item -> {
            assertThat(item.durableIntakeObservedAt()).isNull();
            assertThat(item.projectedAt()).isNotNull();
            assertThat(item.waitlistDeliveryState()).isNull();
        });
        assertThat(page.items()).filteredOn(item -> item.eventId().equals(release.envelope().eventId().value()))
            .singleElement().satisfies(item -> {
                assertThat(item.fromState()).isEqualTo("CONFIRMED");
                assertThat(item.toState()).isEqualTo("CANCELLED");
            });
        assertThat(read.list(fixture.venue().id(), from, to, 1, fixture.owner()).truncated()).isTrue();
        assertThatThrownBy(() -> read.list(fixture.venue().id(), from.minus(Duration.ofDays(32)), to,
            10, fixture.owner())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> read.list(fixture.venue().id(), from, to, 101, fixture.owner()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> read.list(fixture.venue().id(), from, to, 10, fixture.staff()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test void rollbackDoesNotLeaveProjectionAndTheClaimCompletesOnlyOnce() {
        Fixture fixture = fixture();
        UUID slot = UUID.randomUUID();
        StoredEvent request = append(fixture.tenant(), PromotionRequestedObservedHandler.ROUTE.eventType(),
            "SlotInventory", slot, fixture.venue().id().value(), slot, "");
        DeliveryKey key = new DeliveryKey(request.envelope().tenantId(), request.envelope().eventId(), requestRegistration);
        EventDeliveryWorker worker = worker();
        worker.materialize();
        DeliveryClaim claim = worker.claim(key).orElseThrow();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            requestHandler.handle(request);
            status.setRollbackOnly();
        });
        assertThat(count(request)).isZero();
        assertThat(state(request, requestRegistration)).isEqualTo(DeliverySnapshot.State.PROCESSING);
        worker.process(claim);
        assertThat(state(request, requestRegistration)).isEqualTo(DeliverySnapshot.State.DONE);
        assertThat(count(request)).isEqualTo(1);
        worker.runCycle();
        assertThat(count(request)).isEqualTo(1);
    }

    @Test void kafkaObserverReadinessRequiresBothPreexistingRoutes() {
        assertThat(bootstrap.isReady()).isFalse();
        var kafka = new OperationsObservationBootstrap(registrations, handlers,
            new DeliveryExecutionScope("operations.event-observation", "KAFKA", 1),
            "consumer", true, true, "KAFKA");
        kafka.activate();
        assertThat(kafka.isReady()).isTrue();
        registrations.deactivate(requestRegistration);
        assertThatThrownBy(kafka::activate).isInstanceOf(IllegalStateException.class);
        assertThat(kafka.isReady()).isFalse();
    }

    private EventDeliveryWorker worker() {
        return new EventDeliveryWorker(deliveries, transactions, policy, handlers,
            canonicalizer, entityManagerFactory, SCOPE);
    }
    private DeliverySnapshot.State state(StoredEvent event, UUID registration) {
        DeliveryKey key = new DeliveryKey(event.envelope().tenantId(), event.envelope().eventId(), registration);
        return transactions.execute(() -> deliveries.lock(SCOPE, key).orElseThrow().state());
    }
    private int count(StoredEvent event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_observation_projections WHERE event_id = ?",
            Integer.class, OperationsEventObservationAdapter.bytes(event.envelope().eventId().value()));
    }
    private StoredEvent append(Tenant tenant, String type, String aggregateType, UUID aggregateId,
                               UUID venue, UUID slot, String transitionFields) {
        String payload = "{\"venueId\":\"" + venue + "\",\"resourceId\":\"" + UUID.randomUUID()
            + "\",\"slotInventoryId\":\"" + slot + "\""
            + (transitionFields.isEmpty() ? "" : "," + transitionFields) + "}";
        EventEnvelope event = new EventEnvelope(EventId.newId(), tenant.id(), aggregateType, aggregateId,
            type, 1, OCCURRED, payload);
        return new TransactionTemplate(manager).execute(status -> append.append(event));
    }
    private Fixture fixture() {
        Tenant tenant = tenants.createTenant();
        Venue venue = venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(), "Observed", "UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.TUESDAY,
                new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(22, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10)));
        var owner = new AuthenticatedPrincipal(PrincipalId.newId());
        var staff = new AuthenticatedPrincipal(PrincipalId.newId());
        access.registerPrincipal(owner.principalId());
        access.registerPrincipal(staff.principalId());
        access.assignMembership(owner.principalId(), tenant.id(), TenantRole.OWNER);
        access.assignMembership(staff.principalId(), tenant.id(), TenantRole.STAFF);
        access.grantVenue(staff.principalId(), tenant.id(), TenantRole.STAFF, venue.id());
        return new Fixture(tenant, venue, owner, staff);
    }
    private record Fixture(Tenant tenant, Venue venue, AuthenticatedPrincipal owner,
                           AuthenticatedPrincipal staff) { }
}
