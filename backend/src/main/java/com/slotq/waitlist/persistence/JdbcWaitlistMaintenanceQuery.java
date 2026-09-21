package com.slotq.waitlist.persistence;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.WaitlistMaintenanceQuery;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistOfferId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcWaitlistMaintenanceQuery implements WaitlistMaintenanceQuery {
    private final JdbcTemplate jdbc;
    JdbcWaitlistMaintenanceQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<Pending> pendingAfter(WaitlistOfferId after, int limit) {
        validate(limit);
        return jdbc.query("""
            SELECT id, venue_id FROM waitlist_offers
             WHERE state = 'PENDING' AND id > ? ORDER BY id LIMIT ?
            """, (row, n) -> new Pending(new WaitlistOfferId(JdbcWaitlistDemandStore.uuid(row.getBytes("id"))),
                new VenueId(JdbcWaitlistDemandStore.uuid(row.getBytes("venue_id")))),
            after == null ? new byte[0] : JdbcWaitlistDemandStore.bytes(after.value()), limit);
    }
    @Override public List<Waiting> waitingAfter(WaitlistEntryId after, int limit) {
        validate(limit);
        // Bound Entry enumeration before the immutable Demand join, including future rows.
        return jdbc.query("""
            SELECT entry.id, entry.venue_id, demand.starts_at
              FROM (SELECT id, tenant_id, venue_id, demand_id FROM waitlist_entries
                     WHERE state = 'WAITING' AND id > ? ORDER BY id LIMIT ?) entry
              JOIN waitlist_demands demand ON demand.id = entry.demand_id
               AND demand.tenant_id = entry.tenant_id AND demand.venue_id = entry.venue_id
             ORDER BY entry.id
            """, (row, n) -> new Waiting(new WaitlistEntryId(JdbcWaitlistDemandStore.uuid(row.getBytes("id"))),
                new VenueId(JdbcWaitlistDemandStore.uuid(row.getBytes("venue_id"))),
                row.getObject("starts_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
            after == null ? new byte[0] : JdbcWaitlistDemandStore.bytes(after.value()), limit);
    }
    private static void validate(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid maintenance batch");
    }
}
