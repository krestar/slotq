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
        String path = switch (operation) {
            case RESERVATION_GET -> "/api/v1/venues/" + actor.venueId().value() + "/reservations/" + target;
            case MANAGEMENT_LIST -> "/api/v1/management/venues/" + actor.venueId().value() + "/reservations";
            case EVENT_OBSERVATIONS_LIST -> "/api/v1/management/venues/" + actor.venueId().value() + "/event-observations";
            case RESERVATION_HOLD -> throw new McpFailure(McpFailure.Reason.FORBIDDEN);
        };
        return new Prepared(origin.resolve(path),"GET",credential,context.remaining(clock,CONNECT_MAX),
            context.remaining(clock,RESPONSE_MAX), operation == ProductOperation.EVENT_OBSERVATIONS_LIST
                ? ToolDefinition.MAX_RESULT_BYTES : ToolDefinition.DEFAULT_RESULT_BYTES);
    }
    public Prepared prepareHold(RequestContext context,UUID slot,int partySize,String key) {
        DelegatedActor actor=context.revalidate(authority,clock);
        ProductAccessCredential credential=credentials.issueHold(actor.delegationId(),slot,partySize,key,context.deadline());
        return new Prepared(origin.resolve("/api/v1/venues/"+actor.venueId().value()+"/reservations/holds"),"POST",credential,
            context.remaining(clock,CONNECT_MAX),context.remaining(clock,RESPONSE_MAX));
    }
    public record Prepared(URI uri,String method,ProductAccessCredential credential,Duration connectTimeout,
                           Duration responseTimeout, int maxResponseBytes) {
        public Prepared {
            if (maxResponseBytes < 1 || maxResponseBytes > ToolDefinition.MAX_RESULT_BYTES) {
                throw new IllegalArgumentException("Product response bound required");
            }
        }
        public Prepared(URI uri, String method, ProductAccessCredential credential, Duration connectTimeout, Duration responseTimeout) {
            this(uri, method, credential, connectTimeout, responseTimeout, ToolDefinition.DEFAULT_RESULT_BYTES);
        }
    }
}
