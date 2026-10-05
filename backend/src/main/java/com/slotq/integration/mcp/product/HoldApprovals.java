package com.slotq.integration.mcp.product;

import com.slotq.auth.access.*;
import com.slotq.auth.domain.*;
import com.slotq.mcp.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Controlled original-Actor approval API, deliberately outside the MCP registry and HTTP tool surface. */
public final class HoldApprovals {
    public static final Duration CONFIRMATION=Duration.ofMinutes(5), RETRY=Duration.ofMinutes(15);
    private final ActorAccess authority;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate current;
    private final SecureRandom random=new SecureRandom();
    public HoldApprovals(ActorAccess authority,JdbcTemplate source,PlatformTransactionManager transactions,Clock clock) {
        this.authority=authority;this.clock=clock;this.jdbc=new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
        jdbc.setQueryTimeout(2);current=new TransactionTemplate(transactions);
        current.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);current.setTimeout(2);
    }
    /** Exact review material; no caller-selected Product key and no automatic dispatch. */
    public Review prepare(String originalCredential,UUID delegation,UUID slot,int partySize) {
        var actor=original(originalCredential,delegation);
        if(slot==null || partySize<1)throw denied();
        byte[] secret=new byte[32];random.nextBytes(secret);
        String key=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return current.execute(status->{
            Instant now=storedNow(),expiry=earlier(now.plus(CONFIRMATION),actor.expiresAt());
            if(!expiry.isAfter(now))throw denied();UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO mcp_hold_intents(id,approver_id,delegation_id,tenant_id,venue_id,slot_id,party_size,tool,action,product_key,prepared_at,prepared_expires_at) "
                +"VALUES(?,?,?,?,?,?,?,'reservation.hold','RESERVATION_WRITE',?,?,?)",bytes(id),bytes(actor.original().principalId().value()),
                bytes(delegation),bytes(actor.tenantId().value()),bytes(actor.venueId().value()),bytes(slot),partySize,key,
                Timestamp.from(now),Timestamp.from(expiry));
            return new Review(id,actor.tenantId().value(),actor.venueId().value(),slot,partySize,key,expiry,null,null);
        });
    }
    /** Original Actor authenticates afresh and approves this stored immutable review, never a Boolean. */
    public Confirmation approve(String originalCredential,UUID intentId) {
        AuthenticatedPrincipal approver=authority.validateOriginal(originalCredential);
        return current.execute(status->{
            Intent intent=find(intentId,true);DelegatedActor actor=original(originalCredential,intent.delegation());
            requireActor(intent,actor);
            if(!intent.approver().equals(approver.principalId().value()))throw denied();
            Instant now=storedNow();requireWindow(intent,clock.instant());
            Instant expiry=earlier(earlier(now.plus(CONFIRMATION),actor.expiresAt()),
                intent.firstDispatch()==null?intent.preparedExpiry():intent.firstDispatch().plus(RETRY));
            if(!expiry.isAfter(now))throw denied();UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO mcp_hold_confirmations(id,intent_id,approved_at,expires_at) VALUES(?,?,?,?)",
                bytes(id),bytes(intentId),Timestamp.from(now),Timestamp.from(expiry));
            return new Confirmation(id,intentId,expiry);
        });
    }
    public Review review(String originalCredential,UUID intentId) {
        var approver=authority.validateOriginal(originalCredential);
        return current.execute(status->{
            Intent intent=find(intentId,false);
            if(!intent.approver().equals(approver.principalId().value()))throw denied();
            return new Review(intent.id(),intent.tenant(),intent.venue(),intent.slot(),intent.partySize(),intent.key(),
                intent.firstDispatch()==null?intent.preparedExpiry():intent.firstDispatch().plus(RETRY),intent.firstDispatch(),intent.knownTarget());
        });
    }
    Attempt dispatch(RequestContext context,UUID intentId,UUID confirmation,UUID slot,int partySize,String key) {
        DelegatedActor actor=context.revalidate(authority,clock);
        return current.execute(status->{
            Intent intent=find(intentId,true);requireActor(intent,actor);
            if(!intent.slot().equals(slot) || intent.partySize()!=partySize || !intent.key().equals(key))throw denied();
            var approvals=jdbc.query("SELECT approved_at,expires_at FROM mcp_hold_confirmations WHERE id=? AND intent_id=?",
                (rs,i)->new Instant[]{rs.getTimestamp(1).toInstant(),rs.getTimestamp(2).toInstant()},bytes(confirmation),bytes(intentId));
            if(approvals.size()!=1)throw denied();
            Instant now=clock.instant();requireWindow(intent,now);
            if(now.isBefore(approvals.getFirst()[0]) || !now.isBefore(approvals.getFirst()[1]))throw denied();
            context.remaining(clock,Duration.ofSeconds(30));
            Instant first=intent.firstDispatch();
            if(first==null){first=now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);jdbc.update("UPDATE mcp_hold_intents SET first_dispatch_at=? WHERE id=? AND first_dispatch_at IS NULL",
                Timestamp.from(first),bytes(intentId));}
            return new Attempt(intentId,confirmation,intent.venue(),intent.slot(),intent.partySize(),intent.key(),first,intent.knownTarget());
        });
    }
    void rememberTarget(UUID intent,UUID target) {
        if(target==null)return;
        current.execute(status->{
            int updated=jdbc.update("UPDATE mcp_hold_intents SET known_reservation_id=? WHERE id=? "
                +"AND (known_reservation_id IS NULL OR known_reservation_id=?)",bytes(target),bytes(intent),bytes(target));
            if(updated!=1)throw denied();return null;
        });
    }
    private DelegatedActor original(String credential,UUID delegation) {
        AuthenticatedPrincipal principal=authority.validateOriginal(credential);
        DelegatedActor actor=authority.revalidate(delegation);
        if(!principal.principalId().equals(actor.original().principalId())
                || !actor.permits("reservation.hold",AccessProfile.CUSTOMER,AccessAction.RESERVATION_WRITE))throw denied();
        return actor;
    }
    private static void requireActor(Intent intent,DelegatedActor actor) {
        if(!intent.approver().equals(actor.original().principalId().value()) || !intent.delegation().equals(actor.delegationId())
                || !intent.tenant().equals(actor.tenantId().value()) || !intent.venue().equals(actor.venueId().value())
                || !actor.permits("reservation.hold",AccessProfile.CUSTOMER,AccessAction.RESERVATION_WRITE)
                || !intent.tool().equals("reservation.hold") || !intent.action().equals("RESERVATION_WRITE"))throw denied();
    }
    private static void requireWindow(Intent intent,Instant now) {
        if(now.isBefore(intent.prepared()) || (intent.firstDispatch()!=null && now.isBefore(intent.firstDispatch())))
            throw new McpFailure(McpFailure.Reason.UNAVAILABLE);
        if(!now.isBefore(intent.firstDispatch()==null?intent.preparedExpiry():intent.firstDispatch().plus(RETRY)))throw denied();
    }
    private Intent find(UUID id,boolean lock) {
        if(id==null)throw denied();
        var rows=jdbc.query("SELECT * FROM mcp_hold_intents WHERE id=?"+(lock?" FOR UPDATE":""),(rs,i)->new Intent(
            uuid(rs.getBytes("id")),uuid(rs.getBytes("approver_id")),uuid(rs.getBytes("delegation_id")),uuid(rs.getBytes("tenant_id")),
            uuid(rs.getBytes("venue_id")),uuid(rs.getBytes("slot_id")),rs.getInt("party_size"),rs.getString("tool"),rs.getString("action"),
            rs.getString("product_key"),rs.getTimestamp("prepared_at").toInstant(),rs.getTimestamp("prepared_expires_at").toInstant(),
            rs.getTimestamp("first_dispatch_at")==null?null:rs.getTimestamp("first_dispatch_at").toInstant(),nullableUuid(rs.getBytes("known_reservation_id"))),bytes(id));
        if(rows.size()!=1)throw denied();return rows.getFirst();
    }
    private static McpFailure denied(){return new McpFailure(McpFailure.Reason.FORBIDDEN);}
    private Instant storedNow(){return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    private static Instant earlier(Instant a,Instant b){return a.isBefore(b)?a:b;}
    static byte[] bytes(UUID id){return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    static UUID uuid(byte[] value){var b=ByteBuffer.wrap(value);return new UUID(b.getLong(),b.getLong());}
    static UUID nullableUuid(byte[] value){return value==null?null:uuid(value);}
    private record Intent(UUID id,UUID approver,UUID delegation,UUID tenant,UUID venue,UUID slot,int partySize,String tool,String action,
        String key,Instant prepared,Instant preparedExpiry,Instant firstDispatch,UUID knownTarget){}
    record Attempt(UUID intent,UUID confirmation,UUID venue,UUID slot,int partySize,String key,Instant firstDispatch,UUID knownTarget){
        @Override public String toString(){return "HoldAttempt[redacted]";}
    }
    public record Confirmation(UUID id,UUID intentId,Instant expiresAt){}
    public record Review(UUID intentId,UUID tenantId,UUID venueId,UUID slotInventoryId,int partySize,String idempotencyKey,
        Instant retryExpiresAt,Instant firstDispatchAt,UUID knownReservationId){
        @Override public String toString(){return "HoldReview[intentId="+intentId+"]";}
    }
}
