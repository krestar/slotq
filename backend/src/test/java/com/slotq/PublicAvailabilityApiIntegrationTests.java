package com.slotq;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.jayway.jsonpath.JsonPath;
import com.slotq.booking.application.SlotInventoryUseCase;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = "slotq.auth.dev-bootstrap-enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(PublicAvailabilityApiIntegrationTests.ClockConfiguration.class)
class PublicAvailabilityApiIntegrationTests {

    private static final Instant BASE_NOW = Instant.parse("2026-08-30T09:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq");

    @Autowired MockMvc mockMvc;
    @Autowired TenantUseCase tenantUseCase;
    @Autowired VenueConfigurationUseCase venueUseCase;
    @Autowired ResourceUseCase resourceUseCase;
    @Autowired SlotInventoryUseCase slotUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;

    private String customerToken;

    @BeforeEach
    void reset() throws Exception {
        clock.set(BASE_NOW);
        deleteProductData();
        customerToken = bootstrap("customer-a");
    }

    @Test
    void listsOnlyActiveTenantAndVenueWithStableMinimalAnonymousRepresentation() throws Exception {
        Tenant activeTenant = tenantUseCase.createTenant();
        Venue zulu = venue(activeTenant, "Zulu", "UTC", defaultHours());
        Venue alpha = venue(activeTenant, "Alpha", "Asia/Seoul", defaultHours());
        Venue inactiveVenue = venue(activeTenant, "Hidden venue", "UTC", defaultHours());
        setVenueStatus(inactiveVenue, VenueStatus.INACTIVE);

        Tenant inactiveTenant = tenantUseCase.createTenant();
        Venue hiddenTenantVenue = venue(inactiveTenant, "Hidden tenant", "UTC", defaultHours());
        tenantUseCase.updateTenantStatus(inactiveTenant.id(), TenantStatus.INACTIVE);

        String body = mockMvc.perform(get("/api/v1/venues"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(alpha.id().value().toString()))
            .andExpect(jsonPath("$[0].name").value("Alpha"))
            .andExpect(jsonPath("$[0].timezone").value("Asia/Seoul"))
            .andExpect(jsonPath("$[1].id").value(zulu.id().value().toString()))
            .andExpect(jsonPath("$[2]").doesNotExist())
            .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> venues = JsonPath.read(body, "$[*]");
        assertThat(venues).allSatisfy(item -> assertThat(item.keySet())
            .containsExactlyInAnyOrder("id", "name", "timezone"));
        assertThat(body)
            .doesNotContain(activeTenant.id().value().toString())
            .doesNotContain(inactiveTenant.id().value().toString())
            .doesNotContain(inactiveVenue.id().value().toString())
            .doesNotContain(hiddenTenantVenue.id().value().toString());
    }

    @Test
    void usesVenueLocalDstDayBoundariesForSpringAndFallTransitions() throws Exception {
        clock.set(Instant.parse("2026-03-01T00:00:00Z"));
        Tenant tenant = tenantUseCase.createTenant();
        WeeklyOperatingHours hours = new WeeklyOperatingHours(Map.of(
            DayOfWeek.SUNDAY, new DailyOperatingHours(LocalTime.MIDNIGHT, LocalTime.of(2, 0)),
            DayOfWeek.MONDAY, new DailyOperatingHours(LocalTime.MIDNIGHT, LocalTime.of(2, 0))
        ));

        Venue springVenue = venue(tenant, "Spring", "America/New_York", hours);
        Resource springResource = resource(tenant, springVenue, "Spring table", 4);
        SlotInventory springInside = slot(tenant, springVenue, springResource, "2026-03-08T00:00:00-05:00");
        slot(tenant, springVenue, springResource, "2026-03-09T00:00:00-04:00");

        mockMvc.perform(availability(springVenue, "2026-03-08", 2))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].slotInventoryId")
                .value(springInside.id().value().toString()));

        Venue fallVenue = venue(tenant, "Fall", "America/New_York", hours);
        Resource fallResource = resource(tenant, fallVenue, "Fall table", 4);
        SlotInventory fallInside = slot(tenant, fallVenue, fallResource, "2026-11-01T00:00:00-04:00");
        slot(tenant, fallVenue, fallResource, "2026-11-02T00:00:00-05:00");

        mockMvc.perform(availability(fallVenue, "2026-11-01", 2))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].slotInventoryId")
                .value(fallInside.id().value().toString()));
    }

    @Test
    void filtersResourcePartySizeAndStrictlyFutureSlotsWithStableItemOrder() throws Exception {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant, "Filters", "UTC", defaultHours());
        Resource suitableB = resource(tenant, venue, "B", 4);
        Resource suitableA = resource(tenant, venue, "A", 6);
        Resource tooSmall = resource(tenant, venue, "Small", 2);
        Resource inactive = resource(tenant, venue, "Inactive", 8);
        SlotInventory later = slot(tenant, venue, suitableB, "2026-08-30T10:30:00Z");
        SlotInventory sameStartLowerId;
        SlotInventory sameStartHigherId;
        if (suitableA.id().value().compareTo(suitableB.id().value()) < 0) {
            sameStartLowerId = slot(tenant, venue, suitableA, "2026-08-30T10:00:00Z");
            sameStartHigherId = slot(tenant, venue, suitableB, "2026-08-30T10:00:00Z");
        } else {
            sameStartLowerId = slot(tenant, venue, suitableB, "2026-08-30T10:00:00Z");
            sameStartHigherId = slot(tenant, venue, suitableA, "2026-08-30T10:00:00Z");
        }
        slot(tenant, venue, tooSmall, "2026-08-30T11:00:00Z");
        slot(tenant, venue, inactive, "2026-08-30T11:00:00Z");
        resourceUseCase.updateResource(new ResourceUseCase.UpdateResource(
            tenant.id(), venue.id(), inactive.id(), inactive.name(), inactive.seatingCapacity(),
            ResourceStatus.INACTIVE
        ));

        mockMvc.perform(availability(venue, "2026-08-30", 3))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(3))
            .andExpect(jsonPath("$.items[0].slotInventoryId")
                .value(sameStartLowerId.id().value().toString()))
            .andExpect(jsonPath("$.items[0].resourceId")
                .value(sameStartLowerId.resourceId().value().toString()))
            .andExpect(jsonPath("$.items[0].startsAt").value("2026-08-30T10:00:00Z"))
            .andExpect(jsonPath("$.items[0].endsAt").value("2026-08-30T10:30:00Z"))
            .andExpect(jsonPath("$.items[0].capacity").value(1))
            .andExpect(jsonPath("$.items[1].slotInventoryId")
                .value(sameStartHigherId.id().value().toString()))
            .andExpect(jsonPath("$.items[2].slotInventoryId").value(later.id().value().toString()));

        SlotInventory boundary = slot(tenant, venue, suitableA, "2026-08-30T12:00:00Z");
        clock.set(boundary.startsAt().minusNanos(1_000));
        assertThat(availabilityIds(venue, "2026-08-30", 3))
            .contains(boundary.id().value().toString());
        clock.set(boundary.startsAt());
        assertThat(availabilityIds(venue, "2026-08-30", 3))
            .doesNotContain(boundary.id().value().toString());
        clock.set(boundary.startsAt().plusNanos(1_000));
        assertThat(availabilityIds(venue, "2026-08-30", 3))
            .doesNotContain(boundary.id().value().toString());
    }

    @Test
    void reusesEffectiveOccupancySemanticsWithoutMutatingDueHoldRows() throws Exception {
        Fixture fixture = fixture("Occupancy", "2026-08-30T12:00:00Z");
        UUID reservationId = createHold(fixture.venue(), fixture.slot());

        assertOccupancy(fixture.venue(), 1, 0);
        setReservation(reservationId, "HELD", BASE_NOW.plusNanos(1_000), true);
        assertOccupancy(fixture.venue(), 1, 0);
        setReservation(reservationId, "HELD", BASE_NOW, true);
        assertOccupancy(fixture.venue(), 0, 1);
        setReservation(reservationId, "HELD", BASE_NOW.minusNanos(1_000), true);
        Map<String, Object> reservationBefore = reservationSnapshot(reservationId);
        Map<String, Object> allocationBefore = allocationSnapshot(reservationId);
        assertOccupancy(fixture.venue(), 0, 1);
        assertThat(reservationSnapshot(reservationId)).isEqualTo(reservationBefore);
        assertThat(allocationSnapshot(reservationId)).isEqualTo(allocationBefore);

        for (String occupiedState : List.of("CONFIRMED", "CHECKED_IN")) {
            setReservation(reservationId, occupiedState, BASE_NOW.minusNanos(1_000), true);
            assertOccupancy(fixture.venue(), 1, 0);
        }
        for (String freeState : List.of("EXPIRED", "CANCELLED", "NO_SHOW", "COMPLETED")) {
            setReservation(reservationId, freeState, BASE_NOW.minusNanos(1_000), false);
            assertOccupancy(fixture.venue(), 0, 1);
        }
    }

    @Test
    void keepsTenantScopeAndMatchesHoldCapacityDecisionOnTheSameFixture() throws Exception {
        Fixture target = fixture("Target", "2026-08-30T12:00:00Z");
        Fixture other = fixture("Other tenant", "2026-08-30T12:00:00Z");
        createHold(other.venue(), other.slot());

        assertOccupancy(target.venue(), 0, 1);
        createHold(target.venue(), target.slot());
        assertOccupancy(target.venue(), 1, 0);

        mockMvc.perform(post("/api/v1/venues/{venueId}/reservations/holds",
                target.venue().id().value())
                .header("Authorization", bearer(customerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slotInventoryId\":\"" + target.slot().id().value()
                    + "\",\"partySize\":2}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CAPACITY_UNAVAILABLE"));
    }

    @Test
    void returnsEmptyItemsValidationProblemsAndHiddenVenueNotFound() throws Exception {
        Tenant tenant = tenantUseCase.createTenant();
        Venue empty = venue(tenant, "Empty", "UTC", defaultHours());

        mockMvc.perform(availability(empty, "2026-08-30", 2))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.venueId").value(empty.id().value().toString()))
            .andExpect(jsonPath("$.timezone").value("UTC"))
            .andExpect(jsonPath("$.date").value("2026-08-30"))
            .andExpect(jsonPath("$.items").isEmpty());

        mockMvc.perform(availability(empty, "not-a-date", 2))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(availability(empty, "2026-08-30", 0))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/venues/{venueId}/availability", UUID.randomUUID())
                .param("date", "2026-08-30").param("partySize", "2"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        setVenueStatus(empty, VenueStatus.INACTIVE);
        mockMvc.perform(availability(empty, "2026-08-30", 2))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        Venue inactiveTenantVenue = venue(tenantUseCase.createTenant(), "Inactive tenant", "UTC",
            defaultHours());
        Tenant inactiveTenant = tenantUseCase.getTenant(inactiveTenantVenue.tenantId());
        tenantUseCase.updateTenantStatus(inactiveTenant.id(), TenantStatus.INACTIVE);
        mockMvc.perform(availability(inactiveTenantVenue, "2026-08-30", 2))
            .andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder availability(
        Venue venue, String date, int partySize
    ) {
        return get("/api/v1/venues/{venueId}/availability", venue.id().value())
            .param("date", date).param("partySize", Integer.toString(partySize));
    }

    private List<String> availabilityIds(Venue venue, String date, int partySize) throws Exception {
        String body = mockMvc.perform(availability(venue, date, partySize))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].slotInventoryId");
    }

    private Fixture fixture(String name, String startsAt) {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant, name, "UTC", defaultHours());
        Resource resource = resource(tenant, venue, "Table", 4);
        return new Fixture(tenant, venue, resource, slot(tenant, venue, resource, startsAt));
    }

    private Venue venue(Tenant tenant, String name, String timezone, WeeklyOperatingHours hours) {
        return venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), name, timezone, hours, new BookingPolicyTerms(30, 5, 20, 10)
        ));
    }

    private Resource resource(Tenant tenant, Venue venue, String name, int seatingCapacity) {
        return resourceUseCase.createResource(new ResourceUseCase.CreateResource(
            tenant.id(), venue.id(), name, seatingCapacity
        ));
    }

    private SlotInventory slot(Tenant tenant, Venue venue, Resource resource, String startsAt) {
        return slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), startsAt
        ));
    }

    private WeeklyOperatingHours defaultHours() {
        return new WeeklyOperatingHours(Map.of(
            DayOfWeek.SUNDAY, new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(13, 0))
        ));
    }

    private void setVenueStatus(Venue venue, VenueStatus status) {
        venueUseCase.updateVenue(new VenueConfigurationUseCase.UpdateVenue(
            venue.tenantId(), venue.id(), venue.name(), status,
            venue.timezone().getId(), venue.operatingHours()
        ));
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

    private void assertOccupancy(Venue venue, int occupied, int available) throws Exception {
        mockMvc.perform(availability(venue, "2026-08-30", 2))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.items[0].capacity").value(1))
            .andExpect(jsonPath("$.items[0].occupied").value(occupied))
            .andExpect(jsonPath("$.items[0].available").value(available));
    }

    private void setReservation(UUID reservationId, String state, Instant expiresAt, boolean active) {
        jdbcTemplate.update(
            "UPDATE reservations SET state = ?, expires_at = ? WHERE id = ?",
            state, expiresAt, bytes(reservationId)
        );
        jdbcTemplate.update(
            "UPDATE capacity_allocations SET active = ? WHERE reservation_id = ?",
            active, bytes(reservationId)
        );
    }

    private Map<String, Object> reservationSnapshot(UUID reservationId) {
        return jdbcTemplate.queryForMap("""
            SELECT HEX(id) AS id, HEX(tenant_id) AS tenant_id, HEX(venue_id) AS venue_id,
                   HEX(resource_id) AS resource_id, HEX(slot_inventory_id) AS slot_inventory_id,
                   HEX(customer_principal_id) AS customer_principal_id, party_size, state,
                   applied_policy_version, starts_at, expires_at, cancel_allowed_until,
                   no_show_eligible_at
            FROM reservations WHERE id = ?
            """, bytes(reservationId));
    }

    private Map<String, Object> allocationSnapshot(UUID reservationId) {
        return jdbcTemplate.queryForMap(
            """
            SELECT HEX(id) AS id, HEX(reservation_id) AS reservation_id,
                   HEX(tenant_id) AS tenant_id, HEX(venue_id) AS venue_id,
                   HEX(resource_id) AS resource_id, HEX(slot_inventory_id) AS slot_inventory_id,
                   units, active
            FROM capacity_allocations WHERE reservation_id = ?
            """, bytes(reservationId)
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
