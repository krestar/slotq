package com.slotq.waitlist.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.WaitlistOfferRepository;
import com.slotq.waitlist.domain.WaitlistDemandId;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistOffer;
import com.slotq.waitlist.domain.WaitlistOfferId;
import com.slotq.waitlist.domain.WaitlistOfferState;
import com.slotq.waitlist.domain.WaitlistOfferTerminalReason;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcWaitlistOfferRepository implements WaitlistOfferRepository {
    private static final String SELECT = """
        SELECT id, tenant_id, venue_id, resource_id, slot_inventory_id,
               customer_principal_id, demand_id, entry_id, reservation_id,
               state, terminal_reason, expires_at
          FROM waitlist_offers
        """;
    private final JdbcTemplate jdbc;

    JdbcWaitlistOfferRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void create(VenueId venueId, WaitlistOffer offer) {
        requireVenue(venueId, offer);
        jdbc.update("""
            INSERT INTO waitlist_offers (
                id, tenant_id, venue_id, resource_id, slot_inventory_id,
                customer_principal_id, demand_id, entry_id, reservation_id,
                state, terminal_reason, expires_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, bytes(offer.id().value()), bytes(offer.tenantId().value()),
            bytes(offer.venueId().value()), bytes(offer.resourceId().value()),
            bytes(offer.slotInventoryId().value()), bytes(offer.customerPrincipalId().value()),
            bytes(offer.demandId().value()), bytes(offer.entryId().value()),
            bytes(offer.reservationId().value()), offer.state().name(), null,
            Timestamp.from(offer.expiresAt()));
    }

    @Override
    public void update(VenueId venueId, WaitlistOffer offer) {
        requireVenue(venueId, offer);
        int updated = jdbc.update("""
            UPDATE waitlist_offers SET state = ?, terminal_reason = ?
             WHERE tenant_id = ? AND venue_id = ? AND id = ?
            """, offer.state().name(),
            offer.terminalReason() == null ? null : offer.terminalReason().name(),
            bytes(offer.tenantId().value()), bytes(offer.venueId().value()), bytes(offer.id().value()));
        if (updated != 1) throw new IllegalStateException("Offer update did not affect one row");
    }

    @Override
    public Optional<WaitlistOffer> find(VenueId venueId, WaitlistOfferId offerId) {
        return one(" WHERE venue_id = ? AND id = ?", bytes(venueId.value()), bytes(offerId.value()));
    }

    @Override
    public Optional<WaitlistOffer> findOwned(
        VenueId venueId, PrincipalId customerId, WaitlistOfferId offerId
    ) {
        return one(" WHERE venue_id = ? AND customer_principal_id = ? AND id = ?",
            bytes(venueId.value()), bytes(customerId.value()), bytes(offerId.value()));
    }

    @Override
    public Optional<WaitlistOffer> findByEntry(VenueId venueId, WaitlistEntryId entryId) {
        return one(" WHERE venue_id = ? AND entry_id = ?", bytes(venueId.value()), bytes(entryId.value()));
    }

    @Override
    public Optional<WaitlistOffer> findByEntryForUpdate(VenueId venueId, WaitlistEntryId entryId) {
        return one(" WHERE venue_id = ? AND entry_id = ? FOR UPDATE",
            bytes(venueId.value()), bytes(entryId.value()));
    }

    @Override
    public Optional<WaitlistOffer> findOwnedForUpdate(
        VenueId venueId, PrincipalId customerId, WaitlistOfferId offerId
    ) {
        return one(" WHERE venue_id = ? AND customer_principal_id = ? AND id = ? FOR UPDATE",
            bytes(venueId.value()), bytes(customerId.value()), bytes(offerId.value()));
    }

    private Optional<WaitlistOffer> one(String where, Object... args) {
        return jdbc.query(SELECT + where, JdbcWaitlistOfferRepository::offer, args).stream().findFirst();
    }

    private static WaitlistOffer offer(ResultSet row, int number) throws SQLException {
        String reason = row.getString("terminal_reason");
        return new WaitlistOffer(
            new WaitlistOfferId(uuid(row.getBytes("id"))),
            new TenantId(uuid(row.getBytes("tenant_id"))),
            new VenueId(uuid(row.getBytes("venue_id"))),
            new ResourceId(uuid(row.getBytes("resource_id"))),
            new SlotInventoryId(uuid(row.getBytes("slot_inventory_id"))),
            new PrincipalId(uuid(row.getBytes("customer_principal_id"))),
            new WaitlistDemandId(uuid(row.getBytes("demand_id"))),
            new WaitlistEntryId(uuid(row.getBytes("entry_id"))),
            new ReservationId(uuid(row.getBytes("reservation_id"))),
            row.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            WaitlistOfferState.valueOf(row.getString("state")),
            reason == null ? null : WaitlistOfferTerminalReason.valueOf(reason)
        );
    }

    private static byte[] bytes(java.util.UUID value) { return JdbcWaitlistDemandStore.bytes(value); }
    private static java.util.UUID uuid(byte[] value) { return JdbcWaitlistDemandStore.uuid(value); }

    private void requireVenue(VenueId venueId, WaitlistOffer offer) {
        if (!venueId.equals(offer.venueId())) {
            throw new IllegalArgumentException("Offer venue scope does not match");
        }
    }
}
