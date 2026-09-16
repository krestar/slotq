package com.slotq.booking.application;

import java.time.Instant;

import com.slotq.booking.domain.CapacityAllocation;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.ReservationState;
import com.slotq.events.application.EventAppendService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReservationTransitionRecorderTests {
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final SlotInventoryRepository slots = mock(SlotInventoryRepository.class);
    private final EventAppendService events = mock(EventAppendService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withBean(ReservationRepository.class, () -> reservations)
        .withBean(SlotInventoryRepository.class, () -> slots)
        .withBean(EventAppendService.class, () -> events)
        .withUserConfiguration(ReservationTransitionRecorder.class);

    @Test
    void missingConfigurationIsDisabledAndDoesNotRequireReadinessOrRegistration() {
        Reservation reservation = released();
        context.run(application -> {
            application.getBean(ReservationTransitionRecorder.class).save(reservation,
                new ReservationTransitionRecorder.Before(reservation.id(), ReservationState.HELD, true), Instant.now());
            verify(reservations).save(reservation);
            verifyNoInteractions(events, slots);
        });
    }

    @Test
    void enabledWithoutAReadinessProviderFailsClosedRatherThanOpeningByConfigurationAlone() {
        Reservation reservation = released();
        context.withPropertyValues("slotq.waitlist.promotion.enabled=true").run(application -> {
            assertThatThrownBy(() -> application.getBean(ReservationTransitionRecorder.class).save(reservation,
                new ReservationTransitionRecorder.Before(reservation.id(), ReservationState.HELD, true), Instant.now()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not ready");
            verifyNoInteractions(events, slots);
        });
    }

    @Test
    void inactiveAllocationDoesNotPublishEvenIfStateNamesDiffer() {
        Reservation reservation = released();
        context.withPropertyValues("slotq.waitlist.promotion.enabled=true").run(application -> {
            application.getBean(ReservationTransitionRecorder.class).save(reservation,
                new ReservationTransitionRecorder.Before(reservation.id(), ReservationState.EXPIRED, false), Instant.now());
            verify(reservations).save(reservation);
            verifyNoInteractions(events, slots);
        });
    }

    private Reservation released() {
        Reservation reservation = mock(Reservation.class);
        CapacityAllocation allocation = mock(CapacityAllocation.class);
        when(reservation.id()).thenReturn(ReservationId.newId());
        when(reservation.state()).thenReturn(ReservationState.CANCELLED);
        when(reservation.allocation()).thenReturn(allocation);
        when(allocation.active()).thenReturn(false);
        return reservation;
    }
}
