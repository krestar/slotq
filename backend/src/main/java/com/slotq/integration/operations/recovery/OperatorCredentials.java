package com.slotq.integration.operations.recovery;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class OperatorCredentials {
    private final JdbcTemplate db;
    private final com.slotq.events.application.DeliveryTransactions transactions;

    public OperatorCredentials(JdbcTemplate db, com.slotq.events.application.DeliveryTransactions transactions) {
        this.db = db;
        this.transactions = transactions;
    }

    public Optional<HumanOperator> authenticate(String token) {
        if (token == null || !token.matches("sqop_[0-9a-f]{64}")) return Optional.empty();
        return transactions.execute(() -> db.query("""
            SELECT o.operator_id, c.credential_id FROM operations_credentials c
            JOIN operations_operators o ON o.operator_id=c.operator_id
            WHERE c.token_hash=? AND c.revoked_at IS NULL AND c.expires_at>UTC_TIMESTAMP(6) AND o.active=TRUE
            """, (row, n) -> new HumanOperator(uuid(row.getBytes(1)), uuid(row.getBytes(2))),
            hash(token)).stream().findFirst());
    }

    /** Lock identity/credential through recovery commit; revocation cannot pass this boundary. */
    String requireCurrent(HumanOperator actor) {
        if (actor == null) throw new RecoveryProblem(401, "OPERATOR_AUTHENTICATION_REQUIRED");
        var values = db.query("""
            SELECT o.principal_reference,o.active,c.revoked_at,c.expires_at FROM operations_operators o
            JOIN operations_credentials c ON c.operator_id=o.operator_id
            WHERE o.operator_id=? AND c.credential_id=? FOR SHARE
            """, (row,n)->new Current(row.getString(1),row.getBoolean(2),row.getObject(3)!=null,
                row.getObject(4,java.time.LocalDateTime.class)),bytes(actor.operatorId()),bytes(actor.credentialId()));
        java.time.LocalDateTime now=db.queryForObject("SELECT UTC_TIMESTAMP(6)",java.time.LocalDateTime.class);
        if (values.size()!=1 || !values.getFirst().active() || values.getFirst().revoked()
            || !values.getFirst().expires().isAfter(now)) throw new RecoveryProblem(401,"OPERATOR_AUTHENTICATION_REQUIRED");
        return values.getFirst().reference();
    }

    void requireGrant(HumanOperator actor, UUID tenant, String consumer, String action) {
        var rows = db.queryForList("""
            SELECT action FROM operations_grants
            WHERE operator_id=? AND tenant_id=? AND consumer_id=? AND action=? AND revoked_at IS NULL FOR SHARE
            """, String.class, bytes(actor.operatorId()), bytes(tenant), consumer, action);
        if (rows.size() != 1) throw RecoveryProblem.hidden();
    }

    static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
    static UUID uuid(byte[] value) {
        if (value == null) return null;
        var buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
    private record Current(String reference,boolean active,boolean revoked,java.time.LocalDateTime expires) { }
}
