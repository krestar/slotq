package com.slotq.booking.application;

import java.time.Instant;
import java.util.List;
import com.slotq.booking.domain.ReservationId;
import com.slotq.venue.domain.VenueId;

/** Nonlocking, bounded stored HELD keyset scan; expiry remains a target command. */
public interface BookingMaintenanceQuery {
    List<Held> heldAfter(ReservationId after, int limit);
    record Held(ReservationId id, VenueId venueId, Instant expiresAt) { }
}
