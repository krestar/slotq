package com.slotq.auth.dev;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local | test")
@ConditionalOnProperty(name = "slotq.auth.dev-bootstrap-enabled", havingValue = "true")
class DevAuthFixtureDataInitializer implements ApplicationRunner {

    static final TenantId TENANT_A = tenant("20000000-0000-0000-0000-000000000001");
    static final VenueId VENUE_A = venue("30000000-0000-0000-0000-000000000001");
    static final VenueId VENUE_A_UNASSIGNED = venue("30000000-0000-0000-0000-000000000002");

    private final JdbcTemplate jdbcTemplate;
    private final AccessControlProvisioning provisioning;

    DevAuthFixtureDataInitializer(JdbcTemplate jdbcTemplate, AccessControlProvisioning provisioning) {
        this.jdbcTemplate = jdbcTemplate;
        this.provisioning = provisioning;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        insertFixtureVenue(VENUE_A);
        insertFixtureVenue(VENUE_A_UNASSIGNED);
        DevAuthFixtures.all().values().forEach(provisioning::registerPrincipal);
        membership("tenant-a-owner", TenantRole.OWNER);
        membership("tenant-a-manager", TenantRole.MANAGER);
        membership("tenant-a-staff", TenantRole.STAFF);
        grant("tenant-a-manager", TenantRole.MANAGER, VENUE_A);
        grant("tenant-a-staff", TenantRole.STAFF, VENUE_A);
    }

    private void insertFixtureVenue(VenueId venueId) {
        jdbcTemplate.update(
            "INSERT IGNORE INTO tenants (id, status) VALUES (?, 'ACTIVE')",
            bytes(TENANT_A.value())
        );
        jdbcTemplate.update(
            "INSERT IGNORE INTO venues (id, tenant_id, name, status, timezone) "
                + "VALUES (?, ?, ?, 'ACTIVE', 'UTC')",
            bytes(venueId.value()), bytes(TENANT_A.value()), "Fixture " + venueId.value()
        );
        jdbcTemplate.update(
            "INSERT IGNORE INTO booking_policies "
                + "(tenant_id, venue_id, policy_version, slot_duration_minutes, hold_duration_minutes, "
                + "cancellation_cutoff_minutes, no_show_grace_minutes, created_at) "
                + "VALUES (?, ?, 1, 30, 5, 0, 0, ?)",
            bytes(TENANT_A.value()), bytes(venueId.value()), Timestamp.from(Instant.EPOCH)
        );
    }

    private void membership(String fixtureKey, TenantRole role) {
        provisioning.assignMembership(principal(fixtureKey), TENANT_A, role);
    }

    private void grant(String fixtureKey, TenantRole role, VenueId venueId) {
        provisioning.grantVenue(principal(fixtureKey), TENANT_A, role, venueId);
    }

    private PrincipalId principal(String fixtureKey) {
        return DevAuthFixtures.find(fixtureKey).orElseThrow();
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array();
    }

    private static TenantId tenant(String value) {
        return new TenantId(UUID.fromString(value));
    }

    private static VenueId venue(String value) {
        return new VenueId(UUID.fromString(value));
    }
}
