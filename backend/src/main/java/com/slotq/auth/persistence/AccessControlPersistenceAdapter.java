package com.slotq.auth.persistence;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.slotq.auth.application.AccessControlRepository;
import com.slotq.auth.domain.ActorContext;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class AccessControlPersistenceAdapter implements AccessControlRepository {

    private final JdbcTemplate jdbcTemplate;

    AccessControlPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void registerPrincipal(PrincipalId principalId) {
        jdbcTemplate.update(
            "INSERT IGNORE INTO auth_principals (id) VALUES (?)",
            bytes(principalId.value())
        );
    }

    @Override
    public void saveMembership(PrincipalId principalId, TenantId tenantId, TenantRole role) {
        jdbcTemplate.update(
            "INSERT INTO tenant_memberships (tenant_id, principal_id, role) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE role = VALUES(role)",
            bytes(tenantId.value()), bytes(principalId.value()), role.name()
        );
    }

    @Override
    public void saveVenueGrant(
        PrincipalId principalId,
        TenantId tenantId,
        TenantRole role,
        VenueId venueId
    ) {
        jdbcTemplate.update(
            "INSERT INTO venue_grants (tenant_id, principal_id, role, venue_id) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE role = VALUES(role)",
            bytes(tenantId.value()), bytes(principalId.value()), role.name(), bytes(venueId.value())
        );
    }

    @Override
    public Optional<ActorContext> findActorForVenue(PrincipalId principalId, VenueId verifiedTargetVenueId) {
        List<MembershipRow> memberships = jdbcTemplate.query(
            "SELECT membership.tenant_id, membership.role "
                + "FROM tenant_memberships membership "
                + "JOIN venues target ON target.tenant_id = membership.tenant_id AND target.id = ? "
                + "LEFT JOIN venue_grants target_grant "
                + "ON target_grant.tenant_id = membership.tenant_id "
                + "AND target_grant.principal_id = membership.principal_id "
                + "AND target_grant.venue_id = target.id "
                + "WHERE membership.principal_id = ? "
                + "AND (membership.role = 'OWNER' OR target_grant.venue_id IS NOT NULL)",
            (resultSet, rowNumber) -> new MembershipRow(
                uuid(resultSet.getBytes("tenant_id")),
                TenantRole.valueOf(resultSet.getString("role"))
            ),
            bytes(verifiedTargetVenueId.value()), bytes(principalId.value())
        );
        if (memberships.isEmpty()) {
            return Optional.empty();
        }

        MembershipRow membership = memberships.getFirst();
        List<VenueId> effectiveVenues = membership.role() == TenantRole.OWNER
            ? findAllTenantVenues(membership.tenantId())
            : findGrantedVenues(principalId, membership.tenantId());
        return Optional.of(new ActorContext(
            principalId,
            new TenantId(membership.tenantId()),
            membership.role(),
            Set.copyOf(effectiveVenues)
        ));
    }

    private List<VenueId> findAllTenantVenues(UUID tenantId) {
        return jdbcTemplate.query(
            "SELECT id FROM venues WHERE tenant_id = ? ORDER BY id",
            (resultSet, rowNumber) -> new VenueId(uuid(resultSet.getBytes("id"))),
            bytes(tenantId)
        );
    }

    private List<VenueId> findGrantedVenues(PrincipalId principalId, UUID tenantId) {
        return jdbcTemplate.query(
            "SELECT venue_id FROM venue_grants WHERE tenant_id = ? AND principal_id = ? ORDER BY venue_id",
            (resultSet, rowNumber) -> new VenueId(uuid(resultSet.getBytes("venue_id"))),
            bytes(tenantId), bytes(principalId.value())
        );
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record MembershipRow(UUID tenantId, TenantRole role) {
    }
}
