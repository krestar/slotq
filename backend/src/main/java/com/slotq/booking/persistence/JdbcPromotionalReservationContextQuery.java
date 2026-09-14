package com.slotq.booking.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.slotq.booking.application.PromotionalReservationContextQuery;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.tenancy.domain.TenantStatus;
import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.ResourceType;
import com.slotq.venue.domain.VenueId;
import com.slotq.venue.domain.VenueStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcPromotionalReservationContextQuery implements PromotionalReservationContextQuery {

    private final JdbcTemplate jdbc;

    JdbcPromotionalReservationContextQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Context> findCurrent(
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId
    ) {
        return jdbc.query("""
            SELECT STRAIGHT_JOIN locked_slot.tenant_id,
                   locked_slot.venue_id,
                   locked_slot.resource_id,
                   tenant.status AS tenant_status,
                   venue.status AS venue_status,
                   resource.type AS resource_type,
                   resource.name AS resource_name,
                   resource.seating_capacity,
                   resource.status AS resource_status,
                   policy.policy_version,
                   policy.slot_duration_minutes,
                   policy.hold_duration_minutes,
                   policy.cancellation_cutoff_minutes,
                   policy.no_show_grace_minutes,
                   policy.created_at
              FROM slot_inventories locked_slot
              JOIN tenants tenant
                ON tenant.id = locked_slot.tenant_id
              JOIN venues venue
                ON venue.tenant_id = locked_slot.tenant_id
               AND venue.id = locked_slot.venue_id
              JOIN resources resource
                ON resource.tenant_id = locked_slot.tenant_id
               AND resource.venue_id = locked_slot.venue_id
               AND resource.id = locked_slot.resource_id
              JOIN booking_policies policy
                ON policy.tenant_id = locked_slot.tenant_id
               AND policy.venue_id = locked_slot.venue_id
             WHERE locked_slot.tenant_id = ?
               AND locked_slot.venue_id = ?
               AND locked_slot.resource_id = ?
               AND locked_slot.id = ?
             ORDER BY policy.policy_version DESC
             LIMIT 1
             FOR SHARE OF tenant, venue, policy
             FOR SHARE OF resource NOWAIT
            """, JdbcPromotionalReservationContextQuery::context,
            JdbcWaitlistDemandQuery.bytes(tenantId.value()),
            JdbcWaitlistDemandQuery.bytes(venueId.value()),
            JdbcWaitlistDemandQuery.bytes(resourceId.value()),
            JdbcWaitlistDemandQuery.bytes(slotInventoryId.value())
        ).stream().findFirst();
    }

    private static Context context(ResultSet row, int number) throws SQLException {
        TenantId tenantId = new TenantId(JdbcWaitlistDemandQuery.uuid(row.getBytes("tenant_id")));
        VenueId venueId = new VenueId(JdbcWaitlistDemandQuery.uuid(row.getBytes("venue_id")));
        ResourceId resourceId = new ResourceId(JdbcWaitlistDemandQuery.uuid(row.getBytes("resource_id")));
        Resource resource = new Resource(
            resourceId, tenantId, venueId,
            ResourceType.valueOf(row.getString("resource_type")),
            row.getString("resource_name"), row.getInt("seating_capacity"),
            ResourceStatus.valueOf(row.getString("resource_status"))
        );
        BookingPolicy policy = new BookingPolicy(
            row.getLong("policy_version"),
            new BookingPolicyTerms(
                row.getInt("slot_duration_minutes"),
                row.getInt("hold_duration_minutes"),
                row.getInt("cancellation_cutoff_minutes"),
                row.getInt("no_show_grace_minutes")
            ),
            row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)
        );
        return new Context(
            TenantStatus.valueOf(row.getString("tenant_status")),
            VenueStatus.valueOf(row.getString("venue_status")), resource, policy
        );
    }
}
