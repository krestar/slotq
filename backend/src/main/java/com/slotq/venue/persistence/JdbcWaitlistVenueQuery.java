package com.slotq.venue.persistence;

import java.nio.ByteBuffer;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.application.WaitlistVenueQuery;
import com.slotq.venue.domain.VenueId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcWaitlistVenueQuery implements WaitlistVenueQuery {

    private final JdbcTemplate jdbc;

    JdbcWaitlistVenueQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<VenueScope> find(VenueId venueId) {
        return jdbc.query(
            "SELECT tenant_id, id, timezone FROM venues WHERE id = ?",
            (row, number) -> new VenueScope(
                new TenantId(uuid(row.getBytes("tenant_id"))),
                new VenueId(uuid(row.getBytes("id"))),
                ZoneId.of(row.getString("timezone"))
            ),
            bytes(venueId.value())
        ).stream().findFirst();
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
