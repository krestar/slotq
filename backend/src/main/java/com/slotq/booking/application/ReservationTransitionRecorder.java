package com.slotq.booking.application;

import java.time.Instant;
import java.util.Optional;

import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.ReservationState;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.EventAppendService;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Saves a locked Booking transition and records actual allocation release in its caller transaction. */
@Component
class ReservationTransitionRecorder {
    private static final ConsumerRoute ROUTE = new ConsumerRoute(
        "waitlist.promotion", "booking.capacity-released", 1
    );

    private final ReservationRepository reservations;
    private final SlotInventoryRepository slots;
    private final EventAppendService events;
    private final boolean enabled;
    private final Optional<CapacityReleaseReadiness> readiness;

    ReservationTransitionRecorder(
        ReservationRepository reservations, SlotInventoryRepository slots, EventAppendService events,
        @Value("${slotq.waitlist.promotion.enabled:false}") boolean enabled,
        Optional<CapacityReleaseReadiness> readiness
    ) {
        this.reservations = reservations;
        this.slots = slots;
        this.events = events;
        this.enabled = enabled;
        this.readiness = readiness;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Reservation reservation, Before before, Instant commandNow) {
        if (!reservation.id().equals(before.id())) {
            throw new IllegalArgumentException("transition identity does not match");
        }
        // The adapter flushes Reservation and Allocation together before JDBC event append.
        reservations.save(reservation);
        if (!before.active() || reservation.allocation().active() || !enabled) {
            return;
        }
        if (!readiness.map(CapacityReleaseReadiness::isReady).orElse(false)) {
            throw new IllegalStateException("waitlist promotion producer is not ready");
        }
        // Immutable routing only: do not introduce a Slot lock after the Reservation lock.
        // The locked Booking reconstruction already validates Allocation ownership.
        slots.find(reservation.venueId(), reservation.slotInventoryId())
            .filter(slot -> slot.id().equals(reservation.slotInventoryId())
                && slot.tenantId().equals(reservation.tenantId())
                && slot.venueId().equals(reservation.venueId())
                && slot.resourceId().equals(reservation.resourceId()))
            .orElseThrow(() -> new IllegalStateException("capacity release Slot scope does not match"));
        String payload = """
            {"venueId":"%s","resourceId":"%s","slotInventoryId":"%s","fromState":"%s","toState":"%s"}
            """.formatted(reservation.venueId().value(), reservation.resourceId().value(),
                reservation.slotInventoryId().value(), before.state().name(), reservation.state().name());
        events.appendForActiveRoute(new EventEnvelope(
            EventId.newId(), reservation.tenantId(), "Reservation", reservation.id().value(),
            ROUTE.eventType(), ROUTE.schemaVersion(), commandNow, payload
        ), ROUTE);
    }

    record Before(ReservationId id, ReservationState state, boolean active) {
        static Before capture(Reservation reservation) {
            return new Before(reservation.id(), reservation.state(), reservation.allocation().active());
        }
    }
}
