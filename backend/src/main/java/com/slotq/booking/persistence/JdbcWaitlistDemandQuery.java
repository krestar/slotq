package com.slotq.booking.persistence;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.slotq.booking.application.WaitlistDemandQuery;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcWaitlistDemandQuery implements WaitlistDemandQuery {

    private static final String TARGET_SELECT = """
        SELECT slot.tenant_id, slot.venue_id, slot.resource_id, slot.id,
               slot.starts_at, slot.ends_at,
               tenant.status AS tenant_status, venue.status AS venue_status,
               EXISTS (
                   SELECT 1
                     FROM slot_inventories candidate
                     JOIN resources resource
                       ON resource.tenant_id = candidate.tenant_id
                      AND resource.venue_id = candidate.venue_id
                      AND resource.id = candidate.resource_id
                    WHERE candidate.tenant_id = slot.tenant_id
                      AND candidate.venue_id = slot.venue_id
                      AND candidate.starts_at = slot.starts_at
                      AND candidate.ends_at = slot.ends_at
                      AND resource.status = 'ACTIVE'
                      AND resource.seating_capacity >= ?
               ) AS eligible_resource_exists
          FROM slot_inventories slot
          JOIN venues venue ON venue.tenant_id = slot.tenant_id AND venue.id = slot.venue_id
          JOIN tenants tenant ON tenant.id = slot.tenant_id
         WHERE slot.venue_id = ? AND slot.id = ?
         FOR UPDATE
        """;

    private final JdbcTemplate jdbc;

    JdbcWaitlistDemandQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RegistrationTarget> findRegistrationTargetForUpdate(
        VenueId venueId,
        SlotInventoryId slotInventoryId,
        int partySize
    ) {
        return jdbc.query(
            TARGET_SELECT,
            JdbcWaitlistDemandQuery::registrationTarget,
            partySize,
            bytes(venueId.value()),
            bytes(slotInventoryId.value())
        ).stream().findFirst();
    }

    @Override
    public Optional<SlotTarget> findSlot(VenueId venueId, SlotInventoryId slotInventoryId) {
        return jdbc.query("""
            SELECT slot.tenant_id, slot.venue_id, slot.id, slot.starts_at, slot.ends_at,
                   resource.status AS resource_status, resource.seating_capacity
              FROM slot_inventories slot
              JOIN resources resource
                ON resource.tenant_id = slot.tenant_id
               AND resource.venue_id = slot.venue_id
               AND resource.id = slot.resource_id
             WHERE slot.venue_id = ? AND slot.id = ?
            """, (row, number) -> new SlotTarget(
                new TenantId(uuid(row.getBytes("tenant_id"))),
                new VenueId(uuid(row.getBytes("venue_id"))),
                new SlotInventoryId(uuid(row.getBytes("id"))),
                instant(row, "starts_at"),
                instant(row, "ends_at"),
                "ACTIVE".equals(row.getString("resource_status")),
                row.getInt("seating_capacity")
            ), bytes(venueId.value()), bytes(slotInventoryId.value())).stream().findFirst();
    }

    private static RegistrationTarget registrationTarget(ResultSet row, int number) throws SQLException {
        return new RegistrationTarget(
            new TenantId(uuid(row.getBytes("tenant_id"))),
            new VenueId(uuid(row.getBytes("venue_id"))),
            new ResourceId(uuid(row.getBytes("resource_id"))),
            new SlotInventoryId(uuid(row.getBytes("id"))),
            instant(row, "starts_at"),
            instant(row, "ends_at"),
            "ACTIVE".equals(row.getString("tenant_status")),
            "ACTIVE".equals(row.getString("venue_status")),
            row.getBoolean("eligible_resource_exists")
        );
    }

    private static java.time.Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
