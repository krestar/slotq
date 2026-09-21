package com.slotq.waitlist.application;

import java.time.Instant;
import java.util.UUID;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.domain.ReservationState;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;

/** Event-neutral command/value boundary; callers must join the Product effect transaction. */
public interface WaitlistPromotionUseCase {
    String CONSUMER = "waitlist.promotion";
    Result promote(SystemPrincipal principal, Command command);

    enum Signal { CAPACITY_RELEASED, PROMOTION_REQUESTED }
    enum Outcome { PROMOTED, NO_CAPACITY, NO_CANDIDATE, NOT_ELIGIBLE, SLOT_PAST, DEFERRED }

    // V1 meaning is typed and complete: the integration adapter owns route/version/JSON decoding.
    record Command(TenantId tenantId, UUID eventId, Signal signal, UUID sourceId, Instant occurredAt,
                   VenueId venueId, ResourceId resourceId, SlotInventoryId slotInventoryId,
                   ReservationState fromState, ReservationState toState) { }
    record Result(Outcome outcome, UUID offerId, UUID entryId, UUID reservationId) { }
}
