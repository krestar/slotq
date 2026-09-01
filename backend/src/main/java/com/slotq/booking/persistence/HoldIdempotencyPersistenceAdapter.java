package com.slotq.booking.persistence;

import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.booking.application.HoldIdempotencyKey;
import com.slotq.booking.application.HoldIdempotencyStore;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class HoldIdempotencyPersistenceAdapter implements HoldIdempotencyStore {

    private static final String INSERT = """
        INSERT INTO hold_idempotency_records (
            tenant_id, customer_principal_id, idempotency_key,
            venue_id, slot_inventory_id, party_size, state, started_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    HoldIdempotencyPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Claim claim(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        HoldIdempotencyKey key,
        Fingerprint fingerprint,
        Instant startedAt,
        Instant retentionCutoff
    ) {
        try {
            insert(tenantId, customerPrincipalId, key, fingerprint, startedAt);
            return new Claim(true, fingerprint, null);
        } catch (DuplicateKeyException duplicate) {
            StoredRecord existing = lock(tenantId, customerPrincipalId, key);
            if (existing.completedAt() != null
                && !existing.completedAt().isAfter(retentionCutoff)) {
                deleteExpired(tenantId, customerPrincipalId, key, retentionCutoff);
                insert(tenantId, customerPrincipalId, key, fingerprint, startedAt);
                return new Claim(true, fingerprint, null);
            }
            return new Claim(false, existing.fingerprint(), existing.reservationId());
        }
    }

    @Override
    public void complete(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        HoldIdempotencyKey key,
        ReservationId reservationId,
        Instant completedAt
    ) {
        int updated = jdbcTemplate.update("""
            UPDATE hold_idempotency_records
               SET state = 'COMPLETED', reservation_id = ?, completed_at = ?
             WHERE tenant_id = ?
               AND customer_principal_id = ?
               AND idempotency_key = ?
               AND state = 'IN_PROGRESS'
            """,
            bytes(reservationId.value()), Timestamp.from(completedAt), bytes(tenantId.value()),
            bytes(customerPrincipalId.value()), key.value()
        );
        if (updated != 1) {
            throw new IllegalStateException("HOLD idempotency claim is not in progress");
        }
    }

    @Override
    public int deleteCompletedAtOrBefore(Instant retentionCutoff, int batchSize) {
        return jdbcTemplate.update("""
            DELETE FROM hold_idempotency_records
             WHERE state = 'COMPLETED'
               AND completed_at <= ?
             ORDER BY completed_at
             LIMIT ?
            """, Timestamp.from(retentionCutoff), batchSize);
    }

    private void insert(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        HoldIdempotencyKey key,
        Fingerprint fingerprint,
        Instant startedAt
    ) {
        jdbcTemplate.update(
            INSERT,
            bytes(tenantId.value()), bytes(customerPrincipalId.value()), key.value(),
            bytes(fingerprint.venueId().value()), bytes(fingerprint.slotInventoryId().value()),
            fingerprint.partySize(), Timestamp.from(startedAt)
        );
    }

    private StoredRecord lock(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        HoldIdempotencyKey key
    ) {
        return jdbcTemplate.queryForObject("""
            SELECT venue_id, slot_inventory_id, party_size, reservation_id, completed_at
              FROM hold_idempotency_records
             WHERE tenant_id = ?
               AND customer_principal_id = ?
               AND idempotency_key = ?
             FOR UPDATE
            """, (resultSet, rowNumber) -> {
                Fingerprint fingerprint = new Fingerprint(
                    new VenueId(uuid(resultSet.getBytes("venue_id"))),
                    new SlotInventoryId(uuid(resultSet.getBytes("slot_inventory_id"))),
                    resultSet.getInt("party_size")
                );
                byte[] reservationBytes = resultSet.getBytes("reservation_id");
                Timestamp completedAt = resultSet.getTimestamp("completed_at");
                return new StoredRecord(
                    fingerprint,
                    reservationBytes == null ? null : new ReservationId(uuid(reservationBytes)),
                    completedAt == null ? null : completedAt.toInstant()
                );
            }, bytes(tenantId.value()), bytes(customerPrincipalId.value()), key.value());
    }

    private void deleteExpired(
        TenantId tenantId,
        PrincipalId customerPrincipalId,
        HoldIdempotencyKey key,
        Instant retentionCutoff
    ) {
        int deleted = jdbcTemplate.update("""
            DELETE FROM hold_idempotency_records
             WHERE tenant_id = ?
               AND customer_principal_id = ?
               AND idempotency_key = ?
               AND state = 'COMPLETED'
               AND completed_at <= ?
            """, bytes(tenantId.value()), bytes(customerPrincipalId.value()), key.value(),
            Timestamp.from(retentionCutoff));
        if (deleted != 1) {
            throw new IllegalStateException("Expired HOLD idempotency record could not be reclaimed");
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record StoredRecord(
        Fingerprint fingerprint,
        ReservationId reservationId,
        Instant completedAt
    ) { }
}
