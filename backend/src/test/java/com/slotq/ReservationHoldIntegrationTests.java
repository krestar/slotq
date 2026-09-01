package com.slotq;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.slotq.booking.application.HoldIdempotencyCleanup;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.tenancy.domain.TenantStatus;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueStatus;
import com.slotq.venue.domain.WeeklyOperatingHours;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "slotq.auth.dev-bootstrap-enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(ReservationHoldIntegrationTests.ClockConfiguration.class)
class ReservationHoldIntegrationTests {

    private static final Instant BASE_NOW = Instant.parse("2026-08-30T09:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq")
        .withCommand("--log-bin-trust-function-creators=1");

    @Autowired MockMvc mockMvc;
    @Autowired TenantUseCase tenantUseCase;
    @Autowired VenueConfigurationUseCase venueUseCase;
    @Autowired ResourceUseCase resourceUseCase;
    @Autowired SlotInventoryUseCase slotUseCase;
    @Autowired ReservationUseCase reservationUseCase;
    @Autowired HoldIdempotencyCleanup idempotencyCleanup;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;

    private String customerA;
    private String customerB;

    @BeforeEach
    void resetClockAndCredentials() throws Exception {
        clock.set(BASE_NOW);
        customerA = bootstrap("customer-a");
        customerB = bootstrap("customer-b");
    }

    @Test
    void createsHoldAndAllocationWithCurrentPolicyWithoutChangingSlotSnapshot() throws Exception {
        Fixture fixture = fixture(4, "2026-08-30T11:00:00Z");
        venueUseCase.updateBookingPolicy(new VenueConfigurationUseCase.UpdateBookingPolicy(
            fixture.tenant().id(), fixture.venue().id(), new BookingPolicyTerms(45, 15, 20, 10)
        ));

        String body = postHold(fixture.venue(), fixture.slot(), 3, customerA)
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.venueId").value(fixture.venue().id().value().toString()))
            .andExpect(jsonPath("$.resourceId").value(fixture.resource().id().value().toString()))
            .andExpect(jsonPath("$.slotInventoryId").value(fixture.slot().id().value().toString()))
            .andExpect(jsonPath("$.state").value("HELD"))
            .andExpect(jsonPath("$.partySize").value(3))
            .andExpect(jsonPath("$.allocationQuantity").value(1))
            .andExpect(jsonPath("$.startsAt").value("2026-08-30T11:00:00Z"))
            .andExpect(jsonPath("$.endsAt").value("2026-08-30T11:30:00Z"))
            .andExpect(jsonPath("$.expiresAt").value("2026-08-30T09:15:00Z"))
            .andExpect(jsonPath("$.cancelAllowedUntil").value("2026-08-30T10:40:00Z"))
            .andExpect(jsonPath("$.noShowEligibleAt").value("2026-08-30T11:10:00Z"))
            .andExpect(jsonPath("$.appliedPolicyVersion").value(2))
            .andReturn().getResponse().getContentAsString();

        UUID reservationId = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(count("reservations", reservationId)).isEqualTo(1);
        assertThat(count("capacity_allocations", reservationId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT applied_policy_version FROM slot_inventories WHERE id = ?", Long.class,
            bytes(fixture.slot().id().value())
        )).isEqualTo(1L);
    }

    @Test
    void replaysSameKeyAndLocationWithCurrentEffectiveRepresentation() throws Exception {
        Fixture fixture = fixture(4, "2026-08-30T11:00:00Z");
        String key = "response-loss-retry";

        MvcResult first = postHold(fixture.venue(), fixture.slot(), 2, customerA, key)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("HELD"))
            .andReturn();
        String reservationId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        String location = first.getResponse().getHeader("Location");

        mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/holds", fixture.venue().id().value())
                .header("Authorization", "Bearer " + customerA)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\n  \"partySize\": 2,\n  \"slotInventoryId\": \""
                    + fixture.slot().id().value() + "\"\n}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", location))
            .andExpect(jsonPath("$.id").value(reservationId))
            .andExpect(jsonPath("$.state").value("HELD"));

        clock.set(BASE_NOW.plus(Duration.ofMinutes(6)));
        postHold(fixture.venue(), fixture.slot(), 2, customerA, key)
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", location))
            .andExpect(jsonPath("$.id").value(reservationId))
            .andExpect(jsonPath("$.state").value("EXPIRED"));

        assertThat(countForSlot("reservations", fixture.slot())).isEqualTo(1);
        assertThat(countForSlot("capacity_allocations", fixture.slot())).isEqualTo(1);
        assertThat(countIdempotency(key)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT state FROM hold_idempotency_records WHERE idempotency_key = ?
            """, String.class, key)).isEqualTo("COMPLETED");
    }

    @Test
    void serializesConcurrentSameKeyRequestsToOneReservationAndAllocation() throws Exception {
        Fixture fixture = fixture(4, "2026-08-30T11:00:00Z");
        String key = "concurrent-retry";
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            var request = (java.util.concurrent.Callable<MvcResult>) () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent HOLD start timed out");
                }
                return postHold(fixture.venue(), fixture.slot(), 2, customerA, key)
                    .andExpect(status().isCreated())
                    .andReturn();
            };
            Future<MvcResult> first = executor.submit(request);
            Future<MvcResult> second = executor.submit(request);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MvcResult> responses = List.of(
                first.get(15, TimeUnit.SECONDS),
                second.get(15, TimeUnit.SECONDS)
            );
            assertThat(new java.util.HashSet<>(List.of(
                JsonPath.<String>read(responses.get(0).getResponse().getContentAsString(), "$.id"),
                JsonPath.<String>read(responses.get(1).getResponse().getContentAsString(), "$.id")
            ))).hasSize(1);
            assertThat(new java.util.HashSet<>(List.of(
                responses.get(0).getResponse().getHeader("Location"),
                responses.get(1).getResponse().getHeader("Location")
            ))).hasSize(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        assertThat(countForSlot("reservations", fixture.slot())).isEqualTo(1);
        assertThat(countForSlot("capacity_allocations", fixture.slot())).isEqualTo(1);
        assertThat(countIdempotency(key)).isEqualTo(1);
    }

    @Test
    void rejectsSameKeyWithDifferentFingerprintWithoutNewSideEffects() throws Exception {
        Fixture fixture = fixture(4, "2026-08-30T11:00:00Z");
        String key = "fingerprint-conflict";
        postHold(fixture.venue(), fixture.slot(), 2, customerA, key)
            .andExpect(status().isCreated());

        postHold(fixture.venue(), fixture.slot(), 3, customerA, key)
            .andExpect(status().isConflict())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(countForSlot("reservations", fixture.slot())).isEqualTo(1);
        assertThat(countForSlot("capacity_allocations", fixture.slot())).isEqualTo(1);
        assertThat(countIdempotency(key)).isEqualTo(1);
    }

    @Test
    void scopesTheSameKeyIndependentlyByCustomerAndTenant() throws Exception {
        Fixture first = fixture(4, "2026-08-30T11:00:00Z");
        SlotInventory secondSlot = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            first.tenant().id(), first.venue().id(), first.resource().id(), "2026-08-30T12:00:00Z"
        ));
        Fixture otherTenant = fixture(4, "2026-08-30T11:00:00Z");
        String key = "shared-scope-key";

        MvcResult firstCustomer = postHold(first.venue(), first.slot(), 2, customerA, key)
            .andExpect(status().isCreated()).andReturn();
        MvcResult secondCustomer = postHold(first.venue(), secondSlot, 2, customerB, key)
            .andExpect(status().isCreated()).andReturn();
        MvcResult secondTenant = postHold(otherTenant.venue(), otherTenant.slot(), 2, customerA, key)
            .andExpect(status().isCreated()).andReturn();

        Set<String> ids = Set.of(
            JsonPath.read(firstCustomer.getResponse().getContentAsString(), "$.id"),
            JsonPath.read(secondCustomer.getResponse().getContentAsString(), "$.id"),
            JsonPath.read(secondTenant.getResponse().getContentAsString(), "$.id")
        );
        assertThat(ids).hasSize(3);
        assertThat(countIdempotency(key)).isEqualTo(3);
    }

    @Test
    void invalidKeysHaveNoSideEffectsAndMissingKeyKeepsOneShotBehavior() throws Exception {
        Fixture fixture = fixture(4, "2026-08-30T11:00:00Z");

        for (String invalidKey : List.of(" ", "k".repeat(256))) {
            postHold(fixture.venue(), fixture.slot(), 2, customerA, invalidKey)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors['Idempotency-Key']").exists());
        }
        assertThat(countForSlot("reservations", fixture.slot())).isZero();
        assertThat(countForSlot("capacity_allocations", fixture.slot())).isZero();
        assertThat(countIdempotency(" ")).isZero();
        assertThat(countIdempotency("k".repeat(256))).isZero();

        postHold(fixture.venue(), fixture.slot(), 2, customerA).andExpect(status().isCreated());
        postHold(fixture.venue(), fixture.slot(), 2, customerA)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CAPACITY_UNAVAILABLE"));
        assertThat(countForSlot("reservations", fixture.slot())).isEqualTo(1);
        assertThat(countIdempotency(" ")).isZero();
        assertThat(countIdempotency("k".repeat(256))).isZero();
    }

    @Test
    void rollsBackReliabilityStateWithReservationAndAllowsTheSameKeyRetry() throws Exception {
        Fixture fixture = fixture(4, "2026-08-30T11:00:00Z");
        String key = "rollback-retry";
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_fail_hold_allocation");
        jdbcTemplate.execute("""
            CREATE TRIGGER test_fail_hold_allocation
            BEFORE INSERT ON capacity_allocations
            FOR EACH ROW
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced allocation failure'
            """);
        try {
            postHold(fixture.venue(), fixture.slot(), 2, customerA, key)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_fail_hold_allocation");
        }

        assertThat(countForSlot("reservations", fixture.slot())).isZero();
        assertThat(countForSlot("capacity_allocations", fixture.slot())).isZero();
        assertThat(countIdempotency(key)).isZero();

        postHold(fixture.venue(), fixture.slot(), 2, customerA, key)
            .andExpect(status().isCreated());
        assertThat(countForSlot("reservations", fixture.slot())).isEqualTo(1);
        assertThat(countForSlot("capacity_allocations", fixture.slot())).isEqualTo(1);
        assertThat(countIdempotency(key)).isEqualTo(1);
    }

    @Test
    void enforcesRetentionBoundaryAndCleanupNeverDeletesInProgressRows() throws Exception {
        Fixture fixture = fixture(4, "2026-09-06T11:00:00Z");
        String key = "retention-boundary";
        MvcResult first = postHold(fixture.venue(), fixture.slot(), 2, customerA, key)
            .andExpect(status().isCreated()).andReturn();
        String firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        clock.set(BASE_NOW.plus(Duration.ofHours(24)).minusNanos(1_000));
        postHold(fixture.venue(), fixture.slot(), 2, customerA, key)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(firstId))
            .andExpect(jsonPath("$.state").value("EXPIRED"));

        clock.set(BASE_NOW.plus(Duration.ofHours(24)));
        MvcResult freshCommand = postHold(fixture.venue(), fixture.slot(), 2, customerA, key)
            .andExpect(status().isCreated()).andReturn();
        String secondId = JsonPath.read(freshCommand.getResponse().getContentAsString(), "$.id");
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(countForSlot("reservations", fixture.slot())).isEqualTo(2);
        assertThat(countIdempotency(key)).isEqualTo(1);

        jdbcTemplate.update("""
            INSERT INTO hold_idempotency_records (
                tenant_id, customer_principal_id, idempotency_key,
                venue_id, slot_inventory_id, party_size, state, started_at
            )
            SELECT tenant_id, customer_principal_id, 'in-progress-retention',
                   venue_id, slot_inventory_id, party_size, 'IN_PROGRESS', ?
              FROM hold_idempotency_records
             WHERE idempotency_key = ?
            """, java.sql.Timestamp.from(BASE_NOW), key);

        clock.set(BASE_NOW.plus(Duration.ofHours(48)));
        assertThat(idempotencyCleanup.cleanupExpired()).isGreaterThanOrEqualTo(1);
        assertThat(countIdempotency(key)).isZero();
        assertThat(countIdempotency("in-progress-retention")).isEqualTo(1);
        assertThat(countForSlot("reservations", fixture.slot())).isEqualTo(2);
        assertThat(countForSlot("capacity_allocations", fixture.slot())).isEqualTo(2);
    }

    @Test
    void rejectsConsumedCapacityAndLeavesOnlyTheFirstReservation() throws Exception {
        Fixture fixture = fixture(4, "2026-08-30T11:00:00Z");
        postHold(fixture.venue(), fixture.slot(), 2, customerA).andExpect(status().isCreated());

        postHold(fixture.venue(), fixture.slot(), 2, customerB)
            .andExpect(status().isConflict())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("CAPACITY_UNAVAILABLE"));

        assertThat(countForSlot("reservations", fixture.slot())).isEqualTo(1);
        assertThat(countForSlot("capacity_allocations", fixture.slot())).isEqualTo(1);
    }

    @Test
    void ignoresDueHeldForOccupancyAndReadDoesNotMaterializeExpiry() throws Exception {
        Fixture fixture = fixture(4, "2026-08-30T11:00:00Z");
        String first = postHold(fixture.venue(), fixture.slot(), 2, customerA)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID firstId = UUID.fromString(JsonPath.read(first, "$.id"));
        clock.set(BASE_NOW.plus(Duration.ofMinutes(6)));

        mockMvc.perform(get("/api/v1/venues/{venueId}/reservations/{reservationId}",
                fixture.venue().id().value(), firstId)
                .header("Authorization", "Bearer " + customerA))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(jsonPath("$.state").value("EXPIRED"));

        postHold(fixture.venue(), fixture.slot(), 2, customerB).andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT state FROM reservations WHERE id = ?", String.class, bytes(firstId)
        )).isEqualTo("HELD");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT active FROM capacity_allocations WHERE reservation_id = ?", Boolean.class, bytes(firstId)
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COALESCE(SUM(CASE
                WHEN allocation.active = TRUE
                 AND (reservation.state IN ('CONFIRMED', 'CHECKED_IN')
                      OR (reservation.state = 'HELD' AND reservation.expires_at > ?))
                THEN allocation.units ELSE 0 END), 0)
              FROM reservations reservation
              JOIN capacity_allocations allocation ON allocation.reservation_id = reservation.id
             WHERE reservation.slot_inventory_id = ?
            """, Integer.class, java.sql.Timestamp.from(clock.instant()), bytes(fixture.slot().id().value())))
            .isEqualTo(1);
        assertThat(countForSlot("reservations", fixture.slot())).isEqualTo(2);
        assertThat(countForSlot("capacity_allocations", fixture.slot())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM capacity_allocations
             WHERE slot_inventory_id = ? AND active = TRUE
            """, Long.class, bytes(fixture.slot().id().value()))).isEqualTo(2);
    }

    @Test
    void hidesCrossVenueAndOtherCustomerReservationsAndValidatesTheRequest() throws Exception {
        Fixture fixture = fixture(2, "2026-08-30T11:00:00Z");
        Fixture other = fixture(2, "2026-08-30T11:00:00Z");
        String created = postHold(fixture.venue(), fixture.slot(), 2, customerA)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID reservationId = UUID.fromString(JsonPath.read(created, "$.id"));

        mockMvc.perform(get("/api/v1/venues/{venueId}/reservations/{reservationId}",
                fixture.venue().id().value(), reservationId)
                .header("Authorization", "Bearer " + customerB))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/venues/{venueId}/reservations/{reservationId}",
                other.venue().id().value(), reservationId)
                .header("Authorization", "Bearer " + customerA))
            .andExpect(status().isNotFound());
        postHold(other.venue(), fixture.slot(), 1, customerA)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/holds", fixture.venue().id().value())
                .header("Authorization", "Bearer " + customerA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotInventoryId\":\"" + fixture.slot().id().value()
                    + "\",\"partySize\":0}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.partySize").exists());
        mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/holds", fixture.venue().id().value())
                .header("Authorization", "Bearer " + customerA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotInventoryId\":\"" + fixture.slot().id().value()
                    + "\",\"partySize\":1,\"customerId\":\"forged\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsInactiveScopesStartedSlotAndUnsupportedPartySize() throws Exception {
        Fixture party = fixture(2, "2026-08-30T11:00:00Z");
        postHold(party.venue(), party.slot(), 3, customerA)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PARTY_SIZE_NOT_SUPPORTED"));

        Fixture tenant = fixture(2, "2026-08-30T11:00:00Z");
        tenantUseCase.updateTenantStatus(tenant.tenant().id(), TenantStatus.INACTIVE);
        assertBookingNotAllowed(tenant);

        Fixture venue = fixture(2, "2026-08-30T11:00:00Z");
        venueUseCase.updateVenue(new VenueConfigurationUseCase.UpdateVenue(
            venue.tenant().id(), venue.venue().id(), venue.venue().name(), VenueStatus.INACTIVE, "UTC",
            venue.venue().operatingHours()
        ));
        assertBookingNotAllowed(venue);

        Fixture resource = fixture(2, "2026-08-30T11:00:00Z");
        resourceUseCase.updateResource(new ResourceUseCase.UpdateResource(
            resource.tenant().id(), resource.venue().id(), resource.resource().id(),
            resource.resource().name(), resource.resource().seatingCapacity(), ResourceStatus.INACTIVE
        ));
        assertBookingNotAllowed(resource);

        Fixture started = fixture(2, "2026-08-30T11:00:00Z");
        clock.set(Instant.parse("2026-08-30T11:00:00Z"));
        assertBookingNotAllowed(started);
    }

    @Test
    void rollsBackReservationAndAllocationWhenTransactionFails() {
        Fixture fixture = fixture(2, "2026-08-30T11:00:00Z");
        PrincipalId unstoredPrincipal = PrincipalId.newId();

        assertThatThrownBy(() -> reservationUseCase.createHold(new ReservationUseCase.CreateHold(
            fixture.venue().id(), fixture.slot().id(), new AuthenticatedPrincipal(unstoredPrincipal), 2
        ))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(countForSlot("reservations", fixture.slot())).isZero();
        assertThat(countForSlot("capacity_allocations", fixture.slot())).isZero();
    }

    @Test
    void authenticationFailureUsesTheCommonProblemEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/holds", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type").exists())
            .andExpect(jsonPath("$.title").exists())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.detail").exists())
            .andExpect(jsonPath("$.instance").exists())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
            .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    private void assertBookingNotAllowed(Fixture fixture) throws Exception {
        postHold(fixture.venue(), fixture.slot(), 1, customerA)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("BOOKING_NOT_ALLOWED"));
    }

    private org.springframework.test.web.servlet.ResultActions postHold(
        Venue venue, SlotInventory slot, int partySize, String token
    ) throws Exception {
        return postHold(venue, slot, partySize, token, null);
    }

    private org.springframework.test.web.servlet.ResultActions postHold(
        Venue venue, SlotInventory slot, int partySize, String token, String idempotencyKey
    ) throws Exception {
        var request = post("/api/v1/venues/{venueId}/reservations/holds", venue.id().value())
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"slotInventoryId\":\"" + slot.id().value()
                + "\",\"partySize\":" + partySize + "}");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private Fixture fixture(int seatingCapacity, String startsAt) {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "Reservation Venue", "UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,
                new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(13, 0)))),
            new BookingPolicyTerms(30, 5, 0, 0)
        ));
        Resource resource = resourceUseCase.createResource(new ResourceUseCase.CreateResource(
            tenant.id(), venue.id(), "Table", seatingCapacity
        ));
        SlotInventory slot = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), startsAt
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

    private long count(String table, UUID reservationId) {
        String column = table.equals("reservations") ? "id" : "reservation_id";
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
            Long.class, bytes(reservationId)
        );
    }

    private long countForSlot(String table, SlotInventory slot) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE slot_inventory_id = ?",
            Long.class, bytes(slot.id().value())
        );
    }

    private long countIdempotency(String key) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM hold_idempotency_records WHERE idempotency_key = ?",
            Long.class, key
        );
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
