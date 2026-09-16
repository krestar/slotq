package com.slotq;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import com.jayway.jsonpath.JsonPath;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.auth.domain.TenantRole;
import com.slotq.booking.application.CapacityReleaseReadiness;
import com.slotq.booking.application.ReservationCommand;
import com.slotq.booking.application.ReservationExpiryUseCase;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.WeeklyOperatingHours;
import com.slotq.waitlist.application.WaitlistOfferUseCase;
import com.slotq.waitlist.application.WaitlistRegistrationKey;
import com.slotq.waitlist.application.WaitlistUseCase;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistOfferId;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
    "slotq.auth.dev-bootstrap-enabled=true", "slotq.waitlist.promotion.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(BookingCapacityReleaseIntegrationTests.FixtureConfiguration.class)
class BookingCapacityReleaseIntegrationTests {
    private static final Instant NOW = Instant.parse("2026-08-30T09:00:00.123456789Z");
    private static final Instant START = Instant.parse("2026-08-30T11:00:00Z");
    private static final AuthenticatedPrincipal CUSTOMER = new AuthenticatedPrincipal(
        new PrincipalId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
    );
    private static final AuthenticatedPrincipal OWNER = new AuthenticatedPrincipal(PrincipalId.newId());
    private static final ConsumerRoute ROUTE = new ConsumerRoute("waitlist.promotion", "booking.capacity-released", 1);

    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4")
            .withDatabaseName("slotq_capacity_release")
            .withCommand("--log-bin-trust-function-creators=1", "--innodb-lock-wait-timeout=10");

    @Autowired ReservationUseCase booking;
    @Autowired ReservationExpiryUseCase expiry;
    @Autowired WaitlistUseCase waitlist;
    @Autowired WaitlistOfferUseCase offers;
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired AccessControlProvisioning access;
    @Autowired EventRegistrationService registrations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired MutableClock clock;
    @Autowired TestReadiness readiness;
    @Autowired MockMvc http;
    private UUID registration;
    private String token;

    @BeforeEach
    void prepare() throws Exception {
        clock.set(NOW);
        readiness.ready.set(true);
        access.registerPrincipal(OWNER.principalId());
        String response = http.perform(post("/__dev/auth/session")
            .contentType(MediaType.APPLICATION_JSON).content("{\"fixtureKey\":\"customer-a\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        token = JsonPath.read(response, "$.accessToken");
        registration = registrations.activate(ROUTE);
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_capacity_release");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_capacity_event");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_offer_after_event");
        registrations.deactivate(registration);
    }

    @ParameterizedTest
    @CsvSource({"HELD,CANCEL,CANCELLED", "CONFIRMED,CANCEL,CANCELLED",
        "CONFIRMED,NO_SHOW,NO_SHOW", "CHECKED_IN,COMPLETE,COMPLETED"})
    void ordinaryReleaseTableAndNaturalRepeat(String from, ReservationCommand command, String to) {
        Fixture fixture = fixture();
        ReservationId id = hold(fixture);
        if (!from.equals("HELD")) transition(fixture, id, ReservationCommand.CONFIRM);
        if (from.equals("CHECKED_IN")) {
            clock.set(START);
            transition(fixture, id, ReservationCommand.CHECK_IN);
        }
        if (command == ReservationCommand.NO_SHOW) clock.set(START.plusSeconds(600));
        assertThat(eventCount(id)).isZero();
        transition(fixture, id, command);
        assertEvent(fixture, id, from, to);
        transition(fixture, id, command);
        assertThat(eventCount(id)).isEqualTo(1);
        assertBooking(id, to, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"confirm", "cancel", "system"})
    void dueHeldPersistsOneExpiryEvenWhenPublicCommandReturns409(String path) throws Exception {
        Fixture fixture = fixture();
        ReservationId id = hold(fixture);
        clock.set(storedExpiry(id));
        // Effective expiry and GET alone never release persisted Allocation or publish.
        booking.getReservation(fixture.venue.id(), id, CUSTOMER);
        assertBooking(id, "HELD", true);
        assertThat(eventCount(id)).isZero();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (path.equals("system")) expiry.expire(fixture.venue.id(), id, SystemPrincipal.INSTANCE);
            else publicCommand(fixture, id, path).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        }
        assertEvent(fixture, id, "HELD", "EXPIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"reject", "entry-cancel", "accept-due", "reject-due", "reconcile", "public-cancel", "system"})
    void promotionalReleaseTableSharesRecorderWithoutDuplicateAppend(String path) throws Exception {
        Fixture fixture = fixture();
        var offer = offer(fixture);
        ReservationId id = offer.reservation().id();
        assertThat(eventCount(id)).isZero();
        boolean due = !path.equals("reject") && !path.equals("entry-cancel") && !path.equals("public-cancel");
        if (due) clock.set(storedExpiry(id));
        if (path.equals("public-cancel")) publicCommand(fixture, id, "cancel").andExpect(status().isOk());
        else if (path.equals("entry-cancel")) waitlist.cancel(fixture.venue.id(), new WaitlistEntryId(offer.entryId()), CUSTOMER);
        else if (path.equals("system")) expiry.expire(fixture.venue.id(), id, SystemPrincipal.INSTANCE);
        else if (path.equals("reconcile")) offers.reconcileTarget(SystemPrincipal.INSTANCE,
            fixture.venue.id(), new WaitlistOfferId(offer.id()));
        else http.perform(post("/api/v1/venues/{venue}/waitlist-offers/{offer}/{action}",
                fixture.venue.id().value(), offer.id(), path.startsWith("accept") ? "accept" : "reject")
            .header("Authorization", "Bearer " + token))
            .andExpect(due ? status().isConflict() : status().isOk());
        // Reconciliation of an already released backing Reservation must not republish.
        offers.reconcileTarget(SystemPrincipal.INSTANCE, fixture.venue.id(), new WaitlistOfferId(offer.id()));
        offers.reconcileTarget(SystemPrincipal.INSTANCE, fixture.venue.id(), new WaitlistOfferId(offer.id()));
        assertEvent(fixture, id, "HELD", due ? "EXPIRED" : "CANCELLED");
        assertBooking(id, due ? "EXPIRED" : "CANCELLED", false);
    }

    @Test
    void acceptedOfferKeepsItsEvidenceWhenOrdinaryBookingReleasesCapacity() {
        Fixture fixture = fixture();
        var offer = offer(fixture);
        var offerId = new WaitlistOfferId(offer.id());
        offers.accept(fixture.venue.id(), offerId, CUSTOMER);
        transition(fixture, offer.reservation().id(), ReservationCommand.CANCEL);
        assertThat(offers.accept(fixture.venue.id(), offerId, CUSTOMER).outcome())
            .isEqualTo(WaitlistOfferUseCase.CommandOutcome.SUCCESS);
        assertEvent(fixture, offer.reservation().id(), "CONFIRMED", "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT promotional_confirmed FROM reservations WHERE id = ?",
            Boolean.class, bytes(offer.reservation().id().value()))).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?",
            String.class, bytes(offer.id()))).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_entries WHERE id = ?",
            String.class, bytes(offer.entryId()))).isEqualTo("FULFILLED");
    }

    @Test
    void noReleaseTableCoversCreationConfirmCheckInNotDueReadsAndFailedCommands() {
        Fixture fixture = fixture();
        ReservationId id = hold(fixture);
        assertThatThrownBy(() -> transition(fixture, id, ReservationCommand.COMPLETE)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> expiry.expire(fixture.venue.id(), id, SystemPrincipal.INSTANCE))
            .isInstanceOf(RuntimeException.class);
        transition(fixture, id, ReservationCommand.CONFIRM);
        transition(fixture, id, ReservationCommand.CONFIRM);
        clock.set(START);
        transition(fixture, id, ReservationCommand.CHECK_IN);
        booking.getReservation(fixture.venue.id(), id, CUSTOMER);
        assertThat(eventCount(id)).isZero();
        assertBooking(id, "CHECKED_IN", true);

        clock.set(NOW);
        Fixture promotion = fixture();
        var offer = offer(promotion);
        offers.getOffer(promotion.venue.id(), new WaitlistOfferId(offer.id()), CUSTOMER);
        offers.reconcileTarget(SystemPrincipal.INSTANCE, promotion.venue.id(), new WaitlistOfferId(offer.id()));
        offers.accept(promotion.venue.id(), new WaitlistOfferId(offer.id()), CUSTOMER);
        offers.accept(promotion.venue.id(), new WaitlistOfferId(offer.id()), CUSTOMER);
        offers.reject(promotion.venue.id(), new WaitlistOfferId(offer.id()), CUSTOMER);
        assertThat(eventCount(offer.reservation().id())).isZero();
        assertBooking(offer.reservation().id(), "CONFIRMED", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"readiness", "inactive-route", "flush", "append"})
    void failuresReturn500AndRollBackActualJpaReleaseAndBoundary(String failure) throws Exception {
        Fixture fixture = fixture();
        ReservationId id = hold(fixture);
        inject(failure);
        long before = boundary();
        publicCommand(fixture, id, "cancel").andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertBooking(id, "HELD", true);
        assertThat(eventCount(id)).isZero();
        assertThat(boundary()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"readiness", "inactive-route", "flush", "append"})
    void caughtFailureStillMarksJoinedCallerRollbackOnly(String failure) {
        Fixture fixture = fixture();
        ReservationId id = hold(fixture);
        inject(failure);
        long before = boundary();
        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
            assertThatThrownBy(() -> transition(fixture, id, ReservationCommand.CANCEL))
                .isInstanceOf(RuntimeException.class).hasStackTraceContaining(switch (failure) {
                    case "readiness" -> "waitlist promotion producer is not ready";
                    case "inactive-route" -> "required consumer route is not active";
                    case "flush" -> "injected allocation flush failure";
                    case "append" -> "injected append failure";
                    default -> throw new IllegalArgumentException(failure);
                });
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertBooking(id, "HELD", true);
        assertThat(eventCount(id)).isZero();
        assertThat(boundary()).isEqualTo(before);
    }

    @Test
    void actualJpaAndJdbcSharePhysicalConnectionAndOuterRollbackRemovesBoth() {
        Fixture fixture = fixture();
        ReservationId id = hold(fixture);
        long before = boundary();
        assertThat(transactionManager).isInstanceOf(JpaTransactionManager.class);
        transaction().executeWithoutResult(status -> {
            long jpa = ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()")
                .getSingleResult()).longValue();
            assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(jpa);
            transition(fixture, id, ReservationCommand.CANCEL);
            assertBooking(id, "CANCELLED", false);
            assertThat(eventCount(id)).isEqualTo(1);
            try (Connection observer = connection()) {
                assertThat(scalar(observer, "SELECT COUNT(*) FROM event_records WHERE aggregate_id = ?", bytes(id.value())))
                    .isZero();
                assertThat(scalar(observer, "SELECT active FROM capacity_allocations WHERE reservation_id = ?", bytes(id.value())))
                    .isEqualTo(1);
            } catch (Exception failure) { throw new IllegalStateException(failure); }
            status.setRollbackOnly();
        });
        assertBooking(id, "HELD", true);
        assertThat(eventCount(id)).isZero();
        assertThat(boundary()).isEqualTo(before);
    }

    @Test
    void staleRepeatableReadRegistrationSnapshotCannotAuthorizeRelease() throws Exception {
        Fixture fixture = fixture();
        ReservationId id = hold(fixture);
        try (var executor = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                assertThat(activeRouteSnapshot()).isEqualTo(1);
                try { executor.submit(() -> registrations.deactivate(registration)).get(5, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
                assertThat(activeRouteSnapshot()).as("RR snapshot remains stale").isEqualTo(1);
                assertThatThrownBy(() -> transition(fixture, id, ReservationCommand.CANCEL))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not active");
            })).isInstanceOf(UnexpectedRollbackException.class);
        }
        assertBooking(id, "HELD", true);
        assertThat(eventCount(id)).isZero();
    }

    @Test
    void laterOfferFailureRollsBackAlreadyAppendedEventAndAllBookingAndWaitlistState() {
        Fixture fixture = fixture();
        var offer = offer(fixture);
        long before = boundary();
        jdbc.execute("""
            CREATE TRIGGER fail_offer_after_event BEFORE UPDATE ON waitlist_offers FOR EACH ROW
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected failure after Booking event'
            """);
        assertThatThrownBy(() -> offers.reject(fixture.venue.id(), new WaitlistOfferId(offer.id()), CUSTOMER))
            .isInstanceOf(RuntimeException.class);
        assertBooking(offer.reservation().id(), "HELD", true);
        assertThat(eventCount(offer.reservation().id())).isZero();
        assertThat(boundary()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?", String.class, bytes(offer.id())))
            .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_entries WHERE id = ?", String.class, bytes(offer.entryId())))
            .isEqualTo("OFFERED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "expiry", "reject", "reconcile"})
    void releaseIncludingJpaFlushAndForeignKeysNeverWaitsForSlotLock(String path) throws Exception {
        Fixture fixture = fixture();
        var offer = path.equals("reject") || path.equals("reconcile") ? offer(fixture) : null;
        ReservationId id = offer == null ? hold(fixture) : offer.reservation().id();
        if (path.equals("expiry") || path.equals("reconcile")) clock.set(storedExpiry(id));
        try (var executor = Executors.newSingleThreadExecutor(); Connection slotOwner = connection()) {
            slotOwner.setAutoCommit(false);
            lock(slotOwner, "SELECT id FROM slot_inventories WHERE id = ? FOR UPDATE", bytes(fixture.slot.id().value()));
            try {
                executor.submit(() -> {
                    switch (path) {
                        case "cancel" -> transition(fixture, id, ReservationCommand.CANCEL);
                        case "expiry" -> expiry.expire(fixture.venue.id(), id, SystemPrincipal.INSTANCE);
                        case "reject" -> offers.reject(fixture.venue.id(), new WaitlistOfferId(offer.id()), CUSTOMER);
                        case "reconcile" -> offers.reconcileTarget(SystemPrincipal.INSTANCE, fixture.venue.id(), new WaitlistOfferId(offer.id()));
                        default -> throw new IllegalStateException();
                    }
                }).get(5, TimeUnit.SECONDS);
                assertThat(eventCount(id)).isEqualTo(1);
            } finally { slotOwner.rollback(); }
        }
    }

    @Test
    void releaseWaitsAtEventBoundaryAfterBookingLocksAndDeactivationCannotSlipPastAppend() throws Exception {
        Fixture fixture = fixture();
        ReservationId id = hold(fixture);
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicReference<Long> releaseConnection = new AtomicReference<>();
        try (var executor = Executors.newFixedThreadPool(2); Connection fenceOwner = connection()) {
            fenceOwner.setAutoCommit(false);
            lock(fenceOwner, "SELECT singleton_id FROM event_boundary WHERE singleton_id = 1 FOR UPDATE", null);
            var release = executor.submit(() -> transaction().executeWithoutResult(status -> {
                releaseConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                transition(fixture, id, ReservationCommand.CANCEL);
                appended.countDown();
                await(commit);
            }));
            try {
                awaitWaitOn("event_boundary");
                assertRecordLocks(releaseConnection.get(), "reservations", "capacity_allocations");
                fenceOwner.commit();
                await(appended);
                var cutover = executor.submit(() -> registrations.deactivate(registration));
                awaitWaitOn("event_boundary");
                assertThat(cutover.isDone()).isFalse();
                commit.countDown();
                release.get(5, TimeUnit.SECONDS);
                assertThat(cutover.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(eventCount(id)).isEqualTo(1);
                assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM event_records e JOIN event_registrations r
                      ON e.event_type = r.event_type AND e.schema_version = r.schema_version
                     WHERE e.aggregate_id = ? AND r.registration_id = ?
                       AND r.activation_boundary < e.boundary_sequence
                       AND e.boundary_sequence < r.deactivation_boundary
                    """, Integer.class, bytes(id.value()), bytes(registration))).isEqualTo(1);
            } finally { fenceOwner.rollback(); commit.countDown(); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void slotFirstConfirmAndAcceptWaitForReleaseWithoutCreatingABoundaryCycle(boolean promotional) throws Exception {
        Fixture fixture = fixture();
        var offer = promotional ? offer(fixture) : null;
        ReservationId id = promotional ? offer.reservation().id() : hold(fixture);
        if (!promotional) clock.set(storedExpiry(id));
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var release = executor.submit(() -> transaction().executeWithoutResult(status -> {
                if (promotional) offers.reject(fixture.venue.id(), new WaitlistOfferId(offer.id()), CUSTOMER);
                else expiry.expire(fixture.venue.id(), id, SystemPrincipal.INSTANCE);
                appended.countDown();
                await(commit);
            }));
            try {
                await(appended);
                // Use the real public boundary: authorization/projection reads precede the executor's
                // transaction. Do not wrap the entire HTTP-style command in an artificial RR snapshot.
                var confirm = executor.submit(() -> {
                    if (promotional) assertThat(offers.accept(fixture.venue.id(), new WaitlistOfferId(offer.id()), CUSTOMER).outcome())
                        .isEqualTo(WaitlistOfferUseCase.CommandOutcome.TRANSITION_NOT_ALLOWED);
                    else assertThatThrownBy(() -> transition(fixture, id, ReservationCommand.CONFIRM))
                        .isInstanceOf(com.slotq.booking.application.HoldExpiredException.class);
                });
                long confirmConnection = awaitWaitOn(promotional ? "waitlist_entries" : "reservations");
                assertRecordLocksIncluding(confirmConnection, "slot_inventories");
                commit.countDown();
                release.get(5, TimeUnit.SECONDS);
                confirm.get(5, TimeUnit.SECONDS);
                assertEvent(fixture, id, "HELD", promotional ? "CANCELLED" : "EXPIRED");
            } finally { commit.countDown(); }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementHoldAndOfferCreateRemainAcyclicWhileReleaseWaitsAtBoundary(boolean promotional) throws Exception {
        Fixture fixture = fixture();
        var other = new AuthenticatedPrincipal(PrincipalId.newId());
        access.registerPrincipal(other.principalId());
        ReservationId due = booking.createHold(new ReservationUseCase.CreateHold(fixture.venue.id(), fixture.slot.id(), other, 2))
            .reservation().id();
        var entry = promotional ? waitlist.register(new WaitlistUseCase.CreateRegistration(fixture.venue.id(), fixture.slot.id(), 2,
            new WaitlistRegistrationKey(UUID.randomUUID()), CUSTOMER)).entry() : null;
        clock.set(storedExpiry(due));
        AtomicReference<Long> createConnection = new AtomicReference<>();
        try (var executor = Executors.newFixedThreadPool(2); Connection fenceOwner = connection()) {
            fenceOwner.setAutoCommit(false);
            lock(fenceOwner, "SELECT singleton_id FROM event_boundary WHERE singleton_id = 1 FOR UPDATE", null);
            var release = executor.submit(() -> expiry.expire(fixture.venue.id(), due, SystemPrincipal.INSTANCE));
            try {
                awaitWaitOn("event_boundary");
                var create = executor.submit(() -> transaction().execute(status -> {
                    createConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    return promotional
                        ? offers.createTarget(SystemPrincipal.INSTANCE, fixture.venue.id(), new WaitlistEntryId(entry.id()), fixture.slot.id())
                            .offer().reservation().id()
                        : hold(fixture);
                }));
                if (promotional) {
                    // Existing RR locking scans can wait on an examined due row. No inverse Slot edge
                    // may be added to the release: Slot -> Booking -> boundary must remain acyclic.
                    awaitWaitOn("reservations", "capacity_allocations");
                    assertRecordLocksIncluding(createConnection.get(), "slot_inventories");
                } else {
                    assertBooking(create.get(5, TimeUnit.SECONDS), "HELD", true);
                }
                fenceOwner.commit();
                release.get(5, TimeUnit.SECONDS);
                ReservationId replacement = create.get(5, TimeUnit.SECONDS);
                assertBooking(replacement, "HELD", true);
                assertThat(eventCount(replacement)).isZero();
                assertBooking(due, "EXPIRED", false);
                assertThat(eventCount(due)).isEqualTo(1);
                assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM capacity_allocations a JOIN reservations r ON r.id = a.reservation_id
                    WHERE a.slot_inventory_id = ? AND a.active = 1
                      AND (r.state IN ('CONFIRMED','CHECKED_IN') OR (r.state = 'HELD' AND r.expires_at > ?))
                    """, Integer.class, bytes(fixture.slot.id().value()), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)))
                    .isEqualTo(1);
            } finally { fenceOwner.rollback(); }
        }
    }

    private void inject(String failure) {
        switch (failure) {
            case "readiness" -> readiness.ready.set(false);
            case "inactive-route" -> registrations.deactivate(registration);
            case "flush" -> jdbc.execute("""
                CREATE TRIGGER fail_capacity_release BEFORE UPDATE ON capacity_allocations FOR EACH ROW
                BEGIN
                    IF OLD.active = 1 AND NEW.active = 0 THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected allocation flush failure';
                    END IF;
                END
                """);
            case "append" -> jdbc.execute("""
                CREATE TRIGGER fail_capacity_event BEFORE INSERT ON event_records FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected append failure'
                """);
            default -> throw new IllegalArgumentException(failure);
        }
    }

    private Fixture fixture() {
        Tenant tenant = tenants.createTenant();
        Venue venue = venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(), "Release", "UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY, new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(13, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10)));
        var resource = resources.createResource(new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Table", 4));
        SlotInventory slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(), venue.id(), resource.id(), START.toString()));
        access.assignMembership(OWNER.principalId(), tenant.id(), TenantRole.OWNER);
        return new Fixture(tenant, venue, slot);
    }

    private ReservationId hold(Fixture fixture) {
        return booking.createHold(new ReservationUseCase.CreateHold(fixture.venue.id(), fixture.slot.id(), CUSTOMER, 2))
            .reservation().id();
    }

    private WaitlistOfferUseCase.OfferView offer(Fixture fixture) {
        var entry = waitlist.register(new WaitlistUseCase.CreateRegistration(fixture.venue.id(), fixture.slot.id(), 2,
            new WaitlistRegistrationKey(UUID.randomUUID()), CUSTOMER)).entry();
        var target = offers.createTarget(SystemPrincipal.INSTANCE, fixture.venue.id(), new WaitlistEntryId(entry.id()), fixture.slot.id());
        assertThat(target.outcome()).isEqualTo(WaitlistOfferUseCase.TargetOutcome.CREATED);
        return target.offer();
    }

    private void transition(Fixture fixture, ReservationId id, ReservationCommand command) {
        booking.transition(fixture.venue.id(), id,
            command == ReservationCommand.CONFIRM || command == ReservationCommand.CANCEL ? CUSTOMER : OWNER, command);
    }

    private org.springframework.test.web.servlet.ResultActions publicCommand(Fixture fixture, ReservationId id, String action) throws Exception {
        return http.perform(post("/api/v1/venues/{venue}/reservations/{id}/{action}", fixture.venue.id().value(), id.value(), action)
            .header("Authorization", "Bearer " + token));
    }

    private void assertEvent(Fixture fixture, ReservationId id, String from, String to) {
        var rows = jdbc.queryForList("SELECT * FROM event_records WHERE aggregate_id = ?", bytes(id.value()));
        assertThat(rows).hasSize(1);
        var event = rows.getFirst();
        assertThat(event.get("event_id")).isNotEqualTo(bytes(id.value()));
        assertThat((byte[]) event.get("tenant_id")).isEqualTo(bytes(fixture.tenant.id().value()));
        assertThat(event.get("aggregate_type")).isEqualTo("Reservation");
        assertThat(event.get("event_type")).isEqualTo(ROUTE.eventType());
        assertThat(event.get("schema_version")).isEqualTo(1);
        assertThat(jdbc.<Instant>queryForObject("SELECT occurred_at FROM event_records WHERE aggregate_id = ?",
            (row, number) -> row.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC), bytes(id.value())))
            .isEqualTo(clock.instant().truncatedTo(ChronoUnit.MICROS));
        assertThat(new JsonMapper().readTree((String) event.get("payload")))
            .isEqualTo(new JsonMapper().valueToTree(Map.of("venueId", fixture.venue.id().value().toString(),
                "resourceId", fixture.slot.resourceId().value().toString(), "slotInventoryId", fixture.slot.id().value().toString(),
                "fromState", from, "toState", to)));
    }

    private void assertBooking(ReservationId id, String state, boolean active) {
        assertThat(jdbc.queryForObject("SELECT state FROM reservations WHERE id = ?", String.class, bytes(id.value()))).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT active FROM capacity_allocations WHERE reservation_id = ?", Boolean.class, bytes(id.value()))).isEqualTo(active);
    }

    private int eventCount(ReservationId id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_records WHERE aggregate_id = ?", Integer.class, bytes(id.value()));
    }

    private Instant storedExpiry(ReservationId id) {
        return jdbc.queryForObject("SELECT expires_at FROM reservations WHERE id = ?",
            (row, number) -> row.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC), bytes(id.value()));
    }

    private long boundary() { return jdbc.queryForObject("SELECT sequence_value FROM event_boundary WHERE singleton_id = 1", Long.class); }
    private int activeRouteSnapshot() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_registrations WHERE registration_id = ? AND deactivation_boundary IS NULL",
            Integer.class, bytes(registration));
    }
    private TransactionTemplate transaction() { return new TransactionTemplate(transactionManager); }
    private Connection connection() throws Exception { return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()); }

    private void lock(Connection connection, String sql, byte[] id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            if (id != null) statement.setBytes(1, id);
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
        }
    }

    private long scalar(Connection connection, String sql, byte[] id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, id);
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        }
    }

    private long awaitWaitOn(String... tables) throws Exception {
        try (var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var query = observer.prepareStatement("""
                 SELECT t.PROCESSLIST_ID FROM performance_schema.data_lock_waits w
                 JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                 JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID
                 WHERE l.OBJECT_SCHEMA = ? AND l.OBJECT_NAME IN (%s)
                 """.formatted(String.join(",", java.util.Collections.nCopies(tables.length, "?"))))) {
            query.setString(1, MYSQL.getDatabaseName());
            for (int index = 0; index < tables.length; index++) query.setString(index + 2, tables[index]);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                try (var rows = query.executeQuery()) { if (rows.next()) return rows.getLong(1); }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            throw new AssertionError("No actual MySQL lock wait on " + java.util.Arrays.toString(tables));
        }
    }

    private void assertRecordLocks(long connection, String... tables) throws Exception {
        assertThat(recordLocks(connection)).contains(tables).doesNotContain("slot_inventories");
    }

    private void assertRecordLocksIncluding(long connection, String... tables) throws Exception {
        assertThat(recordLocks(connection)).contains(tables);
    }

    private java.util.List<String> recordLocks(long connection) throws Exception {
        try (var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var query = observer.prepareStatement("""
                 SELECT DISTINCT l.OBJECT_NAME FROM performance_schema.data_locks l
                 JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID
                 WHERE t.PROCESSLIST_ID = ? AND l.LOCK_TYPE = 'RECORD' AND l.LOCK_STATUS = 'GRANTED'
                 """)) {
            query.setLong(1, connection);
            var names = new java.util.ArrayList<String>();
            try (var rows = query.executeQuery()) { while (rows.next()) names.add(rows.getString(1)); }
            return names;
        }
    }

    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("test gate timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    record Fixture(Tenant tenant, Venue venue, SlotInventory slot) { }
    @TestConfiguration
    static class FixtureConfiguration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
        @Bean TestReadiness readiness() { return new TestReadiness(); }
    }
    static class TestReadiness implements CapacityReleaseReadiness {
        final AtomicBoolean ready = new AtomicBoolean();
        @Override public boolean isReady() { return ready.get(); }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        void set(Instant value) { now.set(value); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
