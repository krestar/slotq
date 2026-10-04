package com.slotq.mcp;

import com.slotq.auth.access.AccessAction;
import com.slotq.auth.access.AccessProfile;
import com.slotq.auth.access.DelegatedActor;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;

/** Only an integration composition root registers these; handlers are never scanned. */
public record ToolDefinition(McpSchema.Tool wire, Map<AccessProfile, AccessAction> permissions,
        Resource resource, Handler handler) {
    public ToolDefinition {
        permissions = Map.copyOf(permissions);
        if (permissions.isEmpty()) throw new IllegalArgumentException("Explicit tool permissions required");
        wire = new McpSchema.Tool(wire.name(), wire.title(), wire.description(), JsonData.freeze(wire.inputSchema()),
            wire.outputSchema() == null ? null : JsonData.freeze(wire.outputSchema()), wire.annotations(), null, null);
    }
    public ToolDefinition(McpSchema.Tool wire, AccessProfile profile, AccessAction action,
            Resource resource, Handler handler) {
        this(wire, Map.of(profile, action), resource, handler);
    }
    public boolean permits(DelegatedActor actor) {
        AccessAction required = permissions.get(actor.profile());
        return required != null && actor.permits(wire.name(), actor.profile(), required);
    }
    public AccessAction action(AccessProfile profile) { return permissions.get(profile); }
    public enum Resource { PRODUCT, RETRIEVAL }
    @FunctionalInterface public interface Handler {
        ToolOutcome execute(RequestContext context, Map<String, Object> input) throws Exception;
    }
}
