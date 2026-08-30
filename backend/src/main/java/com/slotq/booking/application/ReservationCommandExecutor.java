package com.slotq.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.ReservationState;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.venue.domain.VenueId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ReservationCommandExecutor {

    private final ReservationRepository reservationRepository;
    private final SlotInventoryRepository slotRepository;

    ReservationCommandExecutor(ReservationRepository reservationRepository,
                               SlotInventoryRepository slotRepository) {
        this.reservationRepository = reservationRepository;
        this.slotRepository = slotRepository;
    }

    @Transactional
    CommandResult execute(VenueId venueId, ReservationId reservationId,
                          ReservationCommand command, Instant now) {
        Reservation reservation = findReservation(venueId, reservationId);
        Clock commandClock = Clock.fixed(now, ZoneOffset.UTC);

        if (reservation.state() == targetState(command)) {
            return CommandResult.success(details(reservation, commandClock));
        }
        if (reservation.state() == ReservationState.EXPIRED) {
            return CommandResult.expired(details(reservation, commandClock));
        }
        if (reservation.state() == ReservationState.HELD && !now.isBefore(reservation.expiresAt())) {
            reservation.expire(commandClock);
            reservationRepository.save(reservation);
            return CommandResult.expired(details(reservation, commandClock));
        }

        validateAndApply(reservation, command, commandClock, now);
        reservationRepository.save(reservation);
        return CommandResult.success(details(reservation, commandClock));
    }

    @Transactional
    ReservationUseCase.ReservationDetails expire(VenueId venueId, ReservationId reservationId,
                                                  Instant now) {
        Reservation reservation = findReservation(venueId, reservationId);
        Clock commandClock = Clock.fixed(now, ZoneOffset.UTC);
        if (reservation.state() == ReservationState.EXPIRED) {
            return details(reservation, commandClock);
        }
        if (reservation.state() != ReservationState.HELD || now.isBefore(reservation.expiresAt())) {
            throw new ReservationTransitionNotAllowedException();
        }
        reservation.expire(commandClock);
        reservationRepository.save(reservation);
        return details(reservation, commandClock);
    }

    private void validateAndApply(Reservation reservation, ReservationCommand command,
                                  Clock commandClock, Instant now) {
        switch (command) {
            case CONFIRM -> {
                requireState(reservation, ReservationState.HELD);
                reservation.confirm(commandClock);
            }
            case CANCEL -> {
                if (reservation.state() != ReservationState.HELD
                    && reservation.state() != ReservationState.CONFIRMED) {
                    throw new ReservationTransitionNotAllowedException();
                }
                if (reservation.state() == ReservationState.CONFIRMED
                    && !now.isBefore(reservation.cancelAllowedUntil())) {
                    throw new CancellationWindowClosedException();
                }
                reservation.cancel(commandClock);
            }
            case CHECK_IN -> {
                requireState(reservation, ReservationState.CONFIRMED);
                if (now.isBefore(reservation.startsAt())
                    || !now.isBefore(reservation.noShowEligibleAt())) {
                    throw new ReservationTransitionNotAllowedException();
                }
                reservation.checkIn(commandClock);
            }
            case NO_SHOW -> {
                requireState(reservation, ReservationState.CONFIRMED);
                if (now.isBefore(reservation.noShowEligibleAt())) {
                    throw new ReservationTransitionNotAllowedException();
                }
                reservation.markNoShow(commandClock);
            }
            case COMPLETE -> {
                requireState(reservation, ReservationState.CHECKED_IN);
                reservation.complete();
            }
        }
    }

    private void requireState(Reservation reservation, ReservationState required) {
        if (reservation.state() != required) {
            throw new ReservationTransitionNotAllowedException();
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
        return reservationRepository.find(venueId, reservationId)
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
