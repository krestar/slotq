package com.slotq.waitlist.persistence;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.WaitlistDemandStore;
import com.slotq.waitlist.domain.WaitlistDemand;
import com.slotq.waitlist.domain.WaitlistDemandId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcWaitlistDemandStore implements WaitlistDemandStore {

    private final JdbcTemplate jdbc;

    JdbcWaitlistDemandStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WaitlistDemand lockOrCreate(WaitlistDemand.Identity identity) {
        WaitlistDemandId proposedId = WaitlistDemandId.newId();
        jdbc.update("""
            INSERT INTO waitlist_demands (
                id, tenant_id, venue_id, starts_at, ends_at, party_size
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE id = id
            """,
            bytes(proposedId.value()), bytes(identity.tenantId().value()),
            bytes(identity.venueId().value()), Timestamp.from(identity.startsAt()),
            Timestamp.from(identity.endsAt()), identity.partySize()
        );
        return selectForUpdate(identity);
    }

    @Override
    public void lock(WaitlistDemand demand) {
        WaitlistDemand locked = selectForUpdate(new WaitlistDemand.Identity(
            demand.tenantId(), demand.venueId(), demand.startsAt(), demand.endsAt(), demand.partySize()
        ));
        if (!locked.id().equals(demand.id())) {
            throw new IllegalStateException("Waitlist demand identity changed");
        }
    }

    private WaitlistDemand selectForUpdate(WaitlistDemand.Identity identity) {
        return jdbc.queryForObject("""
            SELECT id, tenant_id, venue_id, starts_at, ends_at, party_size
              FROM waitlist_demands
             WHERE tenant_id = ? AND venue_id = ?
               AND starts_at = ? AND ends_at = ? AND party_size = ?
             FOR UPDATE
            """, JdbcWaitlistDemandStore::demand,
            bytes(identity.tenantId().value()), bytes(identity.venueId().value()),
            Timestamp.from(identity.startsAt()), Timestamp.from(identity.endsAt()), identity.partySize());
    }

    private static WaitlistDemand demand(ResultSet row, int number) throws SQLException {
        return new WaitlistDemand(
            new WaitlistDemandId(uuid(row.getBytes("id"))),
            new TenantId(uuid(row.getBytes("tenant_id"))),
            new VenueId(uuid(row.getBytes("venue_id"))),
            row.getObject("starts_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            row.getObject("ends_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            row.getInt("party_size")
        );
    }

    static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits()).array();
    }

    static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
