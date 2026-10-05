package com.slotq.knowledge.application;

import com.slotq.knowledge.domain.Corpus.*;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bounded controlled manifest ingestion. No fetching, queue, retry, provider, or startup seeds. */
@Component
public class CorpusIngestion {
    private static final int MAX_MANIFEST_BYTES = 131072;
    private final CorpusAuthoring authoring;
    public CorpusIngestion(CorpusAuthoring authoring) { this.authoring = authoring; }

    public record Entry(VersionInput version, long expectedRevision) {
        public Entry { if (expectedRevision < 0) throw new IllegalArgumentException("Invalid revision"); }
        @Override public String toString() { return "Entry[redacted]"; }
    }
    public record Manifest(TenantId expectedTenant, VenueId venue, List<Entry> entries) {
        public Manifest {
            java.util.Objects.requireNonNull(expectedTenant); java.util.Objects.requireNonNull(venue);
            entries = List.copyOf(entries);
            Set<UUID> ids = new HashSet<>();
            if (entries.isEmpty() || entries.size() > 8 || entries.stream().anyMatch(e -> !ids.add(e.version().documentId()))) {
                throw new IllegalArgumentException("Invalid bounded manifest");
            }
        }
        @Override public String toString() { return "Manifest[redacted]"; }
    }
    public List<VersionMetadata> ingest(String originalCredential, Manifest manifest) {
        // Preflight every scope before any write; expected Tenant is a consistency claim, never authority.
        for (Entry entry : manifest.entries()) {
            var document = authoring.inspect(originalCredential, manifest.venue(), entry.version().documentId());
            if (!document.key().tenantId().equals(manifest.expectedTenant())) {
                throw new CorpusFailure(CorpusFailure.Reason.SCOPE_MISMATCH);
            }
        }
        return manifest.entries().stream().map(entry -> {
            var staged = authoring.stage(originalCredential, manifest.venue(), entry.version(), entry.expectedRevision());
            var validated = authoring.validate(originalCredential, staged.reference());
            if (validated.state() == VersionState.FAILED) throw new CorpusFailure(CorpusFailure.Reason.VALIDATION_FAILED);
            return authoring.publish(originalCredential, validated.reference());
        }).toList();
    }
    public static Manifest readManifest(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            if (bytes.length > MAX_MANIFEST_BYTES) throw new IllegalArgumentException();
            JsonNode root = JsonMapper.builder().build().readTree(bytes);
            fields(root, Set.of("tenantId", "venueId", "documents"));
            JsonNode documents = root.get("documents");
            if (!documents.isArray() || documents.isEmpty() || documents.size() > 8) throw new IllegalArgumentException();
            var entries = new java.util.ArrayList<Entry>();
            for (JsonNode node : documents) {
                fields(node, Set.of("documentId", "versionId", "sourceId", "sourceReference", "sourceTitle", "visibility",
                        "content", "contentDigest", "expectedRevision"));
                if (!node.get("expectedRevision").isIntegralNumber() || !node.get("expectedRevision").canConvertToLong()) {
                    throw new IllegalArgumentException();
                }
                entries.add(new Entry(new VersionInput(id(node, "documentId"), id(node, "versionId"),
                        new Source(id(node, "sourceId"), string(node, "sourceReference"), string(node, "sourceTitle")),
                        Visibility.valueOf(string(node, "visibility")), string(node, "content"), string(node, "contentDigest")),
                        node.get("expectedRevision").asLong()));
            }
            return new Manifest(new TenantId(id(root, "tenantId")), new VenueId(id(root, "venueId")), entries);
        } catch (IOException | RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid bounded corpus manifest");
        }
    }
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || !node.propertyNames().equals(expected)) throw new IllegalArgumentException();
    }
    private static String string(JsonNode node, String name) {
        if (!node.get(name).isString()) throw new IllegalArgumentException();
        return node.get(name).asString();
    }
    private static UUID id(JsonNode node, String name) {
        String value = string(node, name);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }
}
