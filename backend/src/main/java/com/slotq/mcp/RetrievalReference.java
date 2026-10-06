package com.slotq.mcp;

import java.util.UUID;
import java.util.Objects;

/** Opaque provenance only; no URL, title, excerpt, query or provider text. */
public record RetrievalReference(UUID sourceId, UUID documentId, UUID versionId) {
    public RetrievalReference { Objects.requireNonNull(sourceId); Objects.requireNonNull(documentId); Objects.requireNonNull(versionId); }
}
