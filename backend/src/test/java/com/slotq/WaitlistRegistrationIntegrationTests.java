package com.slotq;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.waitlist.application.WaitlistOfferUseCase;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistOfferId;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.application.PromotionalReservationUseCase;
import com.slotq.booking.domain.SlotInventory;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "slotq.auth.dev-bootstrap-enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(WaitlistRegistrationIntegrationTests.ClockConfiguration.class)
class WaitlistRegistrationIntegrationTests {

    private static final Instant BASE_NOW = Instant.parse("2026-09-13T00:00:00Z");
    private static final PrincipalId CUSTOMER_A = principal("10000000-0000-0000-0000-000000000001");
    private static final PrincipalId CUSTOMER_B = principal("10000000-0000-0000-0000-000000000002");
    private static final PrincipalId OWNER = principal("10000000-0000-0000-0000-000000000003");
    private static final PrincipalId MANAGER = principal("10000000-0000-0000-0000-000000000004");
    private static final PrincipalId STAFF = principal("10000000-0000-0000-0000-000000000005");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq_waitlist")
        .withCommand("--log-bin-trust-function-creators=1");

    @Autowired MockMvc mockMvc;
    @Autowired TenantUseCase tenantUseCase;
    @Autowired VenueConfigurationUseCase venueUseCase;
    @Autowired ResourceUseCase resourceUseCase;
    @Autowired SlotInventoryUseCase slotUseCase;
    @Autowired AccessControlProvisioning access;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MutableClock clock;
    @Autowired WaitlistOfferUseCase waitlistOffers;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired PromotionalReservationUseCase promotionalReservations;

    private String customerA;
    private String customerB;
    private String owner;
    private String manager;
    private String staff;

    @BeforeEach
    void authenticateFixtures() throws Exception {
        clock.set(BASE_NOW);
        customerA = bootstrap("customer-a");
        customerB = bootstrap("customer-b");
        owner = bootstrap("tenant-a-owner");
        manager = bootstrap("tenant-a-manager");
        staff = bootstrap("tenant-a-staff");
    }

    @AfterEach
    void removeFaults() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_waitlist_registration_completion");
        jdbc.execute("DROP TRIGGER IF EXISTS gate_waitlist_cancel");
        jdbc.execute("DROP TRIGGER IF EXISTS gate_waitlist_registration");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_waitlist_offer_insert");
        jdbc.execute("DROP TRIGGER IF EXISTS fail_waitlist_offer_update");
        jdbc.execute("DROP TRIGGER IF EXISTS gate_offer_accept");
        jdbc.execute("DROP TRIGGER IF EXISTS gate_offer_reject");
        jdbc.execute("DROP TRIGGER IF EXISTS gate_target_offer");
        jdbc.execute("DROP TRIGGER IF EXISTS gate_offer_expiry");
        jdbc.execute("DROP TABLE IF EXISTS waitlist_command_gate");
    }

    @Test
    void normalizesDemandPreservesReceiptsAndAllowsExplicitRejoinAfterCancel() throws Exception {
        Fixture fixture = fixture(2, 4, "2026-09-13T11:00:00Z", "UTC");
        String firstKey = UUID.randomUUID().toString();

        MvcResult first = register(fixture.venue(), fixture.firstSlot(), 4, customerA, firstKey)
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.state").value("WAITING"))
            .andExpect(jsonPath("$.allowedActions[0]").value("CANCEL"))
            .andExpect(jsonPath("$.offerId").value(org.hamcrest.Matchers.nullValue()))
            .andReturn();
        String entryId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        String location = first.getResponse().getHeader("Location");

        register(fixture.venue(), fixture.secondSlot(), 4, customerA, UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(header().string("Location", location))
            .andExpect(jsonPath("$.id").value(entryId));
        register(fixture.venue(), fixture.secondSlot(), 4, customerA, firstKey)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        register(fixture.venue(), fixture.firstSlot(), 4, customerA, firstKey)
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", location))
            .andExpect(jsonPath("$.id").value(entryId));

        mockMvc.perform(get("/api/v1/venues/{venueId}/waitlist-registration-requests/{key}",
                fixture.venue().id().value(), firstKey).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.entryId").value(entryId))
            .andExpect(jsonPath("$.entryLocation").value(location))
            .andExpect(jsonPath("$.originalStatus").value(201));

        mockMvc.perform(post(location + "/cancel").header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"))
            .andExpect(jsonPath("$.allowedActions").isEmpty());
        register(fixture.venue(), fixture.firstSlot(), 4, customerA, firstKey)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(entryId))
            .andExpect(jsonPath("$.state").value("CANCELLED"));

        clock.set(BASE_NOW.plusNanos(1_000));
        String rejoined = JsonPath.read(register(
            fixture.venue(), fixture.firstSlot(), 4, customerA, UUID.randomUUID().toString()
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(rejoined).isNotEqualTo(entryId);
        assertThat(count("waitlist_entries", fixture.venue().id().value())).isEqualTo(2);

        long before = count("waitlist_entries", fixture.venue().id().value());
        clock.set(fixture.firstSlot().startsAt());
        mockMvc.perform(get(location).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
        mockMvc.perform(get("/api/v1/venues/{venueId}/waitlist-entries", fixture.venue().id().value())
                .param("date", "2026-09-13").header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        assertThat(count("waitlist_entries", fixture.venue().id().value())).isEqualTo(before);
    }

    @Test
    void validatesDemandKeyBodyTimeAndCustomerScopeWithNoStoreErrors() throws Exception {
        Fixture fixture = fixture(2, 4, "2026-09-13T11:00:00Z", "UTC");
        String path = "/api/v1/venues/" + fixture.venue().id().value() + "/waitlist-entries";

        mockMvc.perform(post(path).header("Authorization", bearer(customerA))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(fixture.firstSlot(), 2)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(post(path).header("Authorization", bearer(customerA))
                .header("Idempotency-Key", "not-a-uuid").contentType(MediaType.APPLICATION_JSON)
                .content(body(fixture.firstSlot(), 2)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(path).header("Authorization", bearer(customerA))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotInventoryId\":\"" + fixture.firstSlot().id().value()
                    + "\",\"partySize\":2,\"customerId\":\"forged\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        register(fixture.venue(), fixture.firstSlot(), 5, customerA, UUID.randomUUID().toString())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WAITLIST_DEMAND_NOT_ALLOWED"));

        Venue other = fixture(4, null, "2026-09-13T11:00:00Z", "UTC").venue();
        register(other, fixture.firstSlot(), 2, customerA, UUID.randomUUID().toString())
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        Fixture inactiveResource = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        Resource currentResource = resourceUseCase.getResource(
            inactiveResource.tenant().id(), inactiveResource.venue().id(),
            inactiveResource.firstSlot().resourceId()
        );
        resourceUseCase.updateResource(new ResourceUseCase.UpdateResource(
            inactiveResource.tenant().id(), inactiveResource.venue().id(), currentResource.id(),
            currentResource.name(), currentResource.seatingCapacity(), ResourceStatus.INACTIVE
        ));
        register(inactiveResource.venue(), inactiveResource.firstSlot(), 2,
            customerA, UUID.randomUUID().toString())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WAITLIST_DEMAND_NOT_ALLOWED"));

        Fixture inactiveVenue = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        venueUseCase.updateVenue(new VenueConfigurationUseCase.UpdateVenue(
            inactiveVenue.tenant().id(), inactiveVenue.venue().id(), inactiveVenue.venue().name(),
            VenueStatus.INACTIVE, inactiveVenue.venue().timezone().getId(),
            inactiveVenue.venue().operatingHours()
        ));
        register(inactiveVenue.venue(), inactiveVenue.firstSlot(), 2,
            customerA, UUID.randomUUID().toString())
            .andExpect(status().isConflict());

        Fixture inactiveTenant = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        tenantUseCase.updateTenantStatus(inactiveTenant.tenant().id(), TenantStatus.INACTIVE);
        register(inactiveTenant.venue(), inactiveTenant.firstSlot(), 2,
            customerA, UUID.randomUUID().toString())
            .andExpect(status().isConflict());

        clock.set(fixture.firstSlot().startsAt());
        register(fixture.venue(), fixture.firstSlot(), 2, customerA, UUID.randomUUID().toString())
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("WAITLIST_DEMAND_NOT_ALLOWED"));

        clock.set(BASE_NOW);
        String entryId = JsonPath.read(register(
            fixture.venue(), fixture.firstSlot(), 2, customerA, UUID.randomUUID().toString()
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get("/api/v1/venues/{venueId}/waitlist-entries/{entryId}",
                fixture.venue().id().value(), entryId).header("Authorization", bearer(customerB)))
            .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/api/v1/venues/{venueId}/waitlist-entries/{entryId}",
                fixture.venue().id().value(), entryId))
            .andExpect(status().isUnauthorized()).andExpect(header().string(
                "Cache-Control", org.hamcrest.Matchers.containsString("no-store")
            ));
    }

    @Test
    void serializesSameAndDifferentRegistrationKeysToOneActiveEntry() throws Exception {
        Fixture fixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String sameKey = UUID.randomUUID().toString();
        String otherKey = UUID.randomUUID().toString();
        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(3)) {
            List<Future<MvcResult>> futures = List.of(sameKey, sameKey, otherKey).stream().map(key ->
                executor.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return register(fixture.venue(), fixture.firstSlot(), 2, customerA, key).andReturn();
                })
            ).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<MvcResult> results = futures.stream().map(future -> {
                try {
                    return future.get(20, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }).toList();
            assertThat(results).allSatisfy(result ->
                assertThat(result.getResponse().getStatus()).isIn(200, 201));
            assertThat(results.stream().map(result -> JsonPath.<String>read(
                responseBody(result), "$.id"
            )).collect(java.util.stream.Collectors.toSet())).hasSize(1);
            assertThat(results.subList(0, 2).stream()
                .map(result -> result.getResponse().getStatus()).distinct().toList()).hasSize(1);
        }

        assertThat(count("waitlist_entries", fixture.venue().id().value())).isEqualTo(1);
        assertThat(count("waitlist_registration_requests", fixture.venue().id().value())).isEqualTo(2);
    }

    @Test
    void rollsBackNewAndDuplicateRegistrationReceiptAtomically() throws Exception {
        Fixture newFixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        installCompletionFailure();
        register(newFixture.venue(), newFixture.firstSlot(), 2, customerA, UUID.randomUUID().toString())
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertThat(count("waitlist_demands", newFixture.venue().id().value())).isZero();
        assertThat(count("waitlist_entries", newFixture.venue().id().value())).isZero();
        assertThat(count("waitlist_registration_requests", newFixture.venue().id().value())).isZero();

        jdbc.execute("DROP TRIGGER fail_waitlist_registration_completion");
        String successfulKey = UUID.randomUUID().toString();
        register(newFixture.venue(), newFixture.firstSlot(), 2, customerA, successfulKey)
            .andExpect(status().isCreated());
        installCompletionFailure();
        String duplicateKey = UUID.randomUUID().toString();
        register(newFixture.venue(), newFixture.firstSlot(), 2, customerA, duplicateKey)
            .andExpect(status().isInternalServerError());
        assertThat(count("waitlist_entries", newFixture.venue().id().value())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM waitlist_registration_requests
             WHERE venue_id = ? AND idempotency_key = ?
            """, Integer.class, bytes(newFixture.venue().id().value()), bytes(UUID.fromString(duplicateKey))))
            .isZero();
    }

    @Test
    void cancelAndRegisterCommitAccordingToBothDatabaseSerializationOrders() throws Exception {
        Fixture cancelFirstFixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        MvcResult initial = register(
            cancelFirstFixture.venue(), cancelFirstFixture.firstSlot(), 2,
            customerA, UUID.randomUUID().toString()
        ).andExpect(status().isCreated()).andReturn();
        String initialId = JsonPath.read(initial.getResponse().getContentAsString(), "$.id");
        String cancelPath = initial.getResponse().getHeader("Location") + "/cancel";
        installCommandGate("gate_waitlist_cancel", "waitlist_entries", "OLD.state <> NEW.state");

        try (Connection gate = dataSource.getConnection();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            lockCommandGate(gate);
            long waits = rowLockWaits();
            Future<MvcResult> cancel = executor.submit(() -> mockMvc.perform(post(cancelPath)
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            waits = rowLockWaits();
            Future<MvcResult> register = executor.submit(() -> register(
                cancelFirstFixture.venue(), cancelFirstFixture.firstSlot(), 2,
                customerA, UUID.randomUUID().toString()
            ).andReturn());
            awaitLockWaitAfter(waits);
            gate.commit();

            assertThat(cancel.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            MvcResult rejoin = register.get(10, TimeUnit.SECONDS);
            assertThat(rejoin.getResponse().getStatus()).isEqualTo(201);
            assertThat(JsonPath.<String>read(responseBody(rejoin), "$.id")).isNotEqualTo(initialId);
        }
        dropCommandGate();

        Fixture registerFirstFixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        MvcResult existing = register(
            registerFirstFixture.venue(), registerFirstFixture.firstSlot(), 2,
            customerA, UUID.randomUUID().toString()
        ).andExpect(status().isCreated()).andReturn();
        String existingId = JsonPath.read(existing.getResponse().getContentAsString(), "$.id");
        String existingCancelPath = existing.getResponse().getHeader("Location") + "/cancel";
        installCommandGate(
            "gate_waitlist_registration", "waitlist_registration_requests",
            "OLD.state = 'IN_PROGRESS' AND NEW.state = 'COMPLETED'"
        );

        try (Connection gate = dataSource.getConnection();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            lockCommandGate(gate);
            long waits = rowLockWaits();
            Future<MvcResult> duplicate = executor.submit(() -> register(
                registerFirstFixture.venue(), registerFirstFixture.firstSlot(), 2,
                customerA, UUID.randomUUID().toString()
            ).andReturn());
            awaitLockWaitAfter(waits);
            waits = rowLockWaits();
            Future<MvcResult> cancel = executor.submit(() -> mockMvc.perform(post(existingCancelPath)
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            gate.commit();

            MvcResult duplicateResult = duplicate.get(10, TimeUnit.SECONDS);
            assertThat(duplicateResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(JsonPath.<String>read(responseBody(duplicateResult), "$.id")).isEqualTo(existingId);
            assertThat(cancel.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM waitlist_entries
             WHERE venue_id = ? AND state IN ('WAITING', 'OFFERED')
            """, Integer.class, bytes(registerFirstFixture.venue().id().value()))).isZero();
    }

    @Test
    void managementReadUsesOwnerManagerScopeSlotEligibilityAndStableKeysetPagination() throws Exception {
        Fixture fixture = fixture(2, 4, "2026-09-13T11:00:00Z", "UTC");
        access.assignMembership(OWNER, fixture.tenant().id(), TenantRole.OWNER);
        access.assignMembership(MANAGER, fixture.tenant().id(), TenantRole.MANAGER);
        access.assignMembership(STAFF, fixture.tenant().id(), TenantRole.STAFF);
        access.grantVenue(MANAGER, fixture.tenant().id(), TenantRole.MANAGER, fixture.venue().id());
        access.grantVenue(STAFF, fixture.tenant().id(), TenantRole.STAFF, fixture.venue().id());

        register(fixture.venue(), fixture.firstSlot(), 4, customerA, UUID.randomUUID().toString())
            .andExpect(status().isCreated());
        register(fixture.venue(), fixture.secondSlot(), 4, customerB, UUID.randomUUID().toString())
            .andExpect(status().isCreated());

        String base = "/api/v1/management/venues/" + fixture.venue().id().value()
            + "/waitlist-entries";
        MvcResult firstPage = mockMvc.perform(get(base).param("date", "2026-09-13").param("limit", "1")
                .header("Authorization", bearer(owner)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].allowedActions").isEmpty())
            .andExpect(jsonPath("$.nextCursor").isNotEmpty()).andReturn();
        String cursor = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextCursor");
        String firstId = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.items[0].id");
        mockMvc.perform(get(base).param("date", "2026-09-13").param("limit", "1")
                .param("cursor", cursor).header("Authorization", bearer(manager)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(org.hamcrest.Matchers.not(firstId)));
        mockMvc.perform(get(base).param("date", "2026-09-14").param("cursor", cursor)
                .header("Authorization", bearer(manager)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(base).param("date", "2026-09-13")
                .param("slotInventoryId", fixture.firstSlot().id().value().toString())
                .header("Authorization", bearer(manager)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].eligibleForSlot").value(false));
        mockMvc.perform(get(base).param("date", "2026-09-13")
                .param("slotInventoryId", fixture.secondSlot().id().value().toString())
                .header("Authorization", bearer(owner)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].eligibleForSlot").value(true));
        mockMvc.perform(get(base).param("date", "2026-09-13").header("Authorization", bearer(staff)))
            .andExpect(status().isForbidden());

        Fixture unassigned = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        mockMvc.perform(get("/api/v1/management/venues/{venueId}/waitlist-entries",
                unassigned.venue().id().value()).param("date", "2026-09-13")
                .header("Authorization", bearer(manager)))
            .andExpect(status().isNotFound());
    }

    @Test
    void venueLocalDateUsesDstAwareDayBoundaries() throws Exception {
        Fixture fixture = fixture(4, null, "2026-11-01T09:00:00-05:00", "America/New_York");
        register(fixture.venue(), fixture.firstSlot(), 2, customerA, UUID.randomUUID().toString())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.venueTimezone").value("America/New_York"));

        mockMvc.perform(get("/api/v1/venues/{venueId}/waitlist-entries", fixture.venue().id().value())
                .param("date", "2026-11-01").header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/v1/venues/{venueId}/waitlist-entries", fixture.venue().id().value())
                .param("date", "2026-10-31").header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void scopedForeignKeysRejectCrossVenueEntryAndReceiptReferences() throws Exception {
        Fixture first = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        Fixture second = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String firstKey = UUID.randomUUID().toString();
        String secondKey = UUID.randomUUID().toString();
        String firstEntry = JsonPath.read(register(
            first.venue(), first.firstSlot(), 2, customerA, firstKey
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String secondEntry = JsonPath.read(register(
            second.venue(), second.firstSlot(), 2, customerA, secondKey
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        assertThatThrownBy(() -> jdbc.update("""
            UPDATE waitlist_registration_requests SET entry_id = ?
             WHERE venue_id = ? AND idempotency_key = ?
            """, bytes(UUID.fromString(secondEntry)), bytes(first.venue().id().value()),
            bytes(UUID.fromString(firstKey))))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("""
            SELECT HEX(entry_id) FROM waitlist_registration_requests
             WHERE venue_id = ? AND idempotency_key = ?
            """, String.class, bytes(first.venue().id().value()), bytes(UUID.fromString(firstKey))))
            .isEqualTo(firstEntry.replace("-", "").toUpperCase());
    }

    @Test
    void createsRealPromotionalHoldAndPreservesAcceptEvidenceAfterOrdinaryCancel() throws Exception {
        Fixture fixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        access.assignMembership(OWNER, fixture.tenant().id(), TenantRole.OWNER);
        access.assignMembership(STAFF, fixture.tenant().id(), TenantRole.STAFF);
        access.grantVenue(STAFF, fixture.tenant().id(), TenantRole.STAFF, fixture.venue().id());
        String entryId = JsonPath.read(register(
            fixture.venue(), fixture.firstSlot(), 2, customerA, UUID.randomUUID().toString()
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        WaitlistOfferUseCase.TargetResult created = waitlistOffers.createTarget(
            SystemPrincipal.INSTANCE, fixture.venue().id(), new WaitlistEntryId(UUID.fromString(entryId)),
            fixture.firstSlot().id()
        );
        assertThat(created.outcome()).isEqualTo(WaitlistOfferUseCase.TargetOutcome.CREATED);
        assertThat(created.offer().state().name()).isEqualTo("PENDING");
        assertThat(created.offer().reservation().state().name()).isEqualTo("HELD");
        String offerId = created.offer().id().toString();
        String offerPath = "/api/v1/venues/" + fixture.venue().id().value()
            + "/waitlist-offers/" + offerId;

        mockMvc.perform(get(offerPath).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.state").value("PENDING"))
            .andExpect(jsonPath("$.allowedActions").value(org.hamcrest.Matchers.contains("ACCEPT", "REJECT")));
        mockMvc.perform(post(offerPath + "/accept").header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACCEPTED"))
            .andExpect(jsonPath("$.reservation.state").value("CONFIRMED"));
        mockMvc.perform(post(offerPath + "/accept").header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACCEPTED"));

        UUID reservationId = created.offer().reservation().id().value();
        mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/{reservationId}/cancel",
                fixture.venue().id().value(), reservationId)
                .header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
        mockMvc.perform(get(offerPath).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACCEPTED"))
            .andExpect(jsonPath("$.reservation.state").value("CANCELLED"));
        mockMvc.perform(get("/api/v1/venues/{venueId}/waitlist-entries/{entryId}",
                fixture.venue().id().value(), entryId).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("FULFILLED"))
            .andExpect(jsonPath("$.offerId").value(offerId));

        mockMvc.perform(get("/api/v1/management/venues/{venueId}/waitlist-offers/{offerId}",
                fixture.venue().id().value(), offerId).header("Authorization", bearer(owner)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.allowedActions").isEmpty());
        mockMvc.perform(get("/api/v1/management/venues/{venueId}/waitlist-entries",
                fixture.venue().id().value()).param("date", "2026-09-13")
                .header("Authorization", bearer(owner)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].offerState").value("ACCEPTED"))
            .andExpect(jsonPath("$.items[0].offerExpiresAt").isNotEmpty())
            .andExpect(jsonPath("$.items[0].reservationId").value(reservationId.toString()));
        mockMvc.perform(get("/api/v1/management/venues/{venueId}/waitlist-offers/{offerId}",
                fixture.venue().id().value(), offerId).header("Authorization", bearer(staff)))
            .andExpect(status().isForbidden());

        Fixture ordinary = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        WaitlistOfferUseCase.TargetResult ordinaryOffer = createTarget(
            ordinary, registerEntry(ordinary)
        );
        UUID ordinaryReservation = ordinaryOffer.offer().reservation().id().value();
        String ordinaryPath = offerPath(ordinary, ordinaryOffer.offer().id());
        mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/{reservationId}/confirm",
                ordinary.venue().id().value(), ordinaryReservation)
                .header("Authorization", bearer(customerA)))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/{reservationId}/cancel",
                ordinary.venue().id().value(), ordinaryReservation)
                .header("Authorization", bearer(customerA)))
            .andExpect(status().isOk());
        mockMvc.perform(get(ordinaryPath).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACCEPTED"))
            .andExpect(jsonPath("$.reservation.state").value("CANCELLED"));
        mockMvc.perform(post(ordinaryPath + "/reject").header("Authorization", bearer(customerA)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("OFFER_TRANSITION_NOT_ALLOWED"));
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?",
            String.class, bytes(ordinaryOffer.offer().id()))).isEqualTo("ACCEPTED");

        Fixture backingCancel = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        WaitlistOfferUseCase.TargetResult backingOffer = createTarget(
            backingCancel, registerEntry(backingCancel)
        );
        String backingPath = offerPath(backingCancel, backingOffer.offer().id());
        mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/{reservationId}/cancel",
                backingCancel.venue().id().value(), backingOffer.offer().reservation().id().value())
                .header("Authorization", bearer(customerA)))
            .andExpect(status().isOk());
        mockMvc.perform(get(backingPath).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DECLINED"))
            .andExpect(jsonPath("$.terminalReason").value("BACKING_CANCELLED"));
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?",
            String.class, bytes(backingOffer.offer().id()))).isEqualTo("PENDING");
        mockMvc.perform(post(backingPath + "/accept").header("Authorization", bearer(customerA)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("OFFER_TRANSITION_NOT_ALLOWED"));
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?",
            String.class, bytes(backingOffer.offer().id()))).isEqualTo("DECLINED");
    }

    @Test
    void rejectEntryCancelAndLateAcceptConvergeWithoutCapacityLeak() throws Exception {
        Fixture rejectFixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String rejectedEntry = registerEntry(rejectFixture);
        WaitlistOfferUseCase.TargetResult rejected = createTarget(rejectFixture, rejectedEntry);
        String rejectPath = offerPath(rejectFixture, rejected.offer().id());
        mockMvc.perform(post(rejectPath + "/reject").header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DECLINED"))
            .andExpect(jsonPath("$.terminalReason").value("CUSTOMER_DECLINED"))
            .andExpect(jsonPath("$.reservation.state").value("CANCELLED"));
        mockMvc.perform(post(rejectPath + "/reject").header("Authorization", bearer(customerA)))
            .andExpect(status().isOk());

        Fixture cancelFixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String cancelledEntry = registerEntry(cancelFixture);
        WaitlistOfferUseCase.TargetResult cancelled = createTarget(cancelFixture, cancelledEntry);
        mockMvc.perform(post("/api/v1/venues/{venueId}/waitlist-entries/{entryId}/cancel",
                cancelFixture.venue().id().value(), cancelledEntry)
                .header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DECLINED"));
        mockMvc.perform(get(offerPath(cancelFixture, cancelled.offer().id()))
                .header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.terminalReason").value("ENTRY_CANCELLED"));

        Fixture expiryFixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String expiredEntry = registerEntry(expiryFixture);
        WaitlistOfferUseCase.TargetResult expiring = createTarget(expiryFixture, expiredEntry);
        clock.set(expiring.offer().expiresAt());
        mockMvc.perform(post(offerPath(expiryFixture, expiring.offer().id()) + "/accept")
                .header("Authorization", bearer(customerA)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFER_EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?",
            String.class, bytes(expiring.offer().id()))).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT state FROM reservations WHERE id = ?",
            String.class, bytes(expiring.offer().reservation().id().value()))).isEqualTo("EXPIRED");
    }

    @Test
    void outerRepeatableReadUsesCurrentCapacityAndBusinessResultsKeepCallerTransactionUsable()
        throws Exception {
        Fixture fixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String entryId = registerEntry(fixture);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations", Integer.class))
                .isNotNull();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<MvcResult> competing = executor.submit(() -> mockMvc.perform(
                    post("/api/v1/venues/{venueId}/reservations/holds", fixture.venue().id().value())
                        .header("Authorization", bearer(customerB))
                        .contentType(MediaType.APPLICATION_JSON).content(body(fixture.firstSlot(), 2))
                ).andReturn());
                assertThat(competing.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            WaitlistOfferUseCase.TargetResult result = waitlistOffers.createTarget(
                SystemPrincipal.INSTANCE, fixture.venue().id(),
                new WaitlistEntryId(UUID.fromString(entryId)), fixture.firstSlot().id()
            );
            assertThat(result.outcome()).isEqualTo(
                WaitlistOfferUseCase.TargetOutcome.CAPACITY_UNAVAILABLE);
            jdbc.update("UPDATE waitlist_entries SET joined_at = joined_at WHERE id = ?",
                bytes(UUID.fromString(entryId)));
        });
        assertThat(count("waitlist_offers", fixture.venue().id().value())).isZero();

        Fixture expiry = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String expiryEntry = registerEntry(expiry);
        WaitlistOfferUseCase.TargetResult created = createTarget(expiry, expiryEntry);
        clock.set(created.offer().expiresAt());
        transaction.executeWithoutResult(status -> {
            WaitlistOfferUseCase.TargetResult result = waitlistOffers.reconcileTarget(
                SystemPrincipal.INSTANCE, expiry.venue().id(),
                new WaitlistOfferId(created.offer().id())
            );
            assertThat(result.outcome()).isEqualTo(WaitlistOfferUseCase.TargetOutcome.EXPIRED);
        });
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?",
            String.class, bytes(created.offer().id()))).isEqualTo("EXPIRED");

        Fixture rollback = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String rollbackEntry = registerEntry(rollback);
        WaitlistOfferUseCase.TargetResult rollbackOffer = createTarget(rollback, rollbackEntry);
        clock.set(rollbackOffer.offer().expiresAt());
        transaction.executeWithoutResult(status -> {
            WaitlistOfferUseCase.TargetResult result = waitlistOffers.reconcileTarget(
                SystemPrincipal.INSTANCE, rollback.venue().id(),
                new WaitlistOfferId(rollbackOffer.offer().id())
            );
            assertThat(result.outcome()).isEqualTo(WaitlistOfferUseCase.TargetOutcome.EXPIRED);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?",
            String.class, bytes(rollbackOffer.offer().id()))).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT state FROM reservations WHERE id = ?",
            String.class, bytes(rollbackOffer.offer().reservation().id().value()))).isEqualTo("HELD");

        Fixture staleEntity = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        WaitlistOfferUseCase.TargetResult staleOffer = createTarget(
            staleEntity, registerEntry(staleEntity)
        );
        transaction.executeWithoutResult(status -> {
            PromotionalReservationUseCase.ReservationView snapshot = promotionalReservations.get(
                staleEntity.venue().id(), staleOffer.offer().reservation().id(), clock.instant()
            );
            assertThat(snapshot.state().name()).isEqualTo("HELD");
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<Void> lifecycle = executor.submit(() -> {
                    UUID reservationId = staleOffer.offer().reservation().id().value();
                    mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/{reservationId}/confirm",
                            staleEntity.venue().id().value(), reservationId)
                            .header("Authorization", bearer(customerA)))
                        .andExpect(status().isOk());
                    mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/{reservationId}/cancel",
                            staleEntity.venue().id().value(), reservationId)
                            .header("Authorization", bearer(customerA)))
                        .andExpect(status().isOk());
                    return null;
                });
                lifecycle.get(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            WaitlistOfferUseCase.TargetResult reconciled = waitlistOffers.reconcileTarget(
                SystemPrincipal.INSTANCE, staleEntity.venue().id(),
                new WaitlistOfferId(staleOffer.offer().id())
            );
            assertThat(reconciled.outcome()).isEqualTo(WaitlistOfferUseCase.TargetOutcome.TERMINAL);
            assertThat(reconciled.offer().state().name()).isEqualTo("ACCEPTED");
            assertThat(reconciled.offer().reservation().state().name()).isEqualTo("CANCELLED");
        });
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?",
            String.class, bytes(staleOffer.offer().id()))).isEqualTo("ACCEPTED");
    }

    @Test
    void outerRepeatableReadRefusesCommittedInactiveResourceWithoutPoisoningCallerTransaction() {
        Fixture fixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String entryId;
        try {
            entryId = registerEntry(fixture);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertThat(resourceUseCase.getResource(
                fixture.tenant().id(), fixture.venue().id(), fixture.firstSlot().resourceId()
            ).status()).isEqualTo(ResourceStatus.ACTIVE);
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<ResourceStatus> update = executor.submit(() -> resourceUseCase.updateResource(
                    new ResourceUseCase.UpdateResource(
                        fixture.tenant().id(), fixture.venue().id(),
                        fixture.firstSlot().resourceId(), "First", 4, ResourceStatus.INACTIVE
                    )
                ).status());
                assertThat(update.get(10, TimeUnit.SECONDS)).isEqualTo(ResourceStatus.INACTIVE);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }

            WaitlistOfferUseCase.TargetResult result = waitlistOffers.createTarget(
                SystemPrincipal.INSTANCE, fixture.venue().id(),
                new WaitlistEntryId(UUID.fromString(entryId)), fixture.firstSlot().id()
            );
            assertThat(result.outcome()).isEqualTo(WaitlistOfferUseCase.TargetOutcome.NOT_ELIGIBLE);
            assertThat(status.isRollbackOnly()).isFalse();
            assertThat(jdbc.update(
                "UPDATE waitlist_entries SET joined_at = joined_at WHERE id = ?",
                bytes(UUID.fromString(entryId))
            )).isEqualTo(1);
        });

        assertThat(count("reservations", fixture.venue().id().value())).isZero();
        assertThat(count("capacity_allocations", fixture.venue().id().value())).isZero();
        assertThat(count("waitlist_offers", fixture.venue().id().value())).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT state FROM waitlist_entries WHERE id = ?",
            String.class, bytes(UUID.fromString(entryId))
        )).isEqualTo("WAITING");
    }

    @Test
    void outerRepeatableReadAppliesCommittedCurrentPolicyToPromotionalHold() {
        Fixture fixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String entryId;
        try {
            entryId = registerEntry(fixture);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertThat(venueUseCase.getVenue(
                fixture.tenant().id(), fixture.venue().id()
            ).currentPolicy().version()).isEqualTo(1L);
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<Long> update = executor.submit(() -> venueUseCase.updateBookingPolicy(
                    new VenueConfigurationUseCase.UpdateBookingPolicy(
                        fixture.tenant().id(), fixture.venue().id(),
                        new BookingPolicyTerms(30, 17, 20, 10)
                    )
                ).version());
                assertThat(update.get(10, TimeUnit.SECONDS)).isEqualTo(2L);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }

            WaitlistOfferUseCase.TargetResult result = waitlistOffers.createTarget(
                SystemPrincipal.INSTANCE, fixture.venue().id(),
                new WaitlistEntryId(UUID.fromString(entryId)), fixture.firstSlot().id()
            );
            assertThat(result.outcome()).isEqualTo(WaitlistOfferUseCase.TargetOutcome.CREATED);
            assertThat(result.offer().reservation().appliedPolicyVersion()).isEqualTo(2L);
            assertThat(result.offer().expiresAt()).isEqualTo(BASE_NOW.plusSeconds(17 * 60L));
            assertThat(status.isRollbackOnly()).isFalse();
        });
    }

    @Test
    void offerInsertFailureRollsBackReservationAllocationAndAllowsAnotherSlotRetry() throws Exception {
        Fixture fixture = fixture(4, 4, "2026-09-13T11:00:00Z", "UTC");
        String entryId = registerEntry(fixture);
        jdbc.execute("""
            CREATE TRIGGER fail_waitlist_offer_insert BEFORE INSERT ON waitlist_offers
            FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected offer failure'
            """);
        assertThatThrownBy(() -> waitlistOffers.createTarget(
            SystemPrincipal.INSTANCE, fixture.venue().id(),
            new WaitlistEntryId(UUID.fromString(entryId)), fixture.firstSlot().id()
        )).isInstanceOf(RuntimeException.class);
        assertThat(count("waitlist_offers", fixture.venue().id().value())).isZero();
        assertThat(count("reservations", fixture.venue().id().value())).isZero();
        assertThat(count("capacity_allocations", fixture.venue().id().value())).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_entries WHERE id = ?",
            String.class, bytes(UUID.fromString(entryId)))).isEqualTo("WAITING");

        jdbc.execute("DROP TRIGGER fail_waitlist_offer_insert");
        WaitlistOfferUseCase.TargetResult retry = waitlistOffers.createTarget(
            SystemPrincipal.INSTANCE, fixture.venue().id(),
            new WaitlistEntryId(UUID.fromString(entryId)), fixture.secondSlot().id()
        );
        assertThat(retry.outcome()).isEqualTo(WaitlistOfferUseCase.TargetOutcome.CREATED);
        assertThat(retry.offer().slotInventoryId()).isEqualTo(fixture.secondSlot().id().value());
    }

    @Test
    void offerUpdateFailureRollsBackAcceptReservationEvidenceAndEntryTogether() throws Exception {
        Fixture fixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String entryId = registerEntry(fixture);
        WaitlistOfferUseCase.TargetResult created = createTarget(fixture, entryId);
        jdbc.execute("""
            CREATE TRIGGER fail_waitlist_offer_update BEFORE UPDATE ON waitlist_offers
            FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'secret injected offer failure'
            """);
        MvcResult result = mockMvc.perform(post(offerPath(fixture, created.offer().id()) + "/accept")
                .header("Authorization", bearer(customerA)))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret injected");
        assertThat(jdbc.queryForObject("SELECT state FROM reservations WHERE id = ?",
            String.class, bytes(created.offer().reservation().id().value()))).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT promotional_confirmed FROM reservations WHERE id = ?",
            Boolean.class, bytes(created.offer().reservation().id().value()))).isFalse();
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?",
            String.class, bytes(created.offer().id()))).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_entries WHERE id = ?",
            String.class, bytes(UUID.fromString(entryId)))).isEqualTo("OFFERED");
    }

    @Test
    void sameEntryDifferentSlotTargetsCreateOneOfferAndOrdinaryHoldSharesTheCapacityOracle()
        throws Exception {
        Fixture fixture = fixture(4, 4, "2026-09-13T11:00:00Z", "UTC");
        String entryId = registerEntry(fixture);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<WaitlistOfferUseCase.TargetResult>> futures = List.of(
                fixture.firstSlot(), fixture.secondSlot()
            ).stream().map(slot -> executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return waitlistOffers.createTarget(
                    SystemPrincipal.INSTANCE, fixture.venue().id(),
                    new WaitlistEntryId(UUID.fromString(entryId)), slot.id()
                );
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<WaitlistOfferUseCase.TargetResult> results = futures.stream().map(future -> {
                try { return future.get(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }).toList();
            assertThat(results).extracting(WaitlistOfferUseCase.TargetResult::outcome)
                .containsExactlyInAnyOrder(
                    WaitlistOfferUseCase.TargetOutcome.CREATED,
                    WaitlistOfferUseCase.TargetOutcome.EXISTING
                );
            assertThat(results).extracting(result -> result.offer().id()).containsOnly(results.get(0).offer().id());
        }
        assertThat(count("waitlist_offers", fixture.venue().id().value())).isEqualTo(1);
        assertThat(count("reservations", fixture.venue().id().value())).isEqualTo(1);

        WaitlistOfferUseCase.OfferView offer = waitlistOffers.createTarget(
            SystemPrincipal.INSTANCE, fixture.venue().id(),
            new WaitlistEntryId(UUID.fromString(entryId)), fixture.firstSlot().id()
        ).offer();
        SlotInventory occupiedSlot = offer.slotInventoryId().equals(fixture.firstSlot().id().value())
            ? fixture.firstSlot() : fixture.secondSlot();
        mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/holds",
                fixture.venue().id().value()).header("Authorization", bearer(customerB))
                .contentType(MediaType.APPLICATION_JSON).content(body(occupiedSlot, 2)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CAPACITY_UNAVAILABLE"));
    }

    @Test
    void offerActionsRejectExtraHeadersBodiesAndWrongActorsWithoutMutation() throws Exception {
        Fixture fixture = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        access.assignMembership(OWNER, fixture.tenant().id(), TenantRole.OWNER);
        String entryId = registerEntry(fixture);
        WaitlistOfferUseCase.TargetResult created = createTarget(fixture, entryId);
        String path = offerPath(fixture, created.offer().id());

        mockMvc.perform(post(path + "/accept").header("Authorization", bearer(customerA))
                .header("Idempotency-Key", UUID.randomUUID()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(path + "/reject").header("Authorization", bearer(customerA))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(path + "/accept").header("Authorization", bearer(customerB)))
            .andExpect(status().isNotFound());
        mockMvc.perform(post(path + "/accept").header("Authorization", bearer(owner)))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_offers WHERE id = ?",
            String.class, bytes(created.offer().id()))).isEqualTo("PENDING");
    }

    @Test
    void acceptAndRejectCommitAccordingToBothMysqlSerializationOrders() throws Exception {
        Fixture acceptFirst = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        WaitlistOfferUseCase.TargetResult firstOffer = createTarget(
            acceptFirst, registerEntry(acceptFirst)
        );
        String firstPath = offerPath(acceptFirst, firstOffer.offer().id());
        installCommandGate(
            "gate_offer_accept", "reservations", "OLD.state = 'HELD' AND NEW.state = 'CONFIRMED'"
        );
        try (Connection gate = dataSource.getConnection();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            lockCommandGate(gate);
            long waits = rowLockWaits();
            Future<MvcResult> accept = executor.submit(() -> mockMvc.perform(post(firstPath + "/accept")
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            waits = rowLockWaits();
            Future<MvcResult> reject = executor.submit(() -> mockMvc.perform(post(firstPath + "/reject")
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            gate.commit();
            assertThat(accept.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(reject.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        dropOfferGates();
        mockMvc.perform(get(firstPath).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACCEPTED"));

        Fixture rejectFirst = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        WaitlistOfferUseCase.TargetResult secondOffer = createTarget(
            rejectFirst, registerEntry(rejectFirst)
        );
        String secondPath = offerPath(rejectFirst, secondOffer.offer().id());
        installCommandGate(
            "gate_offer_reject", "reservations", "OLD.state = 'HELD' AND NEW.state = 'CANCELLED'"
        );
        try (Connection gate = dataSource.getConnection();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            lockCommandGate(gate);
            long waits = rowLockWaits();
            Future<MvcResult> reject = executor.submit(() -> mockMvc.perform(post(secondPath + "/reject")
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            waits = rowLockWaits();
            Future<MvcResult> accept = executor.submit(() -> mockMvc.perform(post(secondPath + "/accept")
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            gate.commit();
            assertThat(reject.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(accept.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        dropOfferGates();
        mockMvc.perform(get(secondPath).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DECLINED"));
    }

    @Test
    void waitingCancelAndTargetCreationCommitAccordingToBothMysqlSerializationOrders()
        throws Exception {
        Fixture cancelFirst = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String firstEntry = registerEntry(cancelFirst);
        String firstCancel = "/api/v1/venues/" + cancelFirst.venue().id().value()
            + "/waitlist-entries/" + firstEntry + "/cancel";
        installCommandGate("gate_waitlist_cancel", "waitlist_entries", "OLD.state <> NEW.state");
        try (Connection gate = dataSource.getConnection();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            lockCommandGate(gate);
            long waits = rowLockWaits();
            Future<MvcResult> cancel = executor.submit(() -> mockMvc.perform(post(firstCancel)
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            waits = rowLockWaits();
            Future<WaitlistOfferUseCase.TargetResult> target = executor.submit(() ->
                waitlistOffers.createTarget(
                    SystemPrincipal.INSTANCE, cancelFirst.venue().id(),
                    new WaitlistEntryId(UUID.fromString(firstEntry)), cancelFirst.firstSlot().id()
                ));
            awaitLockWaitAfter(waits);
            gate.commit();
            assertThat(cancel.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(target.get(10, TimeUnit.SECONDS).outcome())
                .isEqualTo(WaitlistOfferUseCase.TargetOutcome.NOT_WAITING);
        }
        dropCommandGate();
        assertThat(count("waitlist_offers", cancelFirst.venue().id().value())).isZero();
        assertThat(count("reservations", cancelFirst.venue().id().value())).isZero();

        Fixture targetFirst = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        String secondEntry = registerEntry(targetFirst);
        String secondCancel = "/api/v1/venues/" + targetFirst.venue().id().value()
            + "/waitlist-entries/" + secondEntry + "/cancel";
        installOfferInsertGate();
        try (Connection gate = dataSource.getConnection();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            lockCommandGate(gate);
            long waits = rowLockWaits();
            Future<WaitlistOfferUseCase.TargetResult> target = executor.submit(() ->
                waitlistOffers.createTarget(
                    SystemPrincipal.INSTANCE, targetFirst.venue().id(),
                    new WaitlistEntryId(UUID.fromString(secondEntry)), targetFirst.firstSlot().id()
                ));
            awaitLockWaitAfter(waits);
            waits = rowLockWaits();
            Future<MvcResult> cancel = executor.submit(() -> mockMvc.perform(post(secondCancel)
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            gate.commit();
            assertThat(target.get(10, TimeUnit.SECONDS).outcome())
                .isEqualTo(WaitlistOfferUseCase.TargetOutcome.CREATED);
            assertThat(cancel.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        }
        dropOfferTargetGate();
        assertThat(jdbc.queryForObject("SELECT state FROM waitlist_entries WHERE id = ?",
            String.class, bytes(UUID.fromString(secondEntry)))).isEqualTo("DECLINED");
        assertThat(jdbc.queryForObject("SELECT state FROM reservations WHERE venue_id = ?",
            String.class, bytes(targetFirst.venue().id().value()))).isEqualTo("CANCELLED");
    }

    @Test
    void acceptAndExpiryCommitAccordingToBothMysqlSerializationOrders() throws Exception {
        Fixture expiryFirst = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        WaitlistOfferUseCase.TargetResult firstOffer = createTarget(
            expiryFirst, registerEntry(expiryFirst)
        );
        String firstPath = offerPath(expiryFirst, firstOffer.offer().id());
        try (Connection slotGate = dataSource.getConnection();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            lockSlot(slotGate, expiryFirst.firstSlot().id().value());
            long waits = rowLockWaits();
            Future<MvcResult> accept = executor.submit(() -> mockMvc.perform(post(firstPath + "/accept")
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            clock.set(firstOffer.offer().expiresAt());
            WaitlistOfferUseCase.TargetResult expiry = waitlistOffers.reconcileTarget(
                SystemPrincipal.INSTANCE, expiryFirst.venue().id(),
                new WaitlistOfferId(firstOffer.offer().id())
            );
            assertThat(expiry.outcome()).isEqualTo(WaitlistOfferUseCase.TargetOutcome.EXPIRED);
            slotGate.commit();
            assertThat(accept.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        mockMvc.perform(get(firstPath).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("EXPIRED"));

        Fixture acceptFirst = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        WaitlistOfferUseCase.TargetResult secondOffer = createTarget(
            acceptFirst, registerEntry(acceptFirst)
        );
        String secondPath = offerPath(acceptFirst, secondOffer.offer().id());
        installCommandGate(
            "gate_offer_accept", "reservations", "OLD.state = 'HELD' AND NEW.state = 'CONFIRMED'"
        );
        try (Connection gate = dataSource.getConnection();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            lockCommandGate(gate);
            long waits = rowLockWaits();
            Future<MvcResult> accept = executor.submit(() -> mockMvc.perform(post(secondPath + "/accept")
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            clock.set(secondOffer.offer().expiresAt());
            waits = rowLockWaits();
            Future<WaitlistOfferUseCase.TargetResult> expiry = executor.submit(() ->
                waitlistOffers.reconcileTarget(
                    SystemPrincipal.INSTANCE, acceptFirst.venue().id(),
                    new WaitlistOfferId(secondOffer.offer().id())
                ));
            awaitLockWaitAfter(waits);
            gate.commit();
            assertThat(accept.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(expiry.get(10, TimeUnit.SECONDS).outcome())
                .isEqualTo(WaitlistOfferUseCase.TargetOutcome.TERMINAL);
        }
        dropOfferGates();
        mockMvc.perform(get(secondPath).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ACCEPTED"));
    }

    @Test
    void rejectAndExpiryCommitAccordingToBothMysqlSerializationOrders() throws Exception {
        Fixture rejectFirst = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        WaitlistOfferUseCase.TargetResult firstOffer = createTarget(
            rejectFirst, registerEntry(rejectFirst)
        );
        String firstPath = offerPath(rejectFirst, firstOffer.offer().id());
        installCommandGate(
            "gate_offer_reject", "reservations", "OLD.state = 'HELD' AND NEW.state = 'CANCELLED'"
        );
        try (Connection gate = dataSource.getConnection();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            lockCommandGate(gate);
            long waits = rowLockWaits();
            Future<MvcResult> reject = executor.submit(() -> mockMvc.perform(post(firstPath + "/reject")
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            clock.set(firstOffer.offer().expiresAt());
            waits = rowLockWaits();
            Future<WaitlistOfferUseCase.TargetResult> expiry = executor.submit(() ->
                waitlistOffers.reconcileTarget(
                    SystemPrincipal.INSTANCE, rejectFirst.venue().id(),
                    new WaitlistOfferId(firstOffer.offer().id())
                ));
            awaitLockWaitAfter(waits);
            gate.commit();
            assertThat(reject.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(expiry.get(10, TimeUnit.SECONDS).outcome())
                .isEqualTo(WaitlistOfferUseCase.TargetOutcome.TERMINAL);
        }
        dropOfferGates();
        mockMvc.perform(get(firstPath).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DECLINED"));

        Fixture expiryFirst = fixture(4, null, "2026-09-13T11:00:00Z", "UTC");
        WaitlistOfferUseCase.TargetResult secondOffer = createTarget(
            expiryFirst, registerEntry(expiryFirst)
        );
        String secondPath = offerPath(expiryFirst, secondOffer.offer().id());
        clock.set(secondOffer.offer().expiresAt());
        installCommandGate(
            "gate_offer_expiry", "reservations", "OLD.state = 'HELD' AND NEW.state = 'EXPIRED'"
        );
        try (Connection gate = dataSource.getConnection();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            lockCommandGate(gate);
            long waits = rowLockWaits();
            Future<WaitlistOfferUseCase.TargetResult> expiry = executor.submit(() ->
                waitlistOffers.reconcileTarget(
                    SystemPrincipal.INSTANCE, expiryFirst.venue().id(),
                    new WaitlistOfferId(secondOffer.offer().id())
                ));
            awaitLockWaitAfter(waits);
            waits = rowLockWaits();
            Future<MvcResult> reject = executor.submit(() -> mockMvc.perform(post(secondPath + "/reject")
                .header("Authorization", bearer(customerA))).andReturn());
            awaitLockWaitAfter(waits);
            gate.commit();
            assertThat(expiry.get(10, TimeUnit.SECONDS).outcome())
                .isEqualTo(WaitlistOfferUseCase.TargetOutcome.EXPIRED);
            assertThat(reject.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        dropOfferGates();
        mockMvc.perform(get(secondPath).header("Authorization", bearer(customerA)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("EXPIRED"));
    }

    private String registerEntry(Fixture fixture) throws Exception {
        return JsonPath.read(register(
            fixture.venue(), fixture.firstSlot(), 2, customerA, UUID.randomUUID().toString()
        ).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private WaitlistOfferUseCase.TargetResult createTarget(Fixture fixture, String entryId) {
        return waitlistOffers.createTarget(
            SystemPrincipal.INSTANCE, fixture.venue().id(),
            new WaitlistEntryId(UUID.fromString(entryId)), fixture.firstSlot().id()
        );
    }

    private String offerPath(Fixture fixture, UUID offerId) {
        return "/api/v1/venues/" + fixture.venue().id().value() + "/waitlist-offers/" + offerId;
    }

    private void installCompletionFailure() {
        jdbc.execute("""
            CREATE TRIGGER fail_waitlist_registration_completion
            BEFORE UPDATE ON waitlist_registration_requests FOR EACH ROW
            BEGIN
                IF OLD.state = 'IN_PROGRESS' AND NEW.state = 'COMPLETED' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected waitlist receipt failure';
                END IF;
            END
            """);
    }

    private void installCommandGate(String trigger, String table, String condition) {
        jdbc.execute("CREATE TABLE waitlist_command_gate (id INT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO waitlist_command_gate (id) VALUES (1)");
        jdbc.execute(("""
            CREATE TRIGGER %s BEFORE UPDATE ON %s FOR EACH ROW
            BEGIN
                DECLARE gate_id INT;
                IF %s THEN
                    SELECT id INTO gate_id FROM waitlist_command_gate WHERE id = 1 FOR UPDATE;
                END IF;
            END
            """).formatted(trigger, table, condition));
    }

    private void installOfferInsertGate() {
        jdbc.execute("CREATE TABLE waitlist_command_gate (id INT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO waitlist_command_gate (id) VALUES (1)");
        jdbc.execute("""
            CREATE TRIGGER gate_target_offer BEFORE INSERT ON waitlist_offers FOR EACH ROW
            BEGIN
                DECLARE gate_id INT;
                SELECT id INTO gate_id FROM waitlist_command_gate WHERE id = 1 FOR UPDATE;
            END
            """);
    }

    private void lockCommandGate(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM waitlist_command_gate WHERE id = 1 FOR UPDATE"
        ); var result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
        }
    }

    private void lockSlot(Connection connection, UUID slotId) throws Exception {
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM slot_inventories WHERE id = ? FOR UPDATE"
        )) {
            statement.setBytes(1, bytes(slotId));
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    private void dropCommandGate() {
        jdbc.execute("DROP TRIGGER IF EXISTS gate_waitlist_cancel");
        jdbc.execute("DROP TRIGGER IF EXISTS gate_waitlist_registration");
        jdbc.execute("DROP TABLE IF EXISTS waitlist_command_gate");
    }

    private void dropOfferGates() {
        jdbc.execute("DROP TRIGGER IF EXISTS gate_offer_accept");
        jdbc.execute("DROP TRIGGER IF EXISTS gate_offer_reject");
        jdbc.execute("DROP TRIGGER IF EXISTS gate_offer_expiry");
        jdbc.execute("DROP TABLE IF EXISTS waitlist_command_gate");
    }

    private void dropOfferTargetGate() {
        jdbc.execute("DROP TRIGGER IF EXISTS gate_target_offer");
        jdbc.execute("DROP TABLE IF EXISTS waitlist_command_gate");
    }

    private long rowLockWaits() {
        return Long.parseLong(jdbc.queryForMap(
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
        throw new AssertionError("Waitlist command did not reach the expected MySQL lock boundary");
    }

    private Fixture fixture(Integer firstCapacity, Integer secondCapacity, String startsAt, String timezone) {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "Waitlist Venue", timezone,
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,
                new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(13, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10)
        ));
        Resource firstResource = resourceUseCase.createResource(new ResourceUseCase.CreateResource(
            tenant.id(), venue.id(), "First", firstCapacity
        ));
        SlotInventory firstSlot = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), firstResource.id(), startsAt
        ));
        if (secondCapacity == null) {
            return new Fixture(tenant, venue, firstSlot, null);
        }
        Resource secondResource = resourceUseCase.createResource(new ResourceUseCase.CreateResource(
            tenant.id(), venue.id(), "Second", secondCapacity
        ));
        SlotInventory secondSlot = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), secondResource.id(), startsAt
        ));
        return new Fixture(tenant, venue, firstSlot, secondSlot);
    }

    private org.springframework.test.web.servlet.ResultActions register(
        Venue venue, SlotInventory slot, int partySize, String token, String key
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/venues/{venueId}/waitlist-entries", venue.id().value())
            .header("Authorization", bearer(token)).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body(slot, partySize)));
    }

    private String body(SlotInventory slot, int partySize) {
        return "{\"slotInventoryId\":\"" + slot.id().value() + "\",\"partySize\":" + partySize + "}";
    }

    private long count(String table, UUID venueId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE venue_id = ?", Long.class, bytes(venueId)
        );
    }

    private String bootstrap(String fixtureKey) throws Exception {
        String response = mockMvc.perform(post("/__dev/auth/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fixtureKey\":\"" + fixtureKey + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private static String responseBody(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static PrincipalId principal(String value) {
        return new PrincipalId(UUID.fromString(value));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits()).array();
    }

    record Fixture(Tenant tenant, Venue venue, SlotInventory firstSlot, SlotInventory secondSlot) { }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(BASE_NOW); }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant initial) { instant = new AtomicReference<>(initial); }
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
