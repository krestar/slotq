package com.slotq.booking.persistence;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.slotq.booking.application.BookingMaintenanceQuery;
import com.slotq.booking.domain.ReservationId;
import com.slotq.venue.domain.VenueId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcBookingMaintenanceQuery implements BookingMaintenanceQuery {
    private final JdbcTemplate jdbc;
    JdbcBookingMaintenanceQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<Held> heldAfter(ReservationId after, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid maintenance batch");
        return jdbc.query("""
            SELECT id, venue_id, expires_at FROM reservations
             WHERE state = 'HELD' AND id > ? ORDER BY id LIMIT ?
            """, (row, n) -> new Held(new ReservationId(uuid(row.getBytes("id"))),
                new VenueId(uuid(row.getBytes("venue_id"))),
                row.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
            after == null ? new byte[0] : bytes(after.value()), limit);
    }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
