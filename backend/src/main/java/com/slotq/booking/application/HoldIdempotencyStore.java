package com.slotq.booking.application;

import java.time.Instant;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;

public interface HoldIdempotencyStore {

    Claim claim(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        HoldIdempotencyKey key,
        Fingerprint fingerprint,
        Instant startedAt,
        Instant retentionCutoff
    );

    void complete(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        HoldIdempotencyKey key,
        ReservationId reservationId,
        Instant completedAt
    );

    int deleteCompletedAtOrBefore(Instant retentionCutoff, int batchSize);

    record Fingerprint(VenueId venueId, SlotInventoryId slotInventoryId, int partySize) { }

    record Claim(boolean owner, Fingerprint fingerprint, ReservationId reservationId) { }
}
