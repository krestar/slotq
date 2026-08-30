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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.WeeklyOperatingHours;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
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
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq");

    @Autowired MockMvc mockMvc;
    @Autowired TenantUseCase tenantUseCase;
    @Autowired VenueConfigurationUseCase venueUseCase;
    @Autowired ResourceUseCase resourceUseCase;
    @Autowired SlotInventoryUseCase slotUseCase;
    @Autowired AccessControlProvisioning accessControlProvisioning;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;

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

        mockMvc.perform(patch("/api/v1/management/venues/{venueId}", venue.id().value())
                .header("Authorization", bearer(operatorToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\",\"status\":\"INACTIVE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Renamed"))
            .andExpect(jsonPath("$.status").value("INACTIVE"));

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
