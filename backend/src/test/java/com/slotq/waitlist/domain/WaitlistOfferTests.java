package com.slotq.waitlist.domain;

import java.time.Instant;
import java.util.UUID;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitlistOfferTests {

    @Test
    void pendingOfferTransitionsOnceWithTheContractedReason() {
        WaitlistOffer accepted = offer();
        accepted.accept();
        accepted.accept();
        assertThat(accepted.state()).isEqualTo(WaitlistOfferState.ACCEPTED);
        assertThat(accepted.terminalReason()).isNull();
        assertThatThrownBy(() -> accepted.decline(WaitlistOfferTerminalReason.CUSTOMER_DECLINED))
            .isInstanceOf(IllegalStateException.class);

        WaitlistOffer declined = offer();
        declined.decline(WaitlistOfferTerminalReason.ENTRY_CANCELLED);
        declined.decline(WaitlistOfferTerminalReason.CUSTOMER_DECLINED);
        assertThat(declined.state()).isEqualTo(WaitlistOfferState.DECLINED);
        assertThat(declined.terminalReason()).isEqualTo(WaitlistOfferTerminalReason.ENTRY_CANCELLED);

        WaitlistOffer expired = offer();
        expired.expire();
        assertThat(expired.state()).isEqualTo(WaitlistOfferState.EXPIRED);
        assertThat(expired.terminalReason()).isEqualTo(WaitlistOfferTerminalReason.HOLD_EXPIRED);
    }

    @Test
    void entryOfferLifecycleDoesNotReopenTerminalState() {
        WaitlistEntry entry = entry();
        entry.offer();
        entry.fulfill();
        entry.fulfill();
        assertThat(entry.state()).isEqualTo(WaitlistEntryState.FULFILLED);
        assertThatThrownBy(entry::offer).isInstanceOf(IllegalStateException.class);
    }

    private WaitlistOffer offer() {
        WaitlistEntry entry = entry();
        return WaitlistOffer.pending(
            WaitlistOfferId.newId(), entry, new ResourceId(UUID.randomUUID()),
            new SlotInventoryId(UUID.randomUUID()), ReservationId.newId(),
            Instant.parse("2026-09-14T01:00:00Z")
        );
    }

    private WaitlistEntry entry() {
        TenantId tenantId = new TenantId(UUID.randomUUID());
        VenueId venueId = new VenueId(UUID.randomUUID());
        WaitlistDemand demand = new WaitlistDemand(
            WaitlistDemandId.newId(), tenantId, venueId,
            Instant.parse("2026-09-14T10:00:00Z"),
            Instant.parse("2026-09-14T11:00:00Z"), 2
        );
        return WaitlistEntry.join(
            WaitlistEntryId.newId(), new PrincipalId(UUID.randomUUID()), demand,
            Instant.parse("2026-09-14T00:00:00Z")
        );
    }
}
