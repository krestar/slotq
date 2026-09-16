package com.slotq.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.ReservationState;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.domain.VenueId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ReservationCommandExecutor {

    private final ReservationRepository reservationRepository;
    private final SlotInventoryRepository slotRepository;
    private final ReservationTransitionPolicy transitionPolicy;
    private final ReservationTransitionRecorder recorder;

    ReservationCommandExecutor(ReservationRepository reservationRepository,
                               SlotInventoryRepository slotRepository,
                               ReservationTransitionPolicy transitionPolicy,
                               ReservationTransitionRecorder recorder) {
        this.reservationRepository = reservationRepository;
        this.slotRepository = slotRepository;
        this.transitionPolicy = transitionPolicy;
        this.recorder = recorder;
    }

    @Transactional
    CommandResult execute(VenueId venueId, ReservationId reservationId, SlotInventoryId slotInventoryId,
                          ReservationCommand command, Instant now) {
        // Slot must precede Reservation and every consistent read: HOLD uses the same capacity boundary.
        // The authorization read supplies only immutable routing identity; validate it against locked state.
        SlotInventory capacitySlot = command == ReservationCommand.CONFIRM
            ? slotRepository.findForUpdate(venueId, slotInventoryId)
                .orElseThrow(ResourceNotFoundException::new)
            : null;
        Reservation reservation = findReservation(venueId, reservationId);
        if (capacitySlot != null && (!capacitySlot.id().equals(reservation.slotInventoryId())
            || !capacitySlot.tenantId().equals(reservation.tenantId())
            || !capacitySlot.venueId().equals(reservation.venueId())
            || !capacitySlot.resourceId().equals(reservation.resourceId()))) {
            throw new ResourceNotFoundException();
        }
        Clock commandClock = Clock.fixed(now, ZoneOffset.UTC);
        var before = ReservationTransitionRecorder.Before.capture(reservation);

        if (reservation.state() == targetState(command)) {
            return CommandResult.success(details(reservation, commandClock));
        }
        if (reservation.state() == ReservationState.EXPIRED) {
            return CommandResult.expired(details(reservation, commandClock));
        }
        if (reservation.state() == ReservationState.HELD && !now.isBefore(reservation.expiresAt())) {
            reservation.expire(commandClock);
            recorder.save(reservation, before, now);
            return CommandResult.expired(details(reservation, commandClock));
        }

        validateAndApply(reservation, command, commandClock, now);
        recorder.save(reservation, before, now);
        return CommandResult.success(details(reservation, commandClock));
    }

    @Transactional
    ReservationUseCase.ReservationDetails expire(VenueId venueId, ReservationId reservationId,
                                                  Instant now) {
        Reservation reservation = findReservation(venueId, reservationId);
        var before = ReservationTransitionRecorder.Before.capture(reservation);
        Clock commandClock = Clock.fixed(now, ZoneOffset.UTC);
        if (reservation.state() == ReservationState.EXPIRED) {
            return details(reservation, commandClock);
        }
        if (reservation.state() != ReservationState.HELD || now.isBefore(reservation.expiresAt())) {
            throw new ReservationTransitionNotAllowedException();
        }
        reservation.expire(commandClock);
        recorder.save(reservation, before, now);
        return details(reservation, commandClock);
    }

    private void validateAndApply(Reservation reservation, ReservationCommand command,
                                  Clock commandClock, Instant now) {
        transitionPolicy.requireAllowed(reservation, command, now);
        if (command == ReservationCommand.CONFIRM
            && reservationRepository.existsOtherEffectiveCapacityConsumer(
                reservation.tenantId(), reservation.venueId(), reservation.resourceId(),
                reservation.slotInventoryId(), reservation.id(), now
            )) {
            throw new CapacityUnavailableException();
        }
        switch (command) {
            case CONFIRM -> reservation.confirm(commandClock);
            case CANCEL -> reservation.cancel(commandClock);
            case CHECK_IN -> reservation.checkIn(commandClock);
            case NO_SHOW -> reservation.markNoShow(commandClock);
            case COMPLETE -> reservation.complete();
        }
    }

    private ReservationState targetState(ReservationCommand command) {
        return switch (command) {
            case CONFIRM -> ReservationState.CONFIRMED;
            case CANCEL -> ReservationState.CANCELLED;
            case CHECK_IN -> ReservationState.CHECKED_IN;
            case NO_SHOW -> ReservationState.NO_SHOW;
            case COMPLETE -> ReservationState.COMPLETED;
        };
    }

    private Reservation findReservation(VenueId venueId, ReservationId reservationId) {
        return reservationRepository.findForUpdate(venueId, reservationId)
            .orElseThrow(ResourceNotFoundException::new);
    }

    private ReservationUseCase.ReservationDetails details(Reservation reservation, Clock clock) {
        SlotInventory slot = slotRepository.find(reservation.venueId(), reservation.slotInventoryId())
            .filter(found -> found.tenantId().equals(reservation.tenantId())
                && found.resourceId().equals(reservation.resourceId()))
            .orElseThrow(ResourceNotFoundException::new);
        return new ReservationUseCase.ReservationDetails(
            reservation, slot.endsAt(), reservation.effectiveState(clock)
        );
    }

    record CommandResult(ReservationUseCase.ReservationDetails details, boolean expired) {
        static CommandResult success(ReservationUseCase.ReservationDetails details) {
            return new CommandResult(details, false);
        }

        static CommandResult expired(ReservationUseCase.ReservationDetails details) {
            return new CommandResult(details, true);
        }
    }
}
