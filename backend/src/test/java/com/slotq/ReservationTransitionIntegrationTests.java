package com.slotq;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.application.AccessDeniedException;
import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.application.ReservationAccessTarget;
import com.slotq.auth.application.ReservationAction;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.auth.domain.TenantRole;
import com.slotq.booking.application.ReservationCommand;
import com.slotq.booking.application.ReservationExpiryUseCase;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.WeeklyOperatingHours;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "slotq.auth.dev-bootstrap-enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(ReservationTransitionIntegrationTests.ClockConfiguration.class)
class ReservationTransitionIntegrationTests {

    private static final Instant BASE_NOW = Instant.parse("2026-08-30T09:00:00Z");
    private static final Instant STARTS_AT = Instant.parse("2026-08-30T11:00:00Z");
    private static final PrincipalId CUSTOMER_A = principal("10000000-0000-0000-0000-000000000001");
    private static final PrincipalId CUSTOMER_B = principal("10000000-0000-0000-0000-000000000002");
    private static final PrincipalId OWNER = principal("10000000-0000-0000-0000-000000000003");
    private static final PrincipalId MANAGER = principal("10000000-0000-0000-0000-000000000004");
    private static final PrincipalId STAFF = principal("10000000-0000-0000-0000-000000000005");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq")
        .withCommand("--log-bin-trust-function-creators=1", "--innodb-lock-wait-timeout=1");

    @Autowired MockMvc mockMvc;
    @Autowired TenantUseCase tenantUseCase;
    @Autowired VenueConfigurationUseCase venueUseCase;
    @Autowired ResourceUseCase resourceUseCase;
    @Autowired SlotInventoryUseCase slotUseCase;
    @Autowired ReservationExpiryUseCase expiryUseCase;
    @Autowired AuthorizationUseCase authorizationUseCase;
    @Autowired AccessControlProvisioning accessControlProvisioning;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;
    @Autowired DataSource dataSource;

    private String customerToken;
    private String otherCustomerToken;
    private String ownerToken;
    private String managerToken;
    private String staffToken;

    @BeforeEach
    void reset() throws Exception {
        clock.set(BASE_NOW);
        customerToken = bootstrap("customer-a");
        otherCustomerToken = bootstrap("customer-b");
        ownerToken = bootstrap("tenant-a-owner");
        managerToken = bootstrap("tenant-a-manager");
        staffToken = bootstrap("tenant-a-staff");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_allocation_release");
    }

    @AfterEach
    void removeFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_allocation_release");
    }

    @Test
    void enforcesTheCompleteActorCommandMatrix() {
        Fixture fixture = fixture();
        grantOperators(fixture);
        ReservationAccessTarget target = new ReservationAccessTarget(
            fixture.tenant().id(), fixture.venue().id(), CUSTOMER_A
        );

        assertAllowed(CUSTOMER_A, target, ReservationAction.CONFIRM, ReservationAction.CANCEL);
        assertDenied(CUSTOMER_A, target, ReservationAction.CHECK_IN,
            ReservationAction.NO_SHOW, ReservationAction.COMPLETE);
        assertDenied(OWNER, target, ReservationAction.CONFIRM);
        assertAllowed(OWNER, target, ReservationAction.CANCEL, ReservationAction.CHECK_IN,
            ReservationAction.NO_SHOW, ReservationAction.COMPLETE);
        assertDenied(MANAGER, target, ReservationAction.CONFIRM);
        assertAllowed(MANAGER, target, ReservationAction.CANCEL, ReservationAction.CHECK_IN,
            ReservationAction.NO_SHOW, ReservationAction.COMPLETE);
        assertDenied(STAFF, target, ReservationAction.CONFIRM, ReservationAction.CANCEL);
        assertAllowed(STAFF, target, ReservationAction.CHECK_IN,
            ReservationAction.NO_SHOW, ReservationAction.COMPLETE);
        assertThatThrownBy(() -> authorizationUseCase.authorizeReservationCommand(
            new AuthenticatedPrincipal(CUSTOMER_B), target, ReservationAction.CONFIRM
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void runsAllowedLifecycleCommandsAndRepeatsTheSameTargetNaturally() throws Exception {
        Fixture fixture = fixture();
        grantOperators(fixture);
        UUID reservationId = createHold(fixture, customerToken);

        command(fixture, reservationId, "confirm", customerToken)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CONFIRMED"));
        command(fixture, reservationId, "confirm", customerToken)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CONFIRMED"));

        clock.set(STARTS_AT);
        command(fixture, reservationId, "check-in", staffToken)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CHECKED_IN"));
        command(fixture, reservationId, "check-in", staffToken)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CHECKED_IN"));
        command(fixture, reservationId, "complete", managerToken)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));
        command(fixture, reservationId, "complete", managerToken)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));

        assertThat(storedState(reservationId)).isEqualTo("COMPLETED");
        assertThat(allocationActive(reservationId)).isFalse();
    }

    @Test
    void supportsOwnerCancelAndStaffNoShowWithAllocationRelease() throws Exception {
        Fixture cancelFixture = fixture();
        grantOperators(cancelFixture);
        UUID cancelled = createHold(cancelFixture, customerToken);
        command(cancelFixture, cancelled, "confirm", customerToken).andExpect(status().isOk());
        command(cancelFixture, cancelled, "cancel", ownerToken)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
        assertThat(allocationActive(cancelled)).isFalse();

        Fixture noShowFixture = fixture();
        grantOperators(noShowFixture);
        UUID noShow = createHold(noShowFixture, customerToken);
        command(noShowFixture, noShow, "confirm", customerToken).andExpect(status().isOk());
        clock.set(STARTS_AT.plus(Duration.ofMinutes(10)));
        command(noShowFixture, noShow, "no-show", staffToken)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NO_SHOW"));
        assertThat(allocationActive(noShow)).isFalse();
    }

    @Test
    void materializesDueHoldBeforeReturningStableConflictAndDoesNotOnHiddenAccess() throws Exception {
        Fixture hiddenFixture = fixture();
        UUID hidden = createHold(hiddenFixture, customerToken);
        clock.set(BASE_NOW.plus(Duration.ofMinutes(5)));
        command(hiddenFixture, hidden, "confirm", otherCustomerToken)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(storedState(hidden)).isEqualTo("HELD");
        assertThat(allocationActive(hidden)).isTrue();

        command(hiddenFixture, hidden, "confirm", customerToken)
            .andExpect(status().isConflict())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        assertThat(storedState(hidden)).isEqualTo("EXPIRED");
        assertThat(allocationActive(hidden)).isFalse();
        command(hiddenFixture, hidden, "confirm", customerToken)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
    }

    @Test
    void materializesDueHoldForOperatorCommandThenReturnsTransitionConflict() throws Exception {
        Fixture fixture = fixture();
        grantOperators(fixture);
        UUID reservationId = createHold(fixture, customerToken);
        clock.set(BASE_NOW.plus(Duration.ofMinutes(5)));

        command(fixture, reservationId, "check-in", ownerToken)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RESERVATION_TRANSITION_NOT_ALLOWED"));

        assertThat(storedState(reservationId)).isEqualTo("EXPIRED");
        assertThat(allocationActive(reservationId)).isFalse();
    }

    @Test
    void appliesEveryTimeEqualityBoundaryWithoutSleeping() throws Exception {
        Fixture confirmFixture = fixture();
        UUID due = createHold(confirmFixture, customerToken);
        clock.set(BASE_NOW.plus(Duration.ofMinutes(5)));
        command(confirmFixture, due, "confirm", customerToken)
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));

        Fixture cancelFixture = fixture();
        UUID cancel = createHold(cancelFixture, customerToken);
        command(cancelFixture, cancel, "confirm", customerToken).andExpect(status().isOk());
        clock.set(STARTS_AT.minus(Duration.ofMinutes(20)));
        command(cancelFixture, cancel, "cancel", customerToken)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CANCELLATION_WINDOW_CLOSED"));

        Fixture checkInFixture = fixture();
        UUID checkIn = createHold(checkInFixture, customerToken);
        command(checkInFixture, checkIn, "confirm", customerToken).andExpect(status().isOk());
        grantOperators(checkInFixture);
        clock.set(STARTS_AT);
        command(checkInFixture, checkIn, "check-in", ownerToken)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CHECKED_IN"));

        Fixture noShowFixture = fixture();
        UUID noShow = createHold(noShowFixture, customerToken);
        command(noShowFixture, noShow, "confirm", customerToken).andExpect(status().isOk());
        grantOperators(noShowFixture);
        clock.set(STARTS_AT.plus(Duration.ofMinutes(10)));
        command(noShowFixture, noShow, "no-show", managerToken)
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NO_SHOW"));
    }

    @Test
    void exposesOnlySameScopeForbiddenAs403AndHidesOtherVenue() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        grantOperators(fixture);
        UUID reservationId = createHold(fixture, customerToken);
        clock.set(BASE_NOW.plus(Duration.ofMinutes(5)));

        command(fixture, reservationId, "check-in", customerToken)
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        command(fixture, reservationId, "cancel", staffToken)
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        command(fixture, reservationId, "confirm", ownerToken)
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(storedState(reservationId)).isEqualTo("HELD");
        assertThat(allocationActive(reservationId)).isTrue();
        command(other, reservationId, "cancel", ownerToken)
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void internalSystemExpiryIsRepeatableAndHasNoPublicEndpoint() throws Exception {
        Fixture fixture = fixture();
        UUID reservationId = createHold(fixture, customerToken);
        clock.set(BASE_NOW.plus(Duration.ofMinutes(5)));

        assertThat(expiryUseCase.expire(
            fixture.venue().id(), new ReservationId(reservationId), SystemPrincipal.INSTANCE
        ).effectiveState().name()).isEqualTo("EXPIRED");
        assertThat(expiryUseCase.expire(
            fixture.venue().id(), new ReservationId(reservationId), SystemPrincipal.INSTANCE
        ).effectiveState().name()).isEqualTo("EXPIRED");
        command(fixture, reservationId, "expire", ownerToken).andExpect(status().isNotFound());
        assertThat(allocationActive(reservationId)).isFalse();
    }

    @Test
    void rollsBackReservationAndAllocationTogetherOnActualMysqlFailure() throws Exception {
        Fixture fixture = fixture();
        UUID reservationId = createHold(fixture, customerToken);
        jdbcTemplate.execute("""
            CREATE TRIGGER fail_allocation_release BEFORE UPDATE ON capacity_allocations
            FOR EACH ROW
            BEGIN
                IF OLD.reservation_id = NEW.reservation_id AND NEW.active = 0 THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced allocation failure';
                END IF;
            END
            """);

        command(fixture, reservationId, "cancel", customerToken)
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(storedState(reservationId)).isEqualTo("HELD");
        assertThat(allocationActive(reservationId)).isTrue();
    }

    @Test
    void serializesCompetingLifecycleWritesAndPreservesTheLegalWinner() throws Exception {
        Fixture fixture = fixture();
        grantOperators(fixture);
        UUID reservationId = createHold(fixture, customerToken);
        command(fixture, reservationId, "confirm", customerToken).andExpect(status().isOk());
        clock.set(STARTS_AT);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> checkIn = executor.submit(() -> concurrentCommand(
                fixture, reservationId, "check-in", staffToken, ready, start
            ));
            Future<MvcResult> cancel = executor.submit(() -> concurrentCommand(
                fixture, reservationId, "cancel", ownerToken, ready, start
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MvcResult> results = List.of(
                checkIn.get(10, TimeUnit.SECONDS), cancel.get(10, TimeUnit.SECONDS)
            );
            assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 409);
            assertThat(results.stream()
                .filter(result -> result.getResponse().getStatus() == 200)
                .map(result -> JsonPath.<String>read(
                    responseBody(result), "$.state"
                ))).containsExactly("CHECKED_IN");
            assertThat(results.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .map(result -> JsonPath.<String>read(
                    responseBody(result), "$.code"
                ))).allMatch(Set.of(
                    "CANCELLATION_WINDOW_CLOSED", "RESERVATION_TRANSITION_NOT_ALLOWED"
                )::contains);
        } finally {
            start.countDown();
        }

        assertThat(storedState(reservationId)).isEqualTo("CHECKED_IN");
        assertThat(allocationActive(reservationId)).isTrue();
    }

    @Test
    void staleWriterReevaluatesTheCommittedWinnerBeforeReturningTheExistingBusinessCode()
        throws Exception {
        Fixture fixture = fixture();
        UUID reservationId = createHold(fixture, customerToken);
        long lockWaitsBefore = innodbRowLockWaits();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch start = new CountDownLatch(1);

        try (Connection winner = dataSource.getConnection();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            winner.setAutoCommit(false);
            lockReservation(winner, reservationId);
            Future<MvcResult> staleConfirm = executor.submit(() -> concurrentCommand(
                fixture, reservationId, "confirm", customerToken, ready, start
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            awaitLockWaitAfter(lockWaitsBefore);

            updateWinnerState(winner, reservationId, "CANCELLED", false);
            winner.commit();

            MvcResult result = staleConfirm.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(
                result.getResponse().getContentAsString(), "$.code"
            )).isEqualTo("RESERVATION_TRANSITION_NOT_ALLOWED");
        } finally {
            start.countDown();
        }

        assertThat(storedState(reservationId)).isEqualTo("CANCELLED");
        assertThat(allocationActive(reservationId)).isFalse();
    }

    @Test
    void mapsMysqlLockWaitTimeoutToSystemFailureWithoutBusinessConflict() throws Exception {
        Fixture fixture = fixture();
        UUID reservationId = createHold(fixture, customerToken);

        try (Connection blocker = dataSource.getConnection();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            lockReservation(blocker, reservationId);

            Future<MvcResult> blocked = executor.submit(() -> command(
                fixture, reservationId, "confirm", customerToken
            ).andReturn());
            MvcResult result = blocked.get(5, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(500);
            assertThat(JsonPath.<String>read(
                result.getResponse().getContentAsString(), "$.code"
            )).isEqualTo("INTERNAL_ERROR");
            blocker.rollback();
        }

        assertThat(storedState(reservationId)).isEqualTo("HELD");
        assertThat(allocationActive(reservationId)).isTrue();
    }

    private MvcResult concurrentCommand(Fixture fixture, UUID reservationId, String command,
                                        String token, CountDownLatch ready, CountDownLatch start)
        throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent Reservation command start timed out");
        }
        return command(fixture, reservationId, command, token).andReturn();
    }

    private String responseBody(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void lockReservation(Connection connection, UUID reservationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM reservations WHERE id = ? FOR UPDATE"
        )) {
            statement.setBytes(1, bytes(reservationId));
            assertThat(statement.executeQuery().next()).isTrue();
        }
    }

    private void updateWinnerState(Connection connection, UUID reservationId,
                                   String state, boolean allocationActive) throws Exception {
        try (PreparedStatement reservation = connection.prepareStatement(
            "UPDATE reservations SET state = ? WHERE id = ?"
        ); PreparedStatement allocation = connection.prepareStatement(
            "UPDATE capacity_allocations SET active = ? WHERE reservation_id = ?"
        )) {
            reservation.setString(1, state);
            reservation.setBytes(2, bytes(reservationId));
            assertThat(reservation.executeUpdate()).isEqualTo(1);
            allocation.setBoolean(1, allocationActive);
            allocation.setBytes(2, bytes(reservationId));
            assertThat(allocation.executeUpdate()).isEqualTo(1);
        }
    }

    private long innodbRowLockWaits() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_waits'"
        );
        return Long.parseLong(row.get("Value").toString());
    }

    private void awaitLockWaitAfter(long before) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (innodbRowLockWaits() > before) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Reservation command did not reach the MySQL row-lock boundary");
    }

    private void assertAllowed(PrincipalId principal, ReservationAccessTarget target,
                               ReservationAction... actions) {
        for (ReservationAction action : actions) {
            assertThatCode(() -> authorizationUseCase.authorizeReservationCommand(
                new AuthenticatedPrincipal(principal), target, action
            )).doesNotThrowAnyException();
        }
    }

    private void assertDenied(PrincipalId principal, ReservationAccessTarget target,
                              ReservationAction... actions) {
        for (ReservationAction action : actions) {
            assertThatThrownBy(() -> authorizationUseCase.authorizeReservationCommand(
                new AuthenticatedPrincipal(principal), target, action
            )).isInstanceOf(AccessDeniedException.class);
        }
    }

    private void grantOperators(Fixture fixture) {
        accessControlProvisioning.assignMembership(OWNER, fixture.tenant().id(), TenantRole.OWNER);
        accessControlProvisioning.assignMembership(MANAGER, fixture.tenant().id(), TenantRole.MANAGER);
        accessControlProvisioning.grantVenue(
            MANAGER, fixture.tenant().id(), TenantRole.MANAGER, fixture.venue().id()
        );
        accessControlProvisioning.assignMembership(STAFF, fixture.tenant().id(), TenantRole.STAFF);
        accessControlProvisioning.grantVenue(
            STAFF, fixture.tenant().id(), TenantRole.STAFF, fixture.venue().id()
        );
    }

    private UUID createHold(Fixture fixture, String token) throws Exception {
        String body = mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/holds",
                fixture.venue().id().value())
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotInventoryId\":\"" + fixture.slot().id().value()
                    + "\",\"partySize\":2}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private org.springframework.test.web.servlet.ResultActions command(
        Fixture fixture, UUID reservationId, String command, String token
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/{reservationId}/{command}",
            fixture.venue().id().value(), reservationId, command)
            .header("Authorization", "Bearer " + token));
    }

    private Fixture fixture() {
        clock.set(BASE_NOW);
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "Reservation Venue", "UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,
                new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(13, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10)
        ));
        Resource resource = resourceUseCase.createResource(new ResourceUseCase.CreateResource(
            tenant.id(), venue.id(), "Table", 4
        ));
        SlotInventory slot = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), STARTS_AT.toString()
        ));
        return new Fixture(tenant, venue, resource, slot);
    }

    private String bootstrap(String fixtureKey) throws Exception {
        String body = mockMvc.perform(post("/__dev/auth/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fixtureKey\":\"" + fixtureKey + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String storedState(UUID reservationId) {
        return jdbcTemplate.queryForObject(
            "SELECT state FROM reservations WHERE id = ?", String.class, bytes(reservationId)
        );
    }

    private boolean allocationActive(UUID reservationId) {
        return jdbcTemplate.queryForObject(
            "SELECT active FROM capacity_allocations WHERE reservation_id = ?",
            Boolean.class, bytes(reservationId)
        );
    }

    private static PrincipalId principal(String value) {
        return new PrincipalId(UUID.fromString(value));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits()).array();
    }

    record Fixture(Tenant tenant, Venue venue, Resource resource, SlotInventory slot) { }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(BASE_NOW);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant initial) { this.instant = new AtomicReference<>(initial); }
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
