package com.slotq;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;

import com.jayway.jsonpath.JsonPath;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.application.VenueRepository;
import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.WeeklyOperatingHours;
import org.hibernate.Session;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "slotq.auth.dev-bootstrap-enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(ManagementApiIntegrationTests.ClockConfiguration.class)
class ManagementApiIntegrationTests {

    private static final Instant BASE_NOW = Instant.parse("2026-08-30T09:00:00Z");
    private static final PrincipalId OPERATOR = principal("10000000-0000-0000-0000-000000000003");
    private static final PrincipalId STAFF = principal("10000000-0000-0000-0000-000000000005");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1")
        .withDatabaseName("slotq");

    @Autowired MockMvc mockMvc;
    @Autowired TenantUseCase tenantUseCase;
    @Autowired VenueConfigurationUseCase venueUseCase;
    @Autowired ResourceUseCase resourceUseCase;
    @Autowired SlotInventoryUseCase slotUseCase;
    @Autowired AccessControlProvisioning accessControlProvisioning;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;
    @Autowired EntityManager entityManager;
    @Autowired PolicySqlCapture policySql;
    @MockitoSpyBean VenueRepository venueRepository;

    private String customerToken;
    private String operatorToken;
    private String staffToken;

    @BeforeEach
    void reset() throws Exception {
        clock.set(BASE_NOW);
        deleteProductData();
        customerToken = bootstrap("customer-a");
        operatorToken = bootstrap("tenant-a-owner");
        staffToken = bootstrap("tenant-a-staff");
    }

    @Test
    void discoversMultiTenantMultiRoleVenueScopeWithoutClientScopeClaims() throws Exception {
        Tenant ownerTenant = tenantUseCase.createTenant();
        Venue ownerA = venue(ownerTenant, "Owner A", "UTC", DayOfWeek.SUNDAY, "09:00", "13:00");
        Venue ownerB = venue(ownerTenant, "Owner B", "UTC", DayOfWeek.SUNDAY, "09:00", "13:00");
        Tenant managerTenant = tenantUseCase.createTenant();
        Venue managerGranted = venue(managerTenant, "Manager granted", "UTC",
            DayOfWeek.SUNDAY, "09:00", "13:00");
        venue(managerTenant, "Manager hidden", "UTC", DayOfWeek.SUNDAY, "09:00", "13:00");
        Tenant staffTenant = tenantUseCase.createTenant();
        venue(staffTenant, "Staff hidden", "UTC", DayOfWeek.SUNDAY, "09:00", "13:00");
        Venue staffGranted = venue(staffTenant, "Staff granted", "UTC",
            DayOfWeek.SUNDAY, "09:00", "13:00");

        accessControlProvisioning.assignMembership(OPERATOR, ownerTenant.id(), TenantRole.OWNER);
        accessControlProvisioning.assignMembership(OPERATOR, managerTenant.id(), TenantRole.MANAGER);
        accessControlProvisioning.grantVenue(
            OPERATOR, managerTenant.id(), TenantRole.MANAGER, managerGranted.id()
        );
        accessControlProvisioning.assignMembership(OPERATOR, staffTenant.id(), TenantRole.STAFF);
        accessControlProvisioning.grantVenue(
            OPERATOR, staffTenant.id(), TenantRole.STAFF, staffGranted.id()
        );

        String body = mockMvc.perform(get("/api/v1/management/venues")
                .param("tenantId", UUID.randomUUID().toString())
                .param("role", "OWNER")
                .header("Authorization", bearer(operatorToken)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();

        List<Venue> expected = new ArrayList<>(List.of(ownerA, ownerB, managerGranted, staffGranted));
        expected.sort(Comparator.comparing((Venue value) -> value.tenantId().value())
            .thenComparing(value -> value.id().value()));
        List<String> actualIds = JsonPath.read(body, "$[*].id");
        assertThat(actualIds).containsExactlyElementsOf(
            expected.stream().map(value -> value.id().value().toString()).toList()
        );
        List<Map<String, Object>> actualVenues = JsonPath.read(body, "$[*]");
        Map<String, Boolean> writableByVenue = actualVenues.stream().collect(
            java.util.stream.Collectors.toMap(
                item -> (String) item.get("id"),
                item -> (Boolean) item.get("configurationWritable")
            )
        );
        assertThat(writableByVenue).containsEntry(ownerA.id().value().toString(), true)
            .containsEntry(ownerB.id().value().toString(), true)
            .containsEntry(managerGranted.id().value().toString(), true)
            .containsEntry(staffGranted.id().value().toString(), false);

        mockMvc.perform(get("/api/v1/management/venues/{venueId}", ownerA.id().value())
                .header("Authorization", bearer(operatorToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configurationWritable").value(true));
        mockMvc.perform(get("/api/v1/management/venues/{venueId}", managerGranted.id().value())
                .header("Authorization", bearer(operatorToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configurationWritable").value(true));
        mockMvc.perform(get("/api/v1/management/venues/{venueId}", staffGranted.id().value())
                .header("Authorization", bearer(operatorToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configurationWritable").value(false));
    }

    @Test
    void hidesCrossScopeDeniesStaffWriteAndPreservesVenueConfigurationOnPatch() throws Exception {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant, "Original", "Asia/Seoul", DayOfWeek.SUNDAY, "09:00", "13:00");
        Tenant otherTenant = tenantUseCase.createTenant();
        Venue hidden = venue(otherTenant, "Hidden", "UTC", DayOfWeek.SUNDAY, "09:00", "13:00");
        accessControlProvisioning.assignMembership(OPERATOR, tenant.id(), TenantRole.OWNER);
        accessControlProvisioning.assignMembership(STAFF, tenant.id(), TenantRole.STAFF);
        accessControlProvisioning.grantVenue(STAFF, tenant.id(), TenantRole.STAFF, venue.id());

        mockMvc.perform(get("/api/v1/management/venues/{venueId}", hidden.id().value())
                .header("Authorization", bearer(operatorToken)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(patch("/api/v1/management/venues/{venueId}", venue.id().value())
                .header("Authorization", bearer(staffToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"forbidden\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/management/venues/{venueId}", venue.id().value())
                .header("Authorization", bearer(staffToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configurationWritable").value(false));

        mockMvc.perform(patch("/api/v1/management/venues/{venueId}", venue.id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\",\"status\":\"INACTIVE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Renamed"))
            .andExpect(jsonPath("$.status").value("INACTIVE"))
            .andExpect(jsonPath("$.configurationWritable").value(true));

        Venue updated = venueUseCase.getVenue(tenant.id(), venue.id());
        assertThat(updated.timezone()).isEqualTo(venue.timezone());
        assertThat(updated.operatingHours()).isEqualTo(venue.operatingHours());
        assertThat(updated.currentPolicy()).isEqualTo(venue.currentPolicy());

        mockMvc.perform(patch("/api/v1/management/venues/{venueId}", venue.id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/management/venues/{venueId}/reservations", venue.id().value())
                .header("Authorization", bearer(operatorToken)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch("/api/v1/management/venues/{venueId}", venue.id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":null}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch("/api/v1/management/venues/{venueId}", venue.id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"timezone\":\"UTC\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void policyAndResourceChangesPreserveExistingReservationAndTableCapacity() throws Exception {
        Fixture fixture = fixture("Preservation", "UTC", "2026-08-30T11:00:00Z");
        accessControlProvisioning.assignMembership(OPERATOR, fixture.tenant().id(), TenantRole.OWNER);
        UUID reservationId = createHold(fixture);
        Map<String, Object> reservationBefore = reservationSnapshot(reservationId);

        mockMvc.perform(put("/api/v1/management/venues/{venueId}/policy", fixture.venue().id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"slotDurationMinutes":60,"holdDurationMinutes":10,
                     "cancellationCutoffMinutes":30,"noShowGraceMinutes":15}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(2));
        assertThat(reservationSnapshot(reservationId)).isEqualTo(reservationBefore);

        mockMvc.perform(patch(
                "/api/v1/management/venues/{venueId}/resources/{resourceId}",
                fixture.venue().id().value(), fixture.resource().id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INACTIVE"));
        assertThat(reservationSnapshot(reservationId)).isEqualTo(reservationBefore);
        assertThat(allocationActive(reservationId)).isTrue();

        String resourceBody = mockMvc.perform(post(
                "/api/v1/management/venues/{venueId}/resources", fixture.venue().id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Second table\",\"seatingCapacity\":6}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.type").value("TABLE"))
            .andReturn().getResponse().getContentAsString();
        Resource second = resourceUseCase.getResource(
            fixture.tenant().id(), fixture.venue().id(),
            new com.slotq.venue.domain.ResourceId(UUID.fromString(JsonPath.read(resourceBody, "$.id")))
        );
        String slotBody = mockMvc.perform(post(
                "/api/v1/management/venues/{venueId}/slot-inventories", fixture.venue().id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceId\":\"" + second.id().value()
                    + "\",\"startsAt\":\"2026-08-30T12:00:00Z\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.capacity").value(1))
            .andReturn().getResponse().getContentAsString();
        UUID slotId = UUID.fromString(JsonPath.read(slotBody, "$.id"));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT capacity FROM slot_inventories WHERE id = ?", Integer.class, bytes(slotId)
        )).isEqualTo(1);
        mockMvc.perform(post(
                "/api/v1/management/venues/{venueId}/slot-inventories", fixture.venue().id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceId\":\"" + second.id().value()
                    + "\",\"startsAt\":\"2026-08-30T12:00:00Z\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SLOT_INVENTORY_CONFLICT"));
        mockMvc.perform(post(
                "/api/v1/management/venues/{venueId}/slot-inventories", fixture.venue().id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceId\":\"" + fixture.resource().id().value()
                    + "\",\"startsAt\":\"2026-08-30T12:00:00Z\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SLOT_INVENTORY_NOT_ALLOWED"));
        mockMvc.perform(post(
                "/api/v1/management/venues/{venueId}/slot-inventories", fixture.venue().id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceId\":\"" + second.id().value()
                    + "\",\"startsAt\":\"not-a-time\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(
                "/api/v1/management/venues/{venueId}/slot-inventories", fixture.venue().id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resourceId\":\"" + second.id().value()
                    + "\",\"startsAt\":\"2026-08-30T12:30:00Z\",\"capacity\":2}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentManagementPolicyPutsUseLatestCommittedVersionAfterAuthorization(boolean cacheOldPolicy)
        throws Exception {
        Fixture fixture = fixture("Policy race", "UTC", "2026-08-30T11:00:00Z");
        accessControlProvisioning.assignMembership(OPERATOR, fixture.tenant().id(), TenantRole.OWNER);
        UUID reservationId = createHold(fixture);
        Map<String, Object> reservationBefore = reservationSnapshot(reservationId);
        Map<String, Object> slotBefore = slotSnapshot(fixture);
        List<Map<String, Object>> policiesBefore = policyRows(fixture.venue());
        BookingPolicyTerms firstTerms = new BookingPolicyTerms(60, 10, 30, 15);
        BookingPolicyTerms secondTerms = new BookingPolicyTerms(45, 7, 25, 12);
        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        AtomicReference<Thread> firstRequest = new AtomicReference<>();
        AtomicReference<BookingPolicy> selectedPolicy = new AtomicReference<>();
        AtomicReference<Long> firstConnection = new AtomicReference<>();

        doAnswer(invocation -> {
            if (Thread.currentThread() != firstRequest.get()) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class))
                    .isNotEqualTo(firstConnection.get());
                return invocation.callRealMethod();
            }
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");
            long connection = jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
            firstConnection.set(connection);
            assertThat(entityManager.isJoinedToTransaction()).isTrue();
            assertThat(entityManager.unwrap(Session.class).getStatistics().getEntityCount())
                .as("authorization uses JDBC and has not loaded any JPA entities").isZero();
            Object cachedPolicy = cacheOldPolicy ? entityManager.createQuery(
                "select policy from BookingPolicyJpaEntity policy "
                    + "where policy.id.tenantId = :tenant and policy.id.venueId = :venue "
                    + "order by policy.id.policyVersion desc"
            ).setParameter("tenant", fixture.tenant().id().value())
                .setParameter("venue", fixture.venue().id().value()).setMaxResults(1).getSingleResult() : null;
            authorized.countDown();
            assertThat(committed.await(30, TimeUnit.SECONDS)).as("B committed before A locks Venue").isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(connection);
            // This is A's first policy read in the uncached case: authorization alone established the old snapshot.
            assertThat(jdbcTemplate.queryForObject(
                "SELECT MAX(policy_version) FROM booking_policies WHERE tenant_id = ? AND venue_id = ?",
                Long.class, bytes(fixture.tenant().id().value()), bytes(fixture.venue().id().value())
            )).as("A's consistent read still sees v1 after B committed v2").isEqualTo(1);
            if (cachedPolicy != null) {
                assertThat(entityManager.contains(cachedPolicy)).isTrue();
            }
            Optional<?> result = (Optional<?>) invocation.callRealMethod();
            Venue locked = (Venue) result.orElseThrow();
            selectedPolicy.set(locked.currentPolicy());
            if (cachedPolicy != null) {
                assertThat(entityManager.contains(cachedPolicy)).as("old entity remains managed").isTrue();
            }
            return result;
        }).when(venueRepository).findForUpdate(any(), any());

        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> {
                firstRequest.set(Thread.currentThread());
                policySql.start();
                try {
                    return new PolicyPut(policyPut(fixture.venue(), firstTerms), policySql.statements());
                } finally {
                    policySql.stop();
                }
            });
            assertThat(authorized.await(30, TimeUnit.SECONDS)).as("A reached Venue lock after authorization")
                .isTrue();
            MvcResult second = policyPut(fixture.venue(), secondTerms);
            assertPolicyResponse(second, 2, secondTerms);
            assertThat(policyRows(fixture.venue())).hasSize(2);
            committed.countDown();
            PolicyPut firstResult = first.get(30, TimeUnit.SECONDS);
            assertPolicyResponse(firstResult.result(), 3, firstTerms);
            assertThat(selectedPolicy.get().version()).as("current read after Venue lock").isEqualTo(2);
            assertThat(selectedPolicy.get().terms()).isEqualTo(secondTerms);

            List<String> lockingSql = firstResult.sql().stream().filter(sql -> sql.contains("for update")).toList();
            assertThat(lockingSql).hasSize(2);
            assertThat(lockingSql.get(0)).contains("from venues");
            assertThat(lockingSql.get(1)).contains("from booking_policies", "order by", "policy_version desc");
            assertThat(firstResult.sql()).anySatisfy(sql -> assertThat(sql).startsWith("insert into booking_policies"));
            assertThat(firstResult.sql()).noneSatisfy(sql -> assertThat(sql).startsWith("update booking_policies"));
            System.out.println("Policy race cacheOldPolicy=" + cacheOldPolicy + ": " + firstResult.sql());
        } finally {
            committed.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        List<Map<String, Object>> rows = policyRows(fixture.venue());
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst()).isEqualTo(policiesBefore.getFirst());
        assertPolicyRow(rows.get(1), 2, secondTerms);
        assertPolicyRow(rows.get(2), 3, firstTerms);
        assertThat(reservationSnapshot(reservationId)).isEqualTo(reservationBefore);
        assertThat(slotSnapshot(fixture)).isEqualTo(slotBefore);
        assertThat(venueUseCase.getVenue(fixture.tenant().id(), fixture.venue().id()).currentPolicy().version())
            .isEqualTo(3);
    }

    @Test
    void policyPutPreservesOwnerManagerAuthorityFullReplacementAndProblemResponses() throws Exception {
        Fixture fixture = fixture("Policy access", "UTC", "2026-08-30T11:00:00Z");
        Venue venue = fixture.venue();
        Venue unassigned = venue(fixture.tenant(), "Unassigned", "UTC", DayOfWeek.SUNDAY, "09:00", "13:00");
        Venue otherTenant = venue(tenantUseCase.createTenant(), "Other tenant", "UTC",
            DayOfWeek.SUNDAY, "09:00", "13:00");
        BookingPolicyTerms terms = new BookingPolicyTerms(60, 10, 30, 15);
        accessControlProvisioning.assignMembership(OPERATOR, fixture.tenant().id(), TenantRole.OWNER);
        assertPolicyResponse(policyPut(venue, terms), 2, terms);
        accessControlProvisioning.assignMembership(OPERATOR, fixture.tenant().id(), TenantRole.MANAGER);
        accessControlProvisioning.grantVenue(OPERATOR, fixture.tenant().id(), TenantRole.MANAGER, venue.id());
        BookingPolicyTerms replacement = new BookingPolicyTerms(45, 7, 0, 0);
        assertPolicyResponse(policyPut(venue, replacement), 3, replacement);
        assertPolicyRow(policyRows(venue).get(2), 3, replacement);

        accessControlProvisioning.assignMembership(STAFF, fixture.tenant().id(), TenantRole.STAFF);
        accessControlProvisioning.grantVenue(STAFF, fixture.tenant().id(), TenantRole.STAFF, venue.id());
        assertPolicyProblem(policyPut(venue, terms, staffToken), 403, "ACCESS_DENIED");
        assertPolicyProblem(policyPut(unassigned, terms), 404, "RESOURCE_NOT_FOUND");
        assertPolicyProblem(policyPut(otherTenant, terms), 404, "RESOURCE_NOT_FOUND");
        for (String invalid : List.of(
            "{\"slotDurationMinutes\":60,\"holdDurationMinutes\":10,\"cancellationCutoffMinutes\":0}",
            "{\"slotDurationMinutes\":0,\"holdDurationMinutes\":10,\"cancellationCutoffMinutes\":0,\"noShowGraceMinutes\":0}",
            "{\"slotDurationMinutes\":60,\"holdDurationMinutes\":0,\"cancellationCutoffMinutes\":0,\"noShowGraceMinutes\":0}",
            "{\"slotDurationMinutes\":60,\"holdDurationMinutes\":10,\"cancellationCutoffMinutes\":-1,\"noShowGraceMinutes\":0}",
            "{\"slotDurationMinutes\":60,\"holdDurationMinutes\":10,\"cancellationCutoffMinutes\":0,\"noShowGraceMinutes\":-1}"
        )) {
            assertPolicyProblem(mockMvc.perform(put("/api/v1/management/venues/{venueId}/policy", venue.id().value())
                    .header("Authorization", bearer(operatorToken))
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andReturn(),
                400, "VALIDATION_FAILED");
        }
        assertThat(policyRows(venue)).hasSize(3);
        assertThat(policyRows(unassigned)).hasSize(1);
        assertThat(policyRows(otherTenant)).hasSize(1);

        policySql.start();
        try {
            assertThat(venueUseCase.getVenue(fixture.tenant().id(), venue.id()).currentPolicy().version()).isEqualTo(3);
            assertThat(policySql.statements()).noneMatch(sql -> sql.contains("for update") || sql.contains("for share"));
        } finally {
            policySql.stop();
        }
    }

    @Test
    void usesVenueLocalDstDateWindowAndStableCollectionOrdering() throws Exception {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(
            tenant, "DST", "America/New_York", DayOfWeek.SUNDAY, "00:00", "05:00"
        );
        accessControlProvisioning.assignMembership(OPERATOR, tenant.id(), TenantRole.OWNER);
        Resource first = resourceUseCase.createResource(new ResourceUseCase.CreateResource(
            tenant.id(), venue.id(), "A table", 2
        ));
        Resource second = resourceUseCase.createResource(new ResourceUseCase.CreateResource(
            tenant.id(), venue.id(), "B table", 2
        ));
        SlotInventory earlier = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), first.id(), "2026-11-01T01:30:00-04:00"
        ));
        SlotInventory later = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), second.id(), "2026-11-01T01:30:00-05:00"
        ));
        UUID earlierReservation = createHold(venue, earlier);
        UUID laterReservation = createHold(venue, later);

        String body = mockMvc.perform(get(
                "/api/v1/management/venues/{venueId}/slot-inventories", venue.id().value())
                .param("date", "2026-11-01")
                .header("Authorization", bearer(operatorToken)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$[*].id")).containsExactly(
            earlier.id().value().toString(), later.id().value().toString()
        );
        assertThat(later.startsAt()).isEqualTo(earlier.startsAt().plus(Duration.ofHours(1)));
        String reservations = mockMvc.perform(get(
                "/api/v1/management/venues/{venueId}/reservations", venue.id().value())
                .param("date", "2026-11-01")
                .header("Authorization", bearer(operatorToken)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(reservations, "$[*].id")).containsExactly(
            earlierReservation.toString(), laterReservation.toString()
        );
    }

    @Test
    void reportsDueHeldAsExpiredWithoutChangingReservationOrAllocationRows() throws Exception {
        Fixture fixture = fixture("Due hold", "UTC", "2026-08-30T11:00:00Z");
        accessControlProvisioning.assignMembership(OPERATOR, fixture.tenant().id(), TenantRole.OWNER);
        UUID reservationId = createHold(fixture);
        Map<String, Object> before = reservationSnapshot(reservationId);
        boolean allocationBefore = allocationActive(reservationId);
        clock.set(BASE_NOW.plus(Duration.ofMinutes(5)));

        mockMvc.perform(get("/api/v1/management/venues/{venueId}/reservations",
                fixture.venue().id().value())
                .param("date", "2026-08-30")
                .param("status", "HELD")
                .header("Authorization", bearer(operatorToken)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/v1/management/venues/{venueId}/reservations",
                fixture.venue().id().value())
                .param("date", "2026-08-30")
                .param("status", "EXPIRED")
                .header("Authorization", bearer(operatorToken)))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$[0].id").value(reservationId.toString()))
            .andExpect(jsonPath("$[0].state").value("EXPIRED"))
            .andExpect(jsonPath("$[0].allowedActions").isEmpty());

        assertThat(reservationSnapshot(reservationId)).isEqualTo(before);
        assertThat(allocationActive(reservationId)).isEqualTo(allocationBefore);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT state FROM reservations WHERE id = ?", String.class, bytes(reservationId)
        )).isEqualTo("HELD");
    }

    @Test
    void everyManagementGetUsesNoStoreAndOperatorActionsReuseCommandEligibility() throws Exception {
        Fixture fixture = fixture("No store", "UTC", "2026-08-30T11:00:00Z");
        accessControlProvisioning.assignMembership(OPERATOR, fixture.tenant().id(), TenantRole.OWNER);
        accessControlProvisioning.assignMembership(STAFF, fixture.tenant().id(), TenantRole.STAFF);
        accessControlProvisioning.grantVenue(
            STAFF, fixture.tenant().id(), TenantRole.STAFF, fixture.venue().id()
        );
        UUID reservationId = createHold(fixture);

        for (String path : List.of(
            "/api/v1/management/venues",
            "/api/v1/management/venues/" + fixture.venue().id().value(),
            "/api/v1/management/venues/" + fixture.venue().id().value() + "/policy",
            "/api/v1/management/venues/" + fixture.venue().id().value() + "/resources",
            "/api/v1/management/venues/" + fixture.venue().id().value()
                + "/slot-inventories?date=2026-08-30",
            "/api/v1/management/venues/" + fixture.venue().id().value()
                + "/reservations?date=2026-08-30"
        )) {
            mockMvc.perform(get(path).header("Authorization", bearer(operatorToken)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        }

        mockMvc.perform(get("/api/v1/management/venues/{venueId}/reservations",
                fixture.venue().id().value())
                .param("date", "2026-08-30")
                .header("Authorization", bearer(operatorToken)))
            .andExpect(jsonPath("$[0].id").value(reservationId.toString()))
            .andExpect(jsonPath("$[0].allowedActions[0]").value("cancel"));
        mockMvc.perform(get("/api/v1/management/venues/{venueId}/reservations",
                fixture.venue().id().value())
                .param("date", "2026-08-30")
                .header("Authorization", bearer(staffToken)))
            .andExpect(jsonPath("$[0].allowedActions").isEmpty());
    }

    private MvcResult policyPut(Venue venue, BookingPolicyTerms terms) throws Exception {
        return policyPut(venue, terms, operatorToken);
    }

    private MvcResult policyPut(Venue venue, BookingPolicyTerms terms, String token) throws Exception {
        return mockMvc.perform(put("/api/v1/management/venues/{venueId}/policy", venue.id().value())
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"slotDurationMinutes":%d,"holdDurationMinutes":%d,
                     "cancellationCutoffMinutes":%d,"noShowGraceMinutes":%d}
                    """.formatted(terms.slotDurationMinutes(), terms.holdDurationMinutes(),
                        terms.cancellationCutoffMinutes(), terms.noShowGraceMinutes())))
            .andReturn();
    }

    private void assertPolicyResponse(MvcResult result, long version, BookingPolicyTerms terms) throws Exception {
        assertThat(result.getResponse().getStatus()).as("Policy PUT exception: %s", result.getResolvedException())
            .isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(JsonPath.<Number>read(body, "$.version").longValue()).isEqualTo(version);
        assertThat(JsonPath.<Integer>read(body, "$.slotDurationMinutes")).isEqualTo(terms.slotDurationMinutes());
        assertThat(JsonPath.<Integer>read(body, "$.holdDurationMinutes")).isEqualTo(terms.holdDurationMinutes());
        assertThat(JsonPath.<Integer>read(body, "$.cancellationCutoffMinutes"))
            .isEqualTo(terms.cancellationCutoffMinutes());
        assertThat(JsonPath.<Integer>read(body, "$.noShowGraceMinutes")).isEqualTo(terms.noShowGraceMinutes());
    }

    private List<Map<String, Object>> policyRows(Venue venue) {
        return jdbcTemplate.queryForList("""
            SELECT policy_version, slot_duration_minutes, hold_duration_minutes,
                   cancellation_cutoff_minutes, no_show_grace_minutes, created_at
            FROM booking_policies WHERE tenant_id = ? AND venue_id = ? ORDER BY policy_version
            """, bytes(venue.tenantId().value()), bytes(venue.id().value()));
    }

    private void assertPolicyRow(Map<String, Object> row, long version, BookingPolicyTerms terms) {
        assertThat(((Number) row.get("policy_version")).longValue()).isEqualTo(version);
        assertThat(((Number) row.get("slot_duration_minutes")).intValue()).isEqualTo(terms.slotDurationMinutes());
        assertThat(((Number) row.get("hold_duration_minutes")).intValue()).isEqualTo(terms.holdDurationMinutes());
        assertThat(((Number) row.get("cancellation_cutoff_minutes")).intValue())
            .isEqualTo(terms.cancellationCutoffMinutes());
        assertThat(((Number) row.get("no_show_grace_minutes")).intValue()).isEqualTo(terms.noShowGraceMinutes());
    }

    private Map<String, Object> slotSnapshot(Fixture fixture) {
        return jdbcTemplate.queryForMap("""
            SELECT starts_at, ends_at, capacity, applied_policy_version
            FROM slot_inventories WHERE id = ?
            """, bytes(fixture.slot().id().value()));
    }

    record PolicyPut(MvcResult result, List<String> sql) { }

    private void assertPolicyProblem(MvcResult result, int statusCode, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(statusCode);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.code")).isEqualTo(code);
    }

    private Fixture fixture(String name, String timezone, String startsAt) {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant, name, timezone, DayOfWeek.SUNDAY, "09:00", "13:00");
        Resource resource = resourceUseCase.createResource(new ResourceUseCase.CreateResource(
            tenant.id(), venue.id(), "Table", 4
        ));
        SlotInventory slot = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), startsAt
        ));
        return new Fixture(tenant, venue, resource, slot);
    }

    private Venue venue(
        Tenant tenant,
        String name,
        String timezone,
        DayOfWeek day,
        String opensAt,
        String closesAt
    ) {
        return venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), name, timezone,
            new WeeklyOperatingHours(Map.of(day, new DailyOperatingHours(
                LocalTime.parse(opensAt), LocalTime.parse(closesAt)
            ))),
            new BookingPolicyTerms(30, 5, 20, 10)
        ));
    }

    private UUID createHold(Fixture fixture) throws Exception {
        return createHold(fixture.venue(), fixture.slot());
    }

    private UUID createHold(Venue venue, SlotInventory slot) throws Exception {
        String body = mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/holds",
                venue.id().value())
                .header("Authorization", bearer(customerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotInventoryId\":\"" + slot.id().value()
                    + "\",\"partySize\":2}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private Map<String, Object> reservationSnapshot(UUID reservationId) {
        return jdbcTemplate.queryForMap("""
            SELECT state, applied_policy_version, starts_at, expires_at,
                   cancel_allowed_until, no_show_eligible_at
            FROM reservations WHERE id = ?
            """, bytes(reservationId));
    }

    private boolean allocationActive(UUID reservationId) {
        return jdbcTemplate.queryForObject(
            "SELECT active FROM capacity_allocations WHERE reservation_id = ?",
            Boolean.class, bytes(reservationId)
        );
    }

    private String bootstrap(String fixtureKey) throws Exception {
        String body = mockMvc.perform(post("/__dev/auth/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fixtureKey\":\"" + fixtureKey + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void deleteProductData() {
        for (String table : List.of(
            "capacity_allocations", "reservations", "slot_inventories", "resources",
            "venue_grants", "tenant_memberships", "venue_operating_hours", "booking_policies",
            "venues", "tenants"
        )) {
            jdbcTemplate.update("DELETE FROM " + table);
        }
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
        PolicySqlCapture policySqlCapture() {
            return new PolicySqlCapture();
        }

        @Bean
        HibernatePropertiesCustomizer policySqlInspector(PolicySqlCapture capture) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(BASE_NOW);
        }
    }

    static final class PolicySqlCapture implements StatementInspector {
        private final ThreadLocal<List<String>> statements = new ThreadLocal<>();

        void start() { statements.set(new ArrayList<>()); }
        List<String> statements() { return List.copyOf(statements.get()); }
        void stop() { statements.remove(); }

        @Override
        public String inspect(String sql) {
            if (statements.get() != null) {
                statements.get().add(sql.toLowerCase(java.util.Locale.ROOT));
            }
            return sql;
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
