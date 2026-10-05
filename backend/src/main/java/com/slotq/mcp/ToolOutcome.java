package com.slotq.mcp;

import java.util.Map;
import java.util.UUID;

/** Result content is untrusted data, never audit metadata. Integrations declare dispatch ambiguity. */
public record ToolOutcome(Map<String, Object> content, boolean dispatched, UUID knownTarget,
        UUID productRequestId, UUID confirmationId, UUID intentId, UUID documentId, UUID versionId,
        McpFailure.Reason failure, McpAudit.TimeoutLayer timeoutLayer, java.util.List<RetrievalReference> retrievalReferences) {
    public ToolOutcome {
        content = JsonData.freeze(content);
        java.util.Objects.requireNonNull(timeoutLayer);
        retrievalReferences = java.util.List.copyOf(retrievalReferences);
        if (retrievalReferences.size() > 5) throw new IllegalArgumentException("Provenance bound");
    }
    public ToolOutcome(Map<String,Object> content, boolean dispatched, UUID knownTarget, UUID productRequestId,
            UUID confirmationId, UUID intentId, UUID documentId, UUID versionId, McpFailure.Reason failure, McpAudit.TimeoutLayer layer) {
        this(content,dispatched,knownTarget,productRequestId,confirmationId,intentId,documentId,versionId,failure,layer,java.util.List.of());
    }
    public static ToolOutcome success(Map<String, Object> content) {
        return new ToolOutcome(content, false, null, null, null, null, null, null, null, McpAudit.TimeoutLayer.NONE);
    }
}
