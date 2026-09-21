package com.slotq.waitlist.persistence;

import java.sql.Timestamp;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.waitlist.application.WaitlistNotificationStore;
import com.slotq.waitlist.domain.WaitlistOffer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static com.slotq.waitlist.persistence.JdbcWaitlistDemandStore.bytes;

@Component
class JdbcWaitlistNotificationStore implements WaitlistNotificationStore {
    private final JdbcTemplate jdbc;
    JdbcWaitlistNotificationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override
    public void offerAvailable(TenantId tenantId, WaitlistOffer offer) {
        if (!tenantId.equals(offer.tenantId())) throw new IllegalArgumentException("Notification tenant does not match");
        jdbc.update("""
            INSERT INTO waitlist_notification_requests
                (tenant_id, offer_id, request_type, venue_id, entry_id, reservation_id, expires_at)
            VALUES (?, ?, 'OFFER_AVAILABLE', ?, ?, ?, ?)
            """, bytes(tenantId.value()), bytes(offer.id().value()), bytes(offer.venueId().value()),
            bytes(offer.entryId().value()), bytes(offer.reservationId().value()), Timestamp.from(offer.expiresAt()));
    }
}
