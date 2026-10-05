package com.slotq.mcp;

import com.slotq.auth.access.AccessAction;
import com.slotq.auth.access.AccessProfile;
import com.slotq.auth.access.DelegatedActor;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import java.util.Set;

/** Only an integration composition root registers these; handlers are never scanned. */
public record ToolDefinition(McpSchema.Tool wire, Map<AccessProfile, AccessAction> permissions,
        Resource resource, Handler handler, Map<AccessProfile,Set<AccessAction>> alternativePermissions, McpAudit.Outcome failureOutcome) {
    public ToolDefinition {
        permissions = Map.copyOf(permissions);
        alternativePermissions = alternativePermissions.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> Set.copyOf(e.getValue())));
        if (permissions.isEmpty()) throw new IllegalArgumentException("Explicit tool permissions required");
        if(failureOutcome!=McpAudit.Outcome.UNKNOWN && failureOutcome!=McpAudit.Outcome.UNAVAILABLE)
            throw new IllegalArgumentException("Explicit failure disposition required");
        wire = new McpSchema.Tool(wire.name(), wire.title(), wire.description(), JsonData.freeze(wire.inputSchema()),
            wire.outputSchema() == null ? null : JsonData.freeze(wire.outputSchema()), wire.annotations(), null, null);
    }
    public ToolDefinition(McpSchema.Tool wire, Map<AccessProfile,AccessAction> permissions, Resource resource, Handler handler) {
        this(wire,permissions,resource,handler,Map.of(),McpAudit.Outcome.UNKNOWN);
    }
    public ToolDefinition(McpSchema.Tool wire, Map<AccessProfile,AccessAction> permissions, Resource resource, Handler handler,
            Map<AccessProfile,Set<AccessAction>> alternatives) {
        this(wire,permissions,resource,handler,alternatives,McpAudit.Outcome.UNKNOWN);
    }
    public ToolDefinition(McpSchema.Tool wire, AccessProfile profile, AccessAction action,
            Resource resource, Handler handler) {
        this(wire, Map.of(profile, action), resource, handler);
    }
    public boolean permits(DelegatedActor actor) {
        return action(actor) != null && actor.tools().contains(wire.name());
    }
    public AccessAction action(DelegatedActor actor) {
        AccessAction required=permissions.get(actor.profile());
        if(required==null)return null;
        if(actor.actions().contains(required))return required;
        return alternativePermissions.getOrDefault(actor.profile(),Set.of()).stream().sorted().filter(actor.actions()::contains).findFirst().orElse(null);
    }
    public AccessAction action(AccessProfile profile) { return permissions.get(profile); }
    public enum Resource { PRODUCT, RETRIEVAL }
    @FunctionalInterface public interface Handler {
        ToolOutcome execute(RequestContext context, Map<String, Object> input) throws Exception;
    }
}
