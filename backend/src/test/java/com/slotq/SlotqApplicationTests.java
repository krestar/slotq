package com.slotq;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.application.AccessDeniedException;
import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.application.ReservationAccessTarget;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.tenancy.domain.TenantStatus;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.PolicyDeadlines;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueStatus;
import com.slotq.venue.domain.WeeklyOperatingHours;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@Import(SlotqApplicationTests.FixedClockConfiguration.class)
class SlotqApplicationTests {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq");

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TenantUseCase tenantUseCase;

    @Autowired
    VenueConfigurationUseCase venueUseCase;

    @Autowired
    AccessControlProvisioning accessControlProvisioning;

    @Autowired
    AuthorizationUseCase authorizationUseCase;

    @Test
    void emptyMySqlAppliesMigrationsAndValidatesJpaMappings() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("3");

        String characterSet = jdbcTemplate.queryForObject(
            "SELECT DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE()",
            String.class
        );
        String collation = jdbcTemplate.queryForObject(
            "SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = DATABASE()",
            String.class
        );

        assertThat(characterSet).isEqualTo("utf8mb4");
        assertThat(collation).isEqualTo("utf8mb4_0900_ai_ci");
    }

    @Test
    void resolvesSamePrincipalOwnerManagerAndStaffMembershipsAtEachTargetBoundary() {
        PrincipalId principalId = PrincipalId.newId();
        accessControlProvisioning.registerPrincipal(principalId);

        Tenant ownerTenant = tenantUseCase.createTenant();
        Venue ownerVenue = venue(ownerTenant);
        Venue secondOwnerVenue = venue(ownerTenant);
        accessControlProvisioning.assignMembership(principalId, ownerTenant.id(), TenantRole.OWNER);

        Tenant managerTenant = tenantUseCase.createTenant();
        Venue managedVenue = venue(managerTenant);
        Venue unassignedManagerVenue = venue(managerTenant);
        accessControlProvisioning.assignMembership(principalId, managerTenant.id(), TenantRole.MANAGER);
        accessControlProvisioning.grantVenue(
            principalId, managerTenant.id(), TenantRole.MANAGER, managedVenue.id()
        );

        Tenant staffTenant = tenantUseCase.createTenant();
        Venue staffedVenue = venue(staffTenant);
        accessControlProvisioning.assignMembership(principalId, staffTenant.id(), TenantRole.STAFF);
        accessControlProvisioning.grantVenue(principalId, staffTenant.id(), TenantRole.STAFF, staffedVenue.id());

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(principalId);
        assertThat(authorizationUseCase.requireVenueAccess(principal, ownerVenue.id()).role())
            .isEqualTo(TenantRole.OWNER);
        assertThat(authorizationUseCase.requireVenueAccess(principal, ownerVenue.id()).venueGrants())
            .containsExactlyInAnyOrder(ownerVenue.id(), secondOwnerVenue.id());
        assertThat(authorizationUseCase.requireVenueConfigurationAccess(principal, managedVenue.id()).role())
            .isEqualTo(TenantRole.MANAGER);
        assertThat(authorizationUseCase.requireVenueAccess(principal, staffedVenue.id()).role())
            .isEqualTo(TenantRole.STAFF);

        assertThatThrownBy(() -> authorizationUseCase.requireVenueAccess(principal, unassignedManagerVenue.id()))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> authorizationUseCase.requireVenueConfigurationAccess(principal, staffedVenue.id()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void reservationOwnershipAllowsOnlyStoredCustomerOrScopedOperator() {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant);
        PrincipalId customer = PrincipalId.newId();
        PrincipalId otherCustomer = PrincipalId.newId();
        PrincipalId operator = PrincipalId.newId();
        accessControlProvisioning.registerPrincipal(customer);
        accessControlProvisioning.registerPrincipal(otherCustomer);
        accessControlProvisioning.registerPrincipal(operator);
        accessControlProvisioning.assignMembership(operator, tenant.id(), TenantRole.MANAGER);
        accessControlProvisioning.grantVenue(operator, tenant.id(), TenantRole.MANAGER, venue.id());
        ReservationAccessTarget target = new ReservationAccessTarget(tenant.id(), venue.id(), customer);

        assertThat(authorizationUseCase.authorizeReservationRead(new AuthenticatedPrincipal(customer), target)
            .isCustomer()).isTrue();
        assertThat(authorizationUseCase.authorizeReservationRead(new AuthenticatedPrincipal(operator), target)
            .operator().role()).isEqualTo(TenantRole.MANAGER);
        assertThatThrownBy(() -> authorizationUseCase.authorizeReservationRead(
            new AuthenticatedPrincipal(otherCustomer), target
        )).isInstanceOf(ResourceNotFoundException.class);

        Tenant otherTenant = tenantUseCase.createTenant();
        assertThatThrownBy(() -> authorizationUseCase.authorizeReservationRead(
            new AuthenticatedPrincipal(operator),
            new ReservationAccessTarget(otherTenant.id(), venue.id(), customer)
        )).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void databaseRejectsCrossTenantAndRoleForgedVenueGrants() {
        Tenant tenant = tenantUseCase.createTenant();
        Tenant otherTenant = tenantUseCase.createTenant();
        Venue venue = venue(tenant);
        Venue otherVenue = venue(otherTenant);
        PrincipalId principal = PrincipalId.newId();
        accessControlProvisioning.registerPrincipal(principal);
        accessControlProvisioning.assignMembership(principal, tenant.id(), TenantRole.MANAGER);

        assertThatThrownBy(() -> accessControlProvisioning.grantVenue(
            principal, tenant.id(), TenantRole.MANAGER, otherVenue.id()
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> accessControlProvisioning.grantVenue(
            principal, tenant.id(), TenantRole.STAFF, venue.id()
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO venue_grants (tenant_id, principal_id, role, venue_id) VALUES (?, ?, 'OWNER', ?)",
            bytes(tenant.id().value()), bytes(principal.value()), bytes(venue.id().value())
        )).isInstanceOf(DataAccessException.class);
    }

    @Test
    void createsReadsAndUpdatesTenantVenueAndVersionedPolicy() {
        Tenant tenant = tenantUseCase.createTenant();
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenantUseCase.getTenant(tenant.id())).isEqualTo(tenant);

        WeeklyOperatingHours initialHours = hours(DayOfWeek.MONDAY, "09:00", "18:00");
        Venue created = venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "Asia/Seoul", initialHours, new BookingPolicyTerms(30, 5, 60, 15)
        ));

        Venue roundTripped = venueUseCase.getVenue(tenant.id(), created.id());
        assertThat(roundTripped).isEqualTo(created);
        assertThat(roundTripped.currentPolicy().version()).isEqualTo(1);

        Venue updatedVenue = venueUseCase.updateVenue(new VenueConfigurationUseCase.UpdateVenue(
            tenant.id(), created.id(), VenueStatus.INACTIVE, "America/New_York",
            hours(DayOfWeek.TUESDAY, "10:00", "17:00")
        ));
        assertThat(updatedVenue.status()).isEqualTo(VenueStatus.INACTIVE);
        assertThat(updatedVenue.timezone()).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(updatedVenue.operatingHours().hoursOn(DayOfWeek.MONDAY)).isEmpty();
        assertThat(updatedVenue.operatingHours().hoursOn(DayOfWeek.TUESDAY)).isPresent();
        assertThat(venueUseCase.getVenue(tenant.id(), created.id())).isEqualTo(updatedVenue);

        BookingPolicy versionTwo = venueUseCase.updateBookingPolicy(
            new VenueConfigurationUseCase.UpdateBookingPolicy(
                tenant.id(), created.id(), new BookingPolicyTerms(60, 10, 0, 0)
            )
        );
        assertThat(versionTwo.version()).isEqualTo(2);
        assertThat(venueUseCase.getVenue(tenant.id(), created.id()).currentPolicy()).isEqualTo(versionTwo);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM booking_policies WHERE tenant_id = ? AND venue_id = ?",
            Long.class, bytes(tenant.id().value()), bytes(created.id().value())
        )).isEqualTo(2L);

        Tenant inactiveTenant = tenantUseCase.updateTenantStatus(tenant.id(), TenantStatus.INACTIVE);
        assertThat(inactiveTenant.status()).isEqualTo(TenantStatus.INACTIVE);
        assertThat(tenantUseCase.getTenant(tenant.id())).isEqualTo(inactiveTenant);
        assertThat(venueUseCase.getVenue(tenant.id(), created.id()).status()).isEqualTo(VenueStatus.INACTIVE);
    }

    @Test
    void databaseRejectsCrossTenantVenuePolicyReference() {
        Tenant owner = tenantUseCase.createTenant();
        Tenant other = tenantUseCase.createTenant();
        Venue venue = venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            owner.id(), "UTC", WeeklyOperatingHours.closedAllWeek(), new BookingPolicyTerms(30, 5, 0, 0)
        ));

        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO booking_policies "
                + "(tenant_id, venue_id, policy_version, slot_duration_minutes, hold_duration_minutes, "
                + "cancellation_cutoff_minutes, no_show_grace_minutes, created_at) "
                + "VALUES (?, ?, 2, 30, 5, 0, 0, ?)",
            bytes(other.id().value()), bytes(venue.id().value()), Timestamp.from(NOW)
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO booking_policies "
                + "(tenant_id, venue_id, policy_version, slot_duration_minutes, hold_duration_minutes, "
                + "cancellation_cutoff_minutes, no_show_grace_minutes, created_at) "
                + "VALUES (?, ?, 2, 0, 5, 0, 0, ?)",
            bytes(owner.id().value()), bytes(venue.id().value()), Timestamp.from(NOW)
        )).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO venue_operating_hours "
                + "(tenant_id, venue_id, day_of_week, opens_at, closes_at) "
                + "VALUES (?, ?, 1, '09:00:00', '09:00:00')",
            bytes(owner.id().value()), bytes(venue.id().value())
        )).isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> venueUseCase.getVenue(other.id(), venue.id()))
            .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void invalidTimezoneHoursPolicyUnitsAndRequiredValuesAreNotSaved() {
        Tenant tenant = tenantUseCase.createTenant();
        long before = venueCount(tenant);

        assertThatThrownBy(() -> venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "Mars/Olympus", WeeklyOperatingHours.closedAllWeek(),
            new BookingPolicyTerms(30, 5, 0, 0)
        ))).isInstanceOf(ZoneRulesException.class);
        assertThatThrownBy(() -> venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "+09:00", WeeklyOperatingHours.closedAllWeek(),
            new BookingPolicyTerms(30, 5, 0, 0)
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DailyOperatingHours(LocalTime.NOON, LocalTime.NOON))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingPolicyTerms(0, 5, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookingPolicyTerms(30, 0, -1, -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO venues (id, tenant_id, status, timezone) VALUES (?, ?, 'ACTIVE', NULL)",
            bytes(UUID.randomUUID()), bytes(tenant.id().value())
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(venueCount(tenant)).isEqualTo(before);
    }

    @Test
    void injectedClockCalculatesBoundaryDeadlinesAndPolicySnapshotRemainsStable() {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "UTC", WeeklyOperatingHours.closedAllWeek(),
            new BookingPolicyTerms(30, 10, 60, 15)
        ));
        BookingPolicy versionOne = venue.currentPolicy();

        Instant nearStart = NOW.plusSeconds(5 * 60L);
        PolicyDeadlines clippedHold = venueUseCase.applyCurrentPolicy(tenant.id(), venue.id(), nearStart);
        assertThat(clippedHold.appliedPolicyVersion()).isEqualTo(1);
        assertThat(clippedHold.expiresAt()).isEqualTo(nearStart);
        assertThat(clippedHold.cancelAllowedUntil()).isEqualTo(NOW.minusSeconds(55 * 60L));
        assertThat(clippedHold.noShowEligibleAt()).isEqualTo(NOW.plusSeconds(20 * 60L));

        venueUseCase.updateBookingPolicy(new VenueConfigurationUseCase.UpdateBookingPolicy(
            tenant.id(), venue.id(), new BookingPolicyTerms(60, 20, 0, 0)
        ));
        Instant laterStart = NOW.plusSeconds(60 * 60L);
        PolicyDeadlines current = venueUseCase.applyCurrentPolicy(tenant.id(), venue.id(), laterStart);
        assertThat(current.appliedPolicyVersion()).isEqualTo(2);
        assertThat(current.expiresAt()).isEqualTo(NOW.plusSeconds(20 * 60L));
        assertThat(current.cancelAllowedUntil()).isEqualTo(laterStart);
        assertThat(current.noShowEligibleAt()).isEqualTo(laterStart);

        PolicyDeadlines previousContract = versionOne.applyTo(laterStart, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(previousContract.appliedPolicyVersion()).isEqualTo(1);
        assertThat(previousContract.expiresAt()).isEqualTo(NOW.plusSeconds(10 * 60L));
        assertThat(previousContract.cancelAllowedUntil()).isEqualTo(NOW);
        assertThat(previousContract.noShowEligibleAt()).isEqualTo(NOW.plusSeconds(75 * 60L));
    }

    @Test
    void concurrentPolicyUpdatesKeepVenueVersionMonotonic() throws Exception {
        Tenant tenant = tenantUseCase.createTenant();
        Venue venue = venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "UTC", WeeklyOperatingHours.closedAllWeek(),
            new BookingPolicyTerms(30, 5, 0, 0)
        ));
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return venueUseCase.updateBookingPolicy(new VenueConfigurationUseCase.UpdateBookingPolicy(
                    tenant.id(), venue.id(), new BookingPolicyTerms(30, 10, 0, 0)
                ));
            });
            var second = executor.submit(() -> {
                start.await();
                return venueUseCase.updateBookingPolicy(new VenueConfigurationUseCase.UpdateBookingPolicy(
                    tenant.id(), venue.id(), new BookingPolicyTerms(30, 15, 0, 0)
                ));
            });

            start.countDown();
            assertThat(new long[] {first.get().version(), second.get().version()})
                .containsExactlyInAnyOrder(2L, 3L);
        }

        assertThat(venueUseCase.getVenue(tenant.id(), venue.id()).currentPolicy().version()).isEqualTo(3);
    }

    private WeeklyOperatingHours hours(DayOfWeek day, String opensAt, String closesAt) {
        return new WeeklyOperatingHours(Map.of(
            day, new DailyOperatingHours(LocalTime.parse(opensAt), LocalTime.parse(closesAt))
        ));
    }

    private Venue venue(Tenant tenant) {
        return venueUseCase.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "UTC", WeeklyOperatingHours.closedAllWeek(), new BookingPolicyTerms(30, 5, 0, 0)
        ));
    }

    private long venueCount(Tenant tenant) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM venues WHERE tenant_id = ?",
            Long.class, bytes(tenant.id().value())
        );
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array();
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
