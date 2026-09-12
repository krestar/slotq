package com.slotq.booking.application;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Optional;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.SystemPrincipal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationApplicationBoundaryTests {

    @Test
    void createHoldAcceptsAuthenticatedPrincipalInsteadOfRawPrincipalId() {
        assertThat(Arrays.stream(ReservationUseCase.CreateHold.class.getRecordComponents())
            .map(RecordComponent::getType))
            .contains(AuthenticatedPrincipal.class)
            .doesNotContain(PrincipalId.class);
        assertThat(Arrays.stream(ReservationUseCase.CreateHold.class.getRecordComponents())
            .filter(component -> component.getName().equals("idempotencyKey"))
            .map(RecordComponent::getType))
            .containsExactly(Optional.class);
    }

    @Test
    void internalExpiryRequiresSystemPrincipal() throws NoSuchMethodException {
        assertThat(ReservationExpiryUseCase.class.getMethod(
            "expire",
            com.slotq.venue.domain.VenueId.class,
            com.slotq.booking.domain.ReservationId.class,
            SystemPrincipal.class
        )).isNotNull();
    }

    @Test
    void concurrencyLocksRemainScopedToOneSlotOrReservation() throws NoSuchMethodException {
        assertThat(SlotInventoryRepository.class.getMethod(
            "findForUpdate",
            com.slotq.venue.domain.VenueId.class,
            com.slotq.booking.domain.SlotInventoryId.class
        ).getReturnType()).isEqualTo(Optional.class);
        assertThat(ReservationRepository.class.getMethod(
            "findForUpdate",
            com.slotq.venue.domain.VenueId.class,
            com.slotq.booking.domain.ReservationId.class
        ).getReturnType()).isEqualTo(Optional.class);
    }
}
