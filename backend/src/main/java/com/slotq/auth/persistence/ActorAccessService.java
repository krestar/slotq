package com.slotq.auth.persistence;

import com.slotq.auth.access.*;
import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.domain.*;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Controlled server provisioning API, intentionally no HTTP controller or startup fixtures. */
@Service
public class ActorAccessService implements ActorAccess, ProductCredentialAccess {
    private final JdbcTemplate jdbc;
    private final AuthorizationUseCase authorization;
    private final Clock clock;
    private final TransactionTemplate current;
    private final SecureRandom random = new SecureRandom();

    public ActorAccessService(JdbcTemplate jdbc, AuthorizationUseCase authorization, Clock clock,
            PlatformTransactionManager transactions) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(2);
        this.authorization = authorization; this.clock = clock;
        current = new TransactionTemplate(transactions);
        current.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        current.setTimeout(2);
    }

    public ProvisionedCredential provisionOriginal(PrincipalId principal, Instant expiresAt) {
        return boundary(() -> {
            if (!expiresAt.isAfter(clock.instant()) || jdbc.queryForObject(
                    "SELECT COUNT(*) FROM auth_principals WHERE id=?", Integer.class, bytes(principal.value())) != 1) {
                throw denied();
            }
            return credential("ORIGINAL", principal, null, expiresAt, null, null);
        });
    }

    /** Called only after an original Actor explicitly approves these exact server-owned limits. */
    public ProvisionedCredential approveDelegation(String originalCredential, VenueId venue, AccessProfile profile,
            Set<AccessAction> actions, Set<String> tools, Duration validity) {
        return boundary(() -> {
            Credential original = lookup(originalCredential, "ORIGINAL");
            if (validity.isNegative() || validity.isZero() || validity.compareTo(Duration.ofMinutes(15)) > 0
                    || actions.isEmpty() || tools.isEmpty() || tools.size() > 16
                    || tools.stream().anyMatch(tool -> !tool.matches("[a-z][a-z0-9_.]{0,79}"))) throw denied();
            UUID tenant = verifiedVenue(venue, profile == AccessProfile.CUSTOMER);
            validateProfile(original.principal(), venue, profile, actions);
            UUID id = UUID.randomUUID();
            Instant now = clock.instant();
            Instant expiry = earlier(now.plus(validity), original.expiry());
            jdbc.update("INSERT INTO auth_access_delegations "
                    + "(id,original_credential_id,principal_id,tenant_id,venue_id,profile,actions,tools,issued_at,expires_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?)", bytes(id), bytes(original.id()), bytes(original.principal().value()),
                    bytes(tenant), bytes(venue.value()), profile.name(), join(actions), String.join(",", new TreeSet<>(tools)),
                    Timestamp.from(now), Timestamp.from(expiry));
            return credential("MCP", original.principal(), id, expiry, null, null);
        });
    }

    public void revokeCredential(UUID id) {
        boundary(() -> { jdbc.update("UPDATE auth_access_credentials SET revoked_at=? WHERE id=?",
                Timestamp.from(clock.instant()), bytes(id)); return null; });
    }
    public void revokeDelegation(UUID id) {
        boundary(() -> { jdbc.update("UPDATE auth_access_delegations SET revoked_at=? WHERE id=?",
                Timestamp.from(clock.instant()), bytes(id)); return null; });
    }

    @Override public AuthenticatedPrincipal validateOriginal(String credential) {
        return boundary(() -> new AuthenticatedPrincipal(lookup(credential, "ORIGINAL").principal()));
    }
    @Override public AuthenticatedPrincipal requireOriginalConfigurationAccess(String credential, VenueId venue) {
        return boundary(() -> {
            var principal = new AuthenticatedPrincipal(lookup(credential, "ORIGINAL").principal());
            verifiedVenue(venue, true);
            authorization.requireVenueConfigurationAccess(principal, venue);
            return principal;
        });
    }
    @Override public DelegatedActor authenticateMcp(String credential) {
        return boundary(() -> live(lookup(credential, "MCP").delegation()));
    }
    @Override public DelegatedActor revalidate(UUID delegationId) { return boundary(() -> live(delegationId)); }

    @Override public ProductAccessCredential issueProduct(UUID delegation, ProductOperation operation,
            UUID target, Instant deadline) {
        return boundary(() -> {
            DelegatedActor actor = live(delegation);
            if (actor.profile() != operation.profile || !actor.actions().contains(operation.action) || !actor.tools().contains(operation.tool)
                    || !deadline.isAfter(clock.instant())
                    || (operation == ProductOperation.RESERVATION_GET && target == null)
                    || (operation == ProductOperation.MANAGEMENT_LIST && target != null)) throw denied();
            Instant expires = earlier(earlier(clock.instant().plusSeconds(60), deadline), actor.expiresAt());
            ProvisionedCredential issued = credential("PRODUCT", actor.original().principalId(), delegation,
                    expires, operation.name(), target);
            return new ProductAccessCredential(issued.value(), expires);
        });
    }

    @Override public Optional<AuthenticatedPrincipal> authenticateProduct(String token, String method, String path) {
        return boundary(() -> {
            var rows = jdbc.query("SELECT audience FROM auth_access_credentials WHERE token_digest=?",
                    (rs, i) -> rs.getString(1), digest(token));
            if (rows.isEmpty()) return Optional.empty();
            Credential credential = lookup(token, "PRODUCT");
            DelegatedActor actor = live(credential.delegation());
            ProductOperation operation = ProductOperation.valueOf(credential.operation());
            if (!method.equals("GET") || actor.profile() != operation.profile
                    || !actor.actions().contains(operation.action) || !actor.tools().contains(operation.tool)) throw denied();
            String expected = operation == ProductOperation.RESERVATION_GET
                    ? "/api/v1/venues/" + actor.venueId().value() + "/reservations/" + credential.target()
                    : "/api/v1/management/venues/" + actor.venueId().value() + "/reservations";
            if (!path.equals(expected)) throw denied();
            return Optional.of(new AuthenticatedPrincipal(actor.original().principalId(), new ConsumerRestriction(
                    actor.tenantId(), actor.venueId(), actor.profile() == AccessProfile.CUSTOMER)));
        });
    }

    private DelegatedActor live(UUID id) {
        if (id == null) throw unauthenticated();
        var rows = jdbc.query("SELECT d.*, o.expires_at original_expiry, o.revoked_at original_revoked "
                + "FROM auth_access_delegations d JOIN auth_access_credentials o ON o.id=d.original_credential_id "
                + "AND o.audience='ORIGINAL' AND o.principal_id=d.principal_id WHERE d.id=?",
                (rs, i) -> {
                    Instant expiry = rs.getTimestamp("expires_at").toInstant();
                    if (!clock.instant().isBefore(expiry) || rs.getTimestamp("revoked_at") != null
                            || !clock.instant().isBefore(rs.getTimestamp("original_expiry").toInstant())
                            || rs.getTimestamp("original_revoked") != null) throw unauthenticated();
                    Set<AccessAction> actions = EnumSet.noneOf(AccessAction.class);
                    for (String a : rs.getString("actions").split(",")) actions.add(AccessAction.valueOf(a));
                    return new DelegatedActor(new AuthenticatedPrincipal(new PrincipalId(uuid(rs.getBytes("principal_id")))),
                            uuid(rs.getBytes("original_credential_id")), id, new TenantId(uuid(rs.getBytes("tenant_id"))),
                            new VenueId(uuid(rs.getBytes("venue_id"))), AccessProfile.valueOf(rs.getString("profile")),
                            actions, Set.of(rs.getString("tools").split(",")), expiry);
                }, bytes(id));
        if (rows.size() != 1) throw unauthenticated();
        var actor = rows.getFirst();
        if (!actor.tenantId().value().equals(verifiedVenue(actor.venueId(), actor.profile() == AccessProfile.CUSTOMER))) throw denied();
        validateProfile(actor.original().principalId(), actor.venueId(), actor.profile(), actor.actions());
        return actor;
    }

    private UUID verifiedVenue(VenueId venue, boolean requireActivePublicVenue) {
        var rows = jdbc.query("SELECT v.tenant_id FROM venues v JOIN tenants t ON t.id=v.tenant_id "
                + "WHERE v.id=?" + (requireActivePublicVenue ? " AND v.status='ACTIVE' AND t.status='ACTIVE'" : ""),
                (rs, i) -> uuid(rs.getBytes(1)),
                bytes(venue.value()));
        if (rows.size() != 1) throw denied();
        return rows.getFirst();
    }
    private void validateProfile(PrincipalId principal, VenueId venue, AccessProfile profile, Set<AccessAction> actions) {
        Set<AccessAction> ceiling = profile == AccessProfile.CUSTOMER
                ? EnumSet.of(AccessAction.RESERVATION_READ, AccessAction.RESERVATION_WRITE, AccessAction.KNOWLEDGE_PUBLIC)
                : EnumSet.of(AccessAction.MANAGEMENT_READ, AccessAction.KNOWLEDGE_PUBLIC, AccessAction.KNOWLEDGE_OPERATOR);
        if (!ceiling.containsAll(actions)) throw denied();
        if (profile == AccessProfile.MANAGEMENT) authorization.requireVenueAccess(new AuthenticatedPrincipal(principal), venue);
    }
    private Credential lookup(String token, String audience) {
        var rows = jdbc.query("SELECT * FROM auth_access_credentials WHERE token_digest=? AND audience=?",
                (rs, i) -> {
                    Instant expiry = rs.getTimestamp("expires_at").toInstant();
                    if (!clock.instant().isBefore(expiry) || rs.getTimestamp("revoked_at") != null) throw unauthenticated();
                    return new Credential(uuid(rs.getBytes("id")), new PrincipalId(uuid(rs.getBytes("principal_id"))),
                            nullableUuid(rs.getBytes("delegation_id")), expiry, rs.getString("operation"),
                            nullableUuid(rs.getBytes("target_id")));
                }, digest(token), audience);
        if (rows.size() != 1) throw unauthenticated();
        return rows.getFirst();
    }
    private ProvisionedCredential credential(String audience, PrincipalId principal, UUID delegation,
            Instant expiry, String operation, UUID target) {
        byte[] secret = new byte[32]; random.nextBytes(secret);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_access_credentials "
                + "(id,token_digest,audience,principal_id,delegation_id,expires_at,operation,target_id) VALUES (?,?,?,?,?,?,?,?)",
                bytes(id), digest(value), audience, bytes(principal.value()), nullableBytes(delegation),
                Timestamp.from(expiry), operation, nullableBytes(target));
        return new ProvisionedCredential(id, delegation, value);
    }
    private <T> T boundary(Supplier<T> task) {
        try { return current.execute(status -> task.get()); }
        catch (AccessFailure failure) { throw failure; }
        catch (com.slotq.auth.application.AccessDeniedException | com.slotq.auth.application.ResourceNotFoundException e) {
            throw denied();
        } catch (RuntimeException e) { throw new AccessFailure(AccessFailure.Reason.UNAVAILABLE); }
    }
    private static String digest(String value) {
        if (value == null || value.length() > 512) throw unauthenticated();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(); }
    }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static String join(Set<AccessAction> actions) { return String.join(",", actions.stream().map(Enum::name).sorted().toList()); }
    private static AccessFailure denied() { return new AccessFailure(AccessFailure.Reason.FORBIDDEN); }
    private static AccessFailure unauthenticated() { return new AccessFailure(AccessFailure.Reason.UNAUTHENTICATED); }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static byte[] nullableBytes(UUID id) { return id == null ? null : bytes(id); }
    private static UUID uuid(byte[] bytes) { var b = ByteBuffer.wrap(bytes); return new UUID(b.getLong(), b.getLong()); }
    private static UUID nullableUuid(byte[] bytes) { return bytes == null ? null : uuid(bytes); }
    private record Credential(UUID id, PrincipalId principal, UUID delegation, Instant expiry, String operation, UUID target) { }
    public static final class ProvisionedCredential {
        private final UUID id, delegationId;
        private final String value;
        ProvisionedCredential(UUID id, UUID delegationId, String value) { this.id=id; this.delegationId=delegationId; this.value=value; }
        public UUID id() { return id; }
        public UUID delegationId() { return delegationId; }
        public String value() { return value; }
        @Override public String toString() { return "ProvisionedCredential[redacted]"; }
    }
}
