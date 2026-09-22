package com.slotq.booking.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.slotq.booking.application.PromotionAvailabilityQuery;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static com.slotq.booking.persistence.JdbcWaitlistDemandQuery.bytes;
import static com.slotq.booking.persistence.JdbcWaitlistDemandQuery.uuid;

@Component
class JdbcPromotionAvailabilityQuery implements PromotionAvailabilityQuery {
    private static final String SELECT = """
        SELECT s.tenant_id, s.venue_id, s.resource_id, s.id, s.starts_at, s.ends_at, r.seating_capacity,
               (s.starts_at > ? AND t.status = 'ACTIVE' AND v.status = 'ACTIVE' AND r.status = 'ACTIVE'
                AND NOT EXISTS (
                    SELECT 1 FROM reservations reservation JOIN capacity_allocations allocation
                      ON allocation.tenant_id = reservation.tenant_id AND allocation.venue_id = reservation.venue_id
                     AND allocation.resource_id = reservation.resource_id AND allocation.slot_inventory_id = reservation.slot_inventory_id
                     AND allocation.reservation_id = reservation.id
                    WHERE reservation.tenant_id = s.tenant_id AND reservation.venue_id = s.venue_id
                      AND reservation.resource_id = s.resource_id AND reservation.slot_inventory_id = s.id
                      AND allocation.active = TRUE
                      AND (reservation.state IN ('CONFIRMED','CHECKED_IN')
                           OR (reservation.state = 'HELD' AND reservation.expires_at > ?))
                )) AS available
          FROM slot_inventories s
          JOIN resources r ON r.tenant_id = s.tenant_id AND r.venue_id = s.venue_id AND r.id = s.resource_id
          JOIN venues v ON v.tenant_id = s.tenant_id AND v.id = s.venue_id
          JOIN tenants t ON t.id = s.tenant_id
        """;
    private final JdbcTemplate jdbc;
    JdbcPromotionAvailabilityQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Target> availableAfter(SlotInventoryId after, Instant observedAt, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid discovery limit");
        String cursor = after == null ? "" : " WHERE s.id > ?";
        Object[] arguments = after == null
            ? new Object[]{Timestamp.from(observedAt), Timestamp.from(observedAt), limit}
            : new Object[]{Timestamp.from(observedAt), Timestamp.from(observedAt), bytes(after.value()), limit};
        return jdbc.query(SELECT + cursor + " HAVING available = TRUE ORDER BY s.id LIMIT ?",
            JdbcPromotionAvailabilityQuery::target, arguments);
    }

    @Override
    public Optional<Target> observe(VenueId venueId, SlotInventoryId slotId, Instant observedAt) {
        return jdbc.query(SELECT + " WHERE s.venue_id = ? AND s.id = ?", JdbcPromotionAvailabilityQuery::target,
            Timestamp.from(observedAt), Timestamp.from(observedAt), bytes(venueId.value()), bytes(slotId.value()))
            .stream().findFirst();
    }

    private static Target target(ResultSet row, int number) throws SQLException {
        return new Target(new TenantId(uuid(row.getBytes("tenant_id"))), new VenueId(uuid(row.getBytes("venue_id"))),
            new ResourceId(uuid(row.getBytes("resource_id"))), new SlotInventoryId(uuid(row.getBytes("id"))),
            row.getObject("starts_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            row.getObject("ends_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            row.getInt("seating_capacity"), row.getBoolean("available"));
    }
}
