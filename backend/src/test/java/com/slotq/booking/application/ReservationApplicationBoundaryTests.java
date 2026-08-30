package com.slotq.booking.application;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationApplicationBoundaryTests {

    @Test
    void createHoldAcceptsAuthenticatedPrincipalInsteadOfRawPrincipalId() {
        assertThat(Arrays.stream(ReservationUseCase.CreateHold.class.getRecordComponents())
            .map(RecordComponent::getType))
            .contains(AuthenticatedPrincipal.class)
            .doesNotContain(PrincipalId.class);
    }
}
