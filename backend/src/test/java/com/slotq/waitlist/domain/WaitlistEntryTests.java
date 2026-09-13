package com.slotq.waitlist.domain;

import java.time.Instant;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitlistEntryTests {

    @Test
    void waitingExpiresEffectivelyAtStartWithoutMutatingStoredState() {
        Instant startsAt = Instant.parse("2030-01-01T10:00:00Z");
        WaitlistEntry entry = entry(startsAt);

        assertThat(entry.effectiveState(startsAt.minusNanos(1)))
            .isEqualTo(WaitlistEntryState.WAITING);
        assertThat(entry.effectiveState(startsAt)).isEqualTo(WaitlistEntryState.EXPIRED);
        assertThat(entry.state()).isEqualTo(WaitlistEntryState.WAITING);
        assertThatThrownBy(() -> entry.cancel(startsAt)).isInstanceOf(IllegalStateException.class);
        assertThat(entry.state()).isEqualTo(WaitlistEntryState.WAITING);
    }

    @Test
    void waitingCancelIsIdempotentAndTerminal() {
        Instant startsAt = Instant.parse("2030-01-01T10:00:00Z");
        WaitlistEntry entry = entry(startsAt);
        Instant commandNow = startsAt.minusSeconds(1);

        entry.cancel(commandNow);
        entry.cancel(commandNow);

        assertThat(entry.state()).isEqualTo(WaitlistEntryState.CANCELLED);
        assertThatThrownBy(() -> new WaitlistEntry(
            entry.id(), entry.tenantId(), new VenueId(java.util.UUID.randomUUID()),
            entry.customerPrincipalId(), entry.demand(), entry.joinedAt(), entry.state()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private WaitlistEntry entry(Instant startsAt) {
        TenantId tenantId = TenantId.newId();
        VenueId venueId = VenueId.newId();
        WaitlistDemand demand = new WaitlistDemand(
            WaitlistDemandId.newId(), tenantId, venueId,
            startsAt, startsAt.plusSeconds(1800), 2
        );
        return WaitlistEntry.join(
            WaitlistEntryId.newId(), PrincipalId.newId(), demand, startsAt.minusSeconds(3600)
        );
    }
}
