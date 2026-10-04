package com.slotq.mcp;

import com.slotq.auth.access.*;
import java.net.URI;
import java.time.*;
import java.util.UUID;

/** Narrow preparation contract for #132; does not invoke Product, retry, confirm or reconcile a command. */
public final class ProductHttpBinding {
    public static final Duration CONNECT_MAX=Duration.ofSeconds(2),RESPONSE_MAX=Duration.ofSeconds(15);
    private final URI origin;
    private final ActorAccess authority;
    private final ProductCredentialAccess credentials;
    private final Clock clock;
    public ProductHttpBinding(URI origin,int productPort,ActorAccess authority,ProductCredentialAccess credentials,Clock clock) {
        if(!"https".equals(origin.getScheme()) || !java.util.Set.of("localhost","127.0.0.1","[::1]").contains(origin.getHost())
                || origin.getPort()!=productPort || origin.getUserInfo()!=null || origin.getQuery()!=null || origin.getFragment()!=null
                || !origin.getPath().isEmpty())throw new IllegalArgumentException("Fixed co-located HTTPS Product origin required");
        this.origin=origin;this.authority=authority;this.credentials=credentials;this.clock=clock;
    }
    public Prepared prepare(RequestContext context,ProductOperation operation,UUID target) {
        if(operation==ProductOperation.RESERVATION_HOLD)throw new McpFailure(McpFailure.Reason.FORBIDDEN);
        DelegatedActor actor=context.revalidate(authority,clock);
        ProductAccessCredential credential=credentials.issueProduct(actor.delegationId(),operation,target,context.deadline());
        String path=operation==ProductOperation.RESERVATION_GET
            ?"/api/v1/venues/"+actor.venueId().value()+"/reservations/"+target
            :"/api/v1/management/venues/"+actor.venueId().value()+"/reservations";
        return new Prepared(origin.resolve(path),"GET",credential,context.remaining(clock,CONNECT_MAX),
            context.remaining(clock,RESPONSE_MAX));
    }
    public Prepared prepareHold(RequestContext context,UUID slot,int partySize,String key) {
        DelegatedActor actor=context.revalidate(authority,clock);
        ProductAccessCredential credential=credentials.issueHold(actor.delegationId(),slot,partySize,key,context.deadline());
        return new Prepared(origin.resolve("/api/v1/venues/"+actor.venueId().value()+"/reservations/holds"),"POST",credential,
            context.remaining(clock,CONNECT_MAX),context.remaining(clock,RESPONSE_MAX));
    }
    public record Prepared(URI uri,String method,ProductAccessCredential credential,Duration connectTimeout,Duration responseTimeout) { }
}
