package com.slotq.waitlist.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.WaitlistRegistrationKey;
import com.slotq.waitlist.application.WaitlistRegistrationStore;
import com.slotq.waitlist.domain.WaitlistEntryId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcWaitlistRegistrationStore implements WaitlistRegistrationStore {

    private final JdbcTemplate jdbc;

    JdbcWaitlistRegistrationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Claim claim(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        WaitlistRegistrationKey key,
        Fingerprint fingerprint,
        Instant startedAt
    ) {
        UUID claimId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO waitlist_registration_requests (
                tenant_id, customer_principal_id, idempotency_key, claim_id,
                venue_id, resource_id, slot_inventory_id, party_size, state, started_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?)
            ON DUPLICATE KEY UPDATE claim_id = claim_id
            """,
            JdbcWaitlistDemandStore.bytes(tenantId.value()),
            JdbcWaitlistDemandStore.bytes(customerPrincipalId.value()),
            JdbcWaitlistDemandStore.bytes(key.value()),
            JdbcWaitlistDemandStore.bytes(claimId),
            JdbcWaitlistDemandStore.bytes(fingerprint.venueId().value()),
            JdbcWaitlistDemandStore.bytes(fingerprint.originalResourceId().value()),
            JdbcWaitlistDemandStore.bytes(fingerprint.slotInventoryId().value()),
            fingerprint.partySize(), Timestamp.from(startedAt)
        );
        Stored stored = lock(tenantId, customerPrincipalId, key);
        return new Claim(
            claimId.equals(stored.claimId()), stored.fingerprint(), stored.entryId(), stored.originalStatus()
        );
    }

    @Override
    public void complete(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        WaitlistRegistrationKey key,
        WaitlistEntryId entryId,
        int originalStatus,
        Instant completedAt
    ) {
        int updated = jdbc.update("""
            UPDATE waitlist_registration_requests
               SET state = 'COMPLETED', entry_id = ?, original_status = ?, completed_at = ?
             WHERE tenant_id = ? AND customer_principal_id = ? AND idempotency_key = ?
               AND state = 'IN_PROGRESS'
            """,
            JdbcWaitlistDemandStore.bytes(entryId.value()), originalStatus, Timestamp.from(completedAt),
            JdbcWaitlistDemandStore.bytes(tenantId.value()),
            JdbcWaitlistDemandStore.bytes(customerPrincipalId.value()),
            JdbcWaitlistDemandStore.bytes(key.value())
        );
        if (updated != 1) {
            throw new IllegalStateException("Waitlist registration claim is not in progress");
        }
    }

    @Override
    public Optional<Result> find(
        VenueId venueId,
        PrincipalId customerPrincipalId,
        WaitlistRegistrationKey key
    ) {
        return jdbc.query("""
            SELECT entry_id, original_status
              FROM waitlist_registration_requests
             WHERE venue_id = ? AND customer_principal_id = ? AND idempotency_key = ?
               AND state = 'COMPLETED'
            """, (row, number) -> new Result(
                new WaitlistEntryId(JdbcWaitlistDemandStore.uuid(row.getBytes("entry_id"))),
                row.getInt("original_status")
            ), JdbcWaitlistDemandStore.bytes(venueId.value()),
            JdbcWaitlistDemandStore.bytes(customerPrincipalId.value()),
            JdbcWaitlistDemandStore.bytes(key.value())).stream().findFirst();
    }

    private Stored lock(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        WaitlistRegistrationKey key
    ) {
        return jdbc.queryForObject("""
            SELECT claim_id, venue_id, resource_id, slot_inventory_id, party_size,
                   state, entry_id, original_status
              FROM waitlist_registration_requests
             WHERE tenant_id = ? AND customer_principal_id = ? AND idempotency_key = ?
             FOR UPDATE
            """, JdbcWaitlistRegistrationStore::stored,
            JdbcWaitlistDemandStore.bytes(tenantId.value()),
            JdbcWaitlistDemandStore.bytes(customerPrincipalId.value()),
            JdbcWaitlistDemandStore.bytes(key.value()));
    }

    private static Stored stored(ResultSet row, int number) throws SQLException {
        byte[] entry = row.getBytes("entry_id");
        Integer originalStatus = (Integer) row.getObject("original_status");
        String state = row.getString("state");
        if ("COMPLETED".equals(state) != (entry != null && originalStatus != null)) {
            throw new IllegalStateException("Waitlist registration result is inconsistent");
        }
        return new Stored(
            JdbcWaitlistDemandStore.uuid(row.getBytes("claim_id")),
            new Fingerprint(
                new VenueId(JdbcWaitlistDemandStore.uuid(row.getBytes("venue_id"))),
                new ResourceId(JdbcWaitlistDemandStore.uuid(row.getBytes("resource_id"))),
                new SlotInventoryId(JdbcWaitlistDemandStore.uuid(row.getBytes("slot_inventory_id"))),
                row.getInt("party_size")
            ),
            entry == null ? null : new WaitlistEntryId(JdbcWaitlistDemandStore.uuid(entry)),
            originalStatus
        );
    }

    private record Stored(
        UUID claimId,
        Fingerprint fingerprint,
        WaitlistEntryId entryId,
        Integer originalStatus
    ) { }
}
