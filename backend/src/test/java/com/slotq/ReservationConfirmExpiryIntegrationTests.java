package com.slotq;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.ProductApiException;
import com.slotq.booking.application.ProductError;
import com.slotq.booking.application.ReservationExpiryUseCase;
import com.slotq.booking.application.ReservationRepository;
import com.slotq.booking.application.ReservationTransitionNotAllowedException;
import com.slotq.booking.application.ReservationUseCase.ReservationDetails;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.domain.Reservation;
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
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "slotq.auth.dev-bootstrap-enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(ReservationConfirmExpiryIntegrationTests.ClockConfiguration.class)
class ReservationConfirmExpiryIntegrationTests {

    private static final Instant BASE_NOW = Instant.parse("2026-08-30T09:00:00Z");
    private static final Instant EXPIRES_AT = BASE_NOW.plus(Duration.ofMinutes(5));
    private static final Instant STARTS_AT = Instant.parse("2026-08-30T11:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq")
        .withCommand("--log-bin-trust-function-creators=1", "--innodb-lock-wait-timeout=20");

    @Autowired MockMvc mockMvc;
    @Autowired TenantUseCase tenantUseCase;
    @Autowired VenueConfigurationUseCase venueUseCase;
    @Autowired ResourceUseCase resourceUseCase;
    @Autowired SlotInventoryUseCase slotUseCase;
    @Autowired ReservationExpiryUseCase expiryUseCase;
    @Autowired ReservationRepository reservationRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;
    @Autowired CommandClock clock;

    private String customerToken;

    @BeforeEach
    void reset() throws Exception {
        clock.set(BASE_NOW);
        String body = mockMvc.perform(post("/__dev/auth/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fixtureKey\":\"customer-a\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        customerToken = JsonPath.read(body, "$.accessToken");
    }

    @AfterEach
    void removeDatabaseGates() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS gate_reservation_transition");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_expiry_release");
        jdbcTemplate.execute("DROP TABLE IF EXISTS reservation_transition_gate");
    }

    @ParameterizedTest(name = "confirmFirst={0}")
    @ValueSource(booleans = {true, false})
    void confirmExpiryRacePreservesCapturedCommandTimeAndTheCommittedWinner(boolean confirmFirst)
        throws Exception {
        runRace(confirmFirst, EXPIRES_AT.minusNanos(1));
    }

    @ParameterizedTest(name = "confirmFirst={0}")
    @ValueSource(booleans = {true, false})
    void confirmExpiryRaceAtEqualityNeverConfirms(boolean confirmFirst) throws Exception {
        runRace(confirmFirst, EXPIRES_AT);
    }

    @Test
    void expiryReleaseFailureRollsBackBothRowsButDueHoldRemainsEffectivelyExpired() throws Exception {
        Fixture fixture = fixture();
        UUID reservationId = createHold(fixture);
        jdbcTemplate.execute("""
            CREATE TRIGGER fail_expiry_release BEFORE UPDATE ON capacity_allocations
            FOR EACH ROW
            BEGIN
                IF OLD.active = 1 AND NEW.active = 0 THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced expiry allocation failure';
                END IF;
            END
            """);
        CommandTime expiryTime = new CommandTime(EXPIRES_AT);

        assertThatThrownBy(() -> clock.during(expiryTime, () -> expire(fixture, reservationId)))
            .isNotInstanceOf(ProductApiException.class)
            .hasRootCauseInstanceOf(SQLException.class)
            .rootCause().satisfies(failure ->
                assertThat(((SQLException) failure).getSQLState()).isEqualTo("45000"));

        expiryTime.assertCapturedOnceBeforeTransaction(EXPIRES_AT);
        assertReconstructedPairAndGet(fixture, reservationId, "HELD", true, "EXPIRED", false);
    }

    private void runRace(boolean confirmFirst, Instant confirmNow) throws Exception {
        Fixture fixture = fixture();
        UUID reservationId = createHold(fixture);
        installWriteGate(reservationId);
        CommandTime confirmTime = new CommandTime(confirmNow);
        CommandTime expiryTime = new CommandTime(EXPIRES_AT);

        // The two gates only hold locks; both state changes still run through real Product commands.
        try (Connection writeGate = dataSource.getConnection();
             Connection reservationGate = dataSource.getConnection()) {
            writeGate.setAutoCommit(false);
            reservationGate.setAutoCommit(false);
            lockRow(writeGate, "SELECT id FROM reservation_transition_gate WHERE id = ? FOR UPDATE",
                reservationId);
            lockRow(reservationGate, "SELECT id FROM reservations WHERE id = ? FOR UPDATE", reservationId);

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                try {
                    long waits = rowLockWaits();
                    Future<MvcResult> confirm = confirmFirst
                        ? executor.submit(() -> clock.during(confirmTime, () -> confirm(fixture, reservationId)))
                        : null;
                    Future<ReservationDetails> expiry = confirmFirst ? null
                        : executor.submit(() -> clock.during(expiryTime, () -> expire(fixture, reservationId)));

                    // The winner captured commandNow before waiting at the Reservation locking read.
                    awaitLockWaitAfter(waits);
                    CommandTime firstTime = confirmFirst ? confirmTime : expiryTime;
                    firstTime.assertCapturedOnceBeforeTransaction(confirmFirst ? confirmNow : EXPIRES_AT);
                    firstTime.set(confirmFirst ? EXPIRES_AT.plusSeconds(1) : EXPIRES_AT.minusSeconds(1));
                    clock.set(EXPIRES_AT.plusSeconds(1));

                    waits = rowLockWaits();
                    reservationGate.commit();
                    // The winner now owns the Reservation lock, but its UPDATE is held at the DB gate.
                    awaitLockWaitAfter(waits);
                    waits = rowLockWaits();
                    if (confirmFirst) {
                        expiry = executor.submit(() -> clock.during(expiryTime, () -> expire(fixture, reservationId)));
                    } else {
                        confirm = executor.submit(() -> clock.during(confirmTime, () -> confirm(fixture, reservationId)));
                    }
                    awaitLockWaitAfter(waits);
                    confirmTime.assertCapturedOnceBeforeTransaction(confirmNow);
                    expiryTime.assertCapturedOnceBeforeTransaction(EXPIRES_AT);
                    confirmTime.set(EXPIRES_AT.plusSeconds(1));
                    expiryTime.set(EXPIRES_AT.minusSeconds(1));
                    assertThat(confirm.isDone()).isFalse();
                    assertThat(expiry.isDone()).isFalse();
                    // In expiry-first, the public confirm has already authorized against this committed HELD.
                    assertStoredPair(reservationId, "HELD", true);
                    writeGate.commit();

                    boolean confirmed = confirmFirst && confirmNow.isBefore(EXPIRES_AT);
                    MvcResult response = confirm.get(10, TimeUnit.SECONDS);
                    assertThat(response.getResponse().getStatus()).isEqualTo(confirmed ? 200 : 409);
                    assertThat(JsonPath.<String>read(response.getResponse().getContentAsString(),
                        confirmed ? "$.state" : "$.code")).isEqualTo(confirmed ? "CONFIRMED" : "HOLD_EXPIRED");
                    if (confirmed) {
                        Future<ReservationDetails> losingExpiry = expiry;
                        assertThatThrownBy(() -> losingExpiry.get(10, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(ReservationTransitionNotAllowedException.class)
                            .cause().satisfies(failure -> assertThat(((ProductApiException) failure).error())
                                .isEqualTo(ProductError.RESERVATION_TRANSITION_NOT_ALLOWED));
                    } else {
                        ReservationDetails expired = expiry.get(10, TimeUnit.SECONDS);
                        assertThat(expired.effectiveState().name()).isEqualTo("EXPIRED");
                        assertThat(expired.reservation().allocation().active()).isFalse();
                    }
                    confirmTime.assertCapturedOnceBeforeTransaction(confirmNow);
                    expiryTime.assertCapturedOnceBeforeTransaction(EXPIRES_AT);
                } finally {
                    // Release blockers before joining worker threads, including on a failed assertion.
                    reservationGate.rollback();
                    writeGate.rollback();
                }
            }
        }

        boolean confirmed = confirmFirst && confirmNow.isBefore(EXPIRES_AT);
        String state = confirmed ? "CONFIRMED" : "EXPIRED";
        assertReconstructedPairAndGet(fixture, reservationId, state, confirmed, state, confirmed);
    }

    private void installWriteGate(UUID reservationId) {
        jdbcTemplate.execute("CREATE TABLE reservation_transition_gate (id BINARY(16) PRIMARY KEY) ENGINE=InnoDB");
        jdbcTemplate.update("INSERT INTO reservation_transition_gate (id) VALUES (?)", bytes(reservationId));
        jdbcTemplate.execute("""
            CREATE TRIGGER gate_reservation_transition BEFORE UPDATE ON reservations
            FOR EACH ROW
            BEGIN
                DECLARE gate_id BINARY(16);
                IF OLD.state <> NEW.state THEN
                    SELECT id INTO gate_id FROM reservation_transition_gate WHERE id = NEW.id FOR UPDATE;
                END IF;
            END
            """);
    }

    private void lockRow(Connection connection, String sql, UUID reservationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, bytes(reservationId));
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    private long rowLockWaits() {
        return Long.parseLong(jdbcTemplate.queryForMap(
            "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_waits'"
        ).get("Value").toString());
    }

    private void awaitLockWaitAfter(long before) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (rowLockWaits() > before) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("Product command did not reach the next MySQL lock gate");
    }

    private void assertReconstructedPairAndGet(Fixture fixture, UUID reservationId, String stored,
                                               boolean active, String effective, boolean consuming)
        throws Exception {
        clock.set(EXPIRES_AT);
        assertStoredPair(reservationId, stored, active);
        Reservation reconstructed = reservationRepository.find(
            fixture.venue().id(), new ReservationId(reservationId)
        ).orElseThrow();
        assertThat(reconstructed.state().name()).isEqualTo(stored);
        assertThat(reconstructed.allocation().active()).isEqualTo(active);
        assertThat(reconstructed.effectiveState(clock).name()).isEqualTo(effective);
        assertThat(reconstructed.effectiveConsumesCapacity(clock)).isEqualTo(consuming);
        assertThat(reservationRepository.existsEffectiveCapacityConsumer(
            fixture.tenant().id(), fixture.venue().id(), fixture.resource().id(), fixture.slot().id(), EXPIRES_AT
        )).isEqualTo(consuming);
        mockMvc.perform(get("/api/v1/venues/{venueId}/reservations/{reservationId}",
                fixture.venue().id().value(), reservationId)
                .header("Authorization", "Bearer " + customerToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(reservationId.toString()))
            .andExpect(jsonPath("$.state").value(effective));
        assertStoredPair(reservationId, stored, active);
    }

    private void assertStoredPair(UUID reservationId, String state, boolean active) {
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM reservations WHERE id = ?",
            String.class, bytes(reservationId))).isEqualTo(state);
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM capacity_allocations WHERE reservation_id = ?",
            Boolean.class, bytes(reservationId))).isEqualTo(active);
    }

    private MvcResult confirm(Fixture fixture, UUID reservationId) throws Exception {
        return mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/{reservationId}/confirm",
                fixture.venue().id().value(), reservationId)
                .header("Authorization", "Bearer " + customerToken)).andReturn();
    }

    private ReservationDetails expire(Fixture fixture, UUID reservationId) {
        return expiryUseCase.expire(fixture.venue().id(), new ReservationId(reservationId), SystemPrincipal.INSTANCE);
    }

    private UUID createHold(Fixture fixture) throws Exception {
        String body = mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/holds", fixture.venue().id().value())
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotInventoryId\":\"" + fixture.slot().id().value() + "\",\"partySize\":2}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.expiresAt").value(EXPIRES_AT.toString()))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private Fixture fixture() {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "Confirm Expiry Venue", "UTC",
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

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits()).array();
    }

    record Fixture(Tenant tenant, Venue venue, Resource resource, SlotInventory slot) { }
    record Capture(Instant instant, boolean transactionActive) { }

    static final class CommandTime {
        private final AtomicReference<Instant> current;
        private final List<Capture> captures = new CopyOnWriteArrayList<>();

        CommandTime(Instant initial) { current = new AtomicReference<>(initial); }
        void set(Instant value) { current.set(value); }
        Instant capture() {
            Instant value = current.get();
            captures.add(new Capture(value, TransactionSynchronizationManager.isActualTransactionActive()));
            return value;
        }
        void assertCapturedOnceBeforeTransaction(Instant expected) {
            assertThat(captures).containsExactly(new Capture(expected, false));
        }
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        CommandClock commandClock() { return new CommandClock(); }
    }

    static final class CommandClock extends Clock {
        private final AtomicReference<Instant> current = new AtomicReference<>(BASE_NOW);
        private final ThreadLocal<CommandTime> command = new ThreadLocal<>();

        void set(Instant value) { current.set(value); }
        <T> T during(CommandTime time, Callable<T> action) throws Exception {
            command.set(time);
            try {
                return action.call();
            } finally {
                command.remove();
            }
        }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() {
            CommandTime time = command.get();
            return time == null ? current.get() : time.capture();
        }
    }
}
