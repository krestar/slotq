package com.slotq.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationState;
import org.springframework.stereotype.Component;

@Component
public class ReservationTransitionPolicy {

    public boolean isAllowed(Reservation reservation, ReservationCommand command, Instant now) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(now, "now must not be null");
        ReservationState state = reservation.effectiveState(Clock.fixed(now, ZoneOffset.UTC));
        return switch (command) {
            case CONFIRM -> state == ReservationState.HELD;
            case CANCEL -> state == ReservationState.HELD
                || (state == ReservationState.CONFIRMED
                    && now.isBefore(reservation.cancelAllowedUntil()));
            case CHECK_IN -> state == ReservationState.CONFIRMED
                && !now.isBefore(reservation.startsAt())
                && now.isBefore(reservation.noShowEligibleAt());
            case NO_SHOW -> state == ReservationState.CONFIRMED
                && !now.isBefore(reservation.noShowEligibleAt());
            case COMPLETE -> state == ReservationState.CHECKED_IN;
        };
    }

    void requireAllowed(Reservation reservation, ReservationCommand command, Instant now) {
        if (isAllowed(reservation, command, now)) {
            return;
        }
        if (command == ReservationCommand.CANCEL
            && reservation.state() == ReservationState.CONFIRMED
            && !now.isBefore(reservation.cancelAllowedUntil())) {
            throw new CancellationWindowClosedException();
        }
        throw new ReservationTransitionNotAllowedException();
    }
}
