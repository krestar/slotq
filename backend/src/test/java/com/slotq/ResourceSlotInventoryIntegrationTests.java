package com.slotq;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.ResourceType;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.WeeklyOperatingHours;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class ResourceSlotInventoryIntegrationTests {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq");

    @Autowired
    TenantUseCase tenantUseCase;

    @Autowired
    VenueConfigurationUseCase venueUseCase;

    @Autowired
    ResourceUseCase resourceUseCase;

    @Autowired
    SlotInventoryUseCase slotUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void createsTableResourceAndUtcSlotRoundTripsThroughJpa() {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant, "Asia/Seoul", DayOfWeek.SATURDAY, "09:00", "12:00", 30);
        Resource resource = resourceUseCase.createResource(
            new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Window table", 4)
        );

        SlotInventory slot = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), "2026-08-29T09:30:00+09:00"
        ));

        assertThat(resource.type()).isEqualTo(ResourceType.TABLE);
        assertThat(resource.status()).isEqualTo(ResourceStatus.ACTIVE);
        assertThat(slot.startsAt()).isEqualTo(Instant.parse("2026-08-29T00:30:00Z"));
        assertThat(slot.endsAt()).isEqualTo(Instant.parse("2026-08-29T01:00:00Z"));
        assertThat(slot.capacity()).isEqualTo(1);
        assertThat(slot.appliedPolicyVersion()).isEqualTo(1);
        assertThat(slotUseCase.getSlot(tenant.id(), venue.id(), resource.id(), slot.id())).isEqualTo(slot);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT starts_at FROM slot_inventories WHERE id = ?",
            Timestamp.class,
            bytes(slot.id().value())
        ).toInstant()).isEqualTo(slot.startsAt());
    }

    @Test
    void rejectsInvalidResourceAndReservationEligibility() {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant, "UTC", DayOfWeek.SATURDAY, "09:00", "12:00", 30);

        assertThatThrownBy(() -> resourceUseCase.createResource(
            new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "bad", 0)
        )).isInstanceOf(IllegalArgumentException.class);

        Resource resource = resourceUseCase.createResource(
            new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Table 1", 4)
        );
        assertThatThrownBy(() -> resourceUseCase.validateReservationEligibility(
            tenant.id(), venue.id(), resource.id(), 5
        )).isInstanceOf(IllegalArgumentException.class);

        Resource inactive = resourceUseCase.updateResource(new ResourceUseCase.UpdateResource(
            tenant.id(), venue.id(), resource.id(), resource.name(), resource.seatingCapacity(),
            ResourceStatus.INACTIVE
        ));
        assertThatThrownBy(() -> resourceUseCase.validateReservationEligibility(
            tenant.id(), venue.id(), inactive.id(), 2
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), inactive.id(), "2026-08-29T09:00:00Z"
        ))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsCrossTenantAndCrossVenueReferencesInDomainApplicationAndDatabase() {
        Tenant owner = tenantUseCase.createTenant();
        Tenant other = tenantUseCase.createTenant();
        Venue ownerVenue = venue(owner, "UTC", DayOfWeek.SATURDAY, "09:00", "12:00", 30);
        Venue otherVenue = venue(other, "UTC", DayOfWeek.SATURDAY, "09:00", "12:00", 30);
        Resource resource = resourceUseCase.createResource(
            new ResourceUseCase.CreateResource(owner.id(), ownerVenue.id(), "Table 1", 2)
        );

        assertThatThrownBy(() -> SlotInventory.create(
            SlotInventoryId.newId(), other.id(), otherVenue.id(), resource,
            Instant.parse("2026-08-29T09:00:00Z"), 30, 1
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            other.id(), otherVenue.id(), resource.id(), "2026-08-29T09:00:00Z"
        ))).isInstanceOf(java.util.NoSuchElementException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO resources (id, tenant_id, venue_id, type, name, seating_capacity, status) "
                + "VALUES (?, ?, ?, 'TABLE', 'forged', 2, 'ACTIVE')",
            bytes(UUID.randomUUID()), bytes(other.id().value()), bytes(ownerVenue.id().value())
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO slot_inventories "
                + "(id, tenant_id, venue_id, resource_id, starts_at, ends_at, capacity, applied_policy_version) "
                + "VALUES (?, ?, ?, ?, ?, ?, 1, 1)",
            bytes(UUID.randomUUID()), bytes(other.id().value()), bytes(otherVenue.id().value()),
            bytes(resource.id().value()), Timestamp.from(Instant.parse("2026-08-29T09:00:00Z")),
            Timestamp.from(Instant.parse("2026-08-29T09:30:00Z"))
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void validatesOffsetOperatingHoursGridAndFixedDuration() {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant, "Asia/Seoul", DayOfWeek.SATURDAY, "09:00", "12:00", 30);
        Resource resource = resourceUseCase.createResource(
            new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Table 1", 2)
        );

        assertThatThrownBy(() -> slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), "2026-08-29T09:00:00"
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), "2026-08-29T09:00+09:00"
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), "2026-08-29T09:15:00+09:00"
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), "2026-08-29T11:45:00+09:00"
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), "2026-08-29T09:00:00Z"
        ))).isInstanceOf(java.time.DateTimeException.class);

        SlotInventory invalidDuration = new SlotInventory(
            SlotInventoryId.newId(), tenant.id(), venue.id(), resource.id(),
            Instant.parse("2026-08-29T00:00:00Z"), Instant.parse("2026-08-29T00:45:00Z"), 1, 1
        );
        assertThatThrownBy(() -> invalidDuration.validateDurationMinutes(30))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invalidDuration.validateAllocationQuantity(2))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlotInventory(
            SlotInventoryId.newId(), tenant.id(), venue.id(), resource.id(),
            Instant.parse("2026-08-29T00:00:00Z"), Instant.parse("2026-08-29T00:30:00Z"), 2, 1
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateAndOverlappingSlotsButAllowsEndExclusiveAdjacency() {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant, "UTC", DayOfWeek.SATURDAY, "09:00", "12:00", 60);
        Resource resource = resourceUseCase.createResource(
            new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Table 1", 2)
        );
        slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), "2026-08-29T09:00:00Z"
        ));
        assertThatThrownBy(() -> slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), "2026-08-29T09:00:00Z"
        ))).isInstanceOf(IllegalArgumentException.class);

        venueUseCase.updateBookingPolicy(new VenueConfigurationUseCase.UpdateBookingPolicy(
            tenant.id(), venue.id(), new BookingPolicyTerms(30, 5, 0, 0)
        ));
        assertThatThrownBy(() -> slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), "2026-08-29T09:30:00Z"
        ))).isInstanceOf(IllegalArgumentException.class);

        SlotInventory adjacent = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), "2026-08-29T10:00:00Z"
        ));
        assertThat(adjacent.startsAt()).isEqualTo(Instant.parse("2026-08-29T10:00:00Z"));
    }

    @Test
    void concurrentCreationStoresOnlyOneSlotAtTheSameResourceStart() throws Exception {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant, "UTC", DayOfWeek.SATURDAY, "09:00", "12:00", 30);
        Resource resource = resourceUseCase.createResource(
            new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Table 1", 2)
        );
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createConcurrentSlot(start, tenant, venue, resource));
            var second = executor.submit(() -> createConcurrentSlot(start, tenant, venue, resource));
            start.countDown();

            assertThat(new Object[] {first.get(), second.get()})
                .filteredOn(SlotInventory.class::isInstance)
                .hasSize(1);
            assertThat(new Object[] {first.get(), second.get()})
                .filteredOn(IllegalArgumentException.class::isInstance)
                .hasSize(1);
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM slot_inventories WHERE tenant_id = ? AND resource_id = ?",
            Long.class,
            bytes(tenant.id().value()),
            bytes(resource.id().value())
        )).isEqualTo(1L);
    }

    @Test
    void handlesDstGapAndBothExplicitOverlapOffsets() {
        Tenant tenant = tenantUseCase.createTenant();
        Venue spring = venue(tenant, "America/New_York", DayOfWeek.SUNDAY, "00:00", "05:00", 30);
        Resource springTable = resourceUseCase.createResource(
            new ResourceUseCase.CreateResource(tenant.id(), spring.id(), "Spring table", 2)
        );
        assertThatThrownBy(() -> slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), spring.id(), springTable.id(), "2026-03-08T02:00:00-05:00"
        ))).isInstanceOf(java.time.DateTimeException.class);

        Venue autumn = venue(tenant, "America/New_York", DayOfWeek.SUNDAY, "00:00", "05:00", 30);
        Resource first = resourceUseCase.createResource(
            new ResourceUseCase.CreateResource(tenant.id(), autumn.id(), "First overlap table", 2)
        );
        Resource second = resourceUseCase.createResource(
            new ResourceUseCase.CreateResource(tenant.id(), autumn.id(), "Second overlap table", 2)
        );
        SlotInventory earlier = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), autumn.id(), first.id(), "2026-11-01T01:30:00-04:00"
        ));
        SlotInventory later = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), autumn.id(), second.id(), "2026-11-01T01:30:00-05:00"
        ));
        assertThat(later.startsAt()).isEqualTo(earlier.startsAt().plusSeconds(3600));
    }

    @Test
    void databaseRejectsCapacityOtherThanOneInvalidRangeAndDuplicateStart() {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant, "UTC", DayOfWeek.SATURDAY, "09:00", "12:00", 30);
        Resource resource = resourceUseCase.createResource(
            new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Table 1", 2)
        );
        SlotInventory slot = slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), "2026-08-29T09:00:00Z"
        ));

        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO resources (id, tenant_id, venue_id, type, name, seating_capacity, status) "
                + "VALUES (?, ?, ?, 'TABLE', 'invalid', 0, 'ACTIVE')",
            bytes(UUID.randomUUID()), bytes(tenant.id().value()), bytes(venue.id().value())
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> insertSlot(
            UUID.randomUUID(), tenant, venue, resource, "2026-08-29T10:00:00Z", "2026-08-29T10:30:00Z", 2
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertSlot(
            UUID.randomUUID(), tenant, venue, resource, "2026-08-29T10:00:00Z", "2026-08-29T10:00:00Z", 1
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertSlot(
            UUID.randomUUID(), tenant, venue, resource, slot.startsAt().toString(), slot.endsAt().toString(), 1
        )).isInstanceOf(DataAccessException.class);
    }

    private Object createConcurrentSlot(
        CountDownLatch start,
        Tenant tenant,
        Venue venue,
        Resource resource
    ) throws InterruptedException {
        start.await();
        try {
            return slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
                tenant.id(), venue.id(), resource.id(), "2026-08-29T09:00:00Z"
            ));
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private Venue venue(
        Tenant tenant,
        String timezone,
        DayOfWeek day,
        String opensAt,
        String closesAt,
        int slotDurationMinutes
    ) {
        return venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(),
            timezone,
            new WeeklyOperatingHours(Map.of(
                day, new DailyOperatingHours(LocalTime.parse(opensAt), LocalTime.parse(closesAt))
            )),
            new BookingPolicyTerms(slotDurationMinutes, 5, 0, 0)
        ));
    }

    private void insertSlot(
        UUID id,
        Tenant tenant,
        Venue venue,
        Resource resource,
        String startsAt,
        String endsAt,
        int capacity
    ) {
        jdbcTemplate.update(
            "INSERT INTO slot_inventories "
                + "(id, tenant_id, venue_id, resource_id, starts_at, ends_at, capacity, applied_policy_version) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
            bytes(id), bytes(tenant.id().value()), bytes(venue.id().value()), bytes(resource.id().value()),
            Timestamp.from(Instant.parse(startsAt)), Timestamp.from(Instant.parse(endsAt)), capacity
        );
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array();
    }
}
