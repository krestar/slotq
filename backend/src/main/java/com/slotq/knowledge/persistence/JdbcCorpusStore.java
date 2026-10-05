package com.slotq.knowledge.persistence;

import com.slotq.knowledge.application.CorpusStore;
import com.slotq.knowledge.domain.Corpus.*;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcCorpusStore implements CorpusStore {
    private final JdbcTemplate jdbc;
    JdbcCorpusStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<PublishedVersion> publications(TenantId tenant, VenueId venue, Set<Visibility> visibility, int limit) {
        if (visibility.isEmpty() || limit < 1 || limit > 65) throw new IllegalArgumentException("Bounded publication read required");
        var args = new java.util.ArrayList<Object>();
        args.add(bytes(tenant.value())); args.add(bytes(venue.value()));
        visibility.stream().sorted().forEach(v -> args.add(v.name())); args.add(limit);
        String slots = String.join(",", java.util.Collections.nCopies(visibility.size(), "?"));
        return jdbc.query("SELECT v.*,d.revision FROM knowledge_documents d JOIN knowledge_versions v "
                + "ON v.tenant_id=d.tenant_id AND v.venue_id=d.venue_id AND v.document_id=d.document_id "
                + "AND v.version_id=d.current_version_id WHERE d.tenant_id=? AND d.venue_id=? "
                + "AND v.visibility IN (" + slots + ") AND d.state='ACTIVE' AND v.state='PUBLISHED' "
                + "AND v.content IS NOT NULL ORDER BY d.document_id LIMIT ?",
                (rs, n) -> new PublishedVersion(new VersionMetadata(new VersionReference(
                        new DocumentKey(tenant, venue, uuid(rs.getBytes("document_id"))), uuid(rs.getBytes("version_id")),
                        rs.getString("content_digest"), Visibility.valueOf(rs.getString("visibility"))),
                        new Source(uuid(rs.getBytes("source_id")), rs.getString("source_reference"), rs.getString("source_title")),
                        VersionState.PUBLISHED, rs.getLong("staged_revision")), rs.getLong("revision"), rs.getString("content")), args.toArray());
    }

    @Override public void createDocument(DocumentKey key) {
        jdbc.update("INSERT INTO knowledge_documents (tenant_id,venue_id,document_id,state,revision) "
                + "VALUES (?,?,?,'UNPUBLISHED',0) ON DUPLICATE KEY UPDATE document_id=document_id", scope(key));
    }
    @Override public Optional<DocumentMetadata> document(DocumentKey key, boolean lock) {
        return jdbc.query("SELECT state,revision,current_version_id FROM knowledge_documents "
                + "WHERE tenant_id=? AND venue_id=? AND document_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new DocumentMetadata(key, DocumentState.valueOf(rs.getString("state")),
                        rs.getLong("revision"), nullableUuid(rs.getBytes("current_version_id"))), scope(key)).stream().findFirst();
    }
    @Override public Optional<StoredVersion> version(DocumentKey key, UUID version) {
        return jdbc.query("SELECT * FROM knowledge_versions WHERE tenant_id=? AND venue_id=? AND document_id=? AND version_id=?",
                (rs, n) -> new StoredVersion(new VersionMetadata(new VersionReference(key, version,
                        rs.getString("content_digest"), Visibility.valueOf(rs.getString("visibility"))),
                        new Source(uuid(rs.getBytes("source_id")), rs.getString("source_reference"), rs.getString("source_title")),
                        VersionState.valueOf(rs.getString("state")), rs.getLong("staged_revision")),
                        rs.getString("expected_digest"), rs.getString("content")), with(key, bytes(version))).stream().findFirst();
    }
    @Override public void insertVersion(DocumentKey key, VersionInput input, String digest, long revision) {
        jdbc.update("INSERT INTO knowledge_versions (tenant_id,venue_id,document_id,version_id,content_digest,expected_digest,"
                + "source_id,source_reference,source_title,visibility,state,staged_revision,content) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,'STAGED',?,?)", with(key, bytes(input.versionId()), digest,
                        input.expectedDigest(), bytes(input.source().sourceId()), input.source().reference(),
                        input.source().title(), input.visibility().name(), revision, input.content()));
    }
    @Override public Optional<PublishedVersion> currentPublished(DocumentKey key) {
        return jdbc.query("SELECT v.*,d.revision FROM knowledge_documents d JOIN knowledge_versions v "
                + "ON v.tenant_id=d.tenant_id AND v.venue_id=d.venue_id AND v.document_id=d.document_id "
                + "AND v.version_id=d.current_version_id WHERE d.tenant_id=? AND d.venue_id=? AND d.document_id=? "
                + "AND d.state='ACTIVE' AND v.state='PUBLISHED' AND v.content IS NOT NULL",
                (rs, n) -> new PublishedVersion(new VersionMetadata(new VersionReference(key,
                        uuid(rs.getBytes("version_id")), rs.getString("content_digest"), Visibility.valueOf(rs.getString("visibility"))),
                        new Source(uuid(rs.getBytes("source_id")), rs.getString("source_reference"), rs.getString("source_title")),
                        VersionState.PUBLISHED, rs.getLong("staged_revision")), rs.getLong("revision"), rs.getString("content")),
                scope(key)).stream().findFirst();
    }
    @Override public Optional<VersionObservation> observe(DocumentKey key, UUID version) {
        return jdbc.query("SELECT v.version_id,v.content_digest,v.source_id,v.source_reference,v.source_title,v.visibility,"
                + "v.state,v.staged_revision,(v.content IS NOT NULL) payload_present,d.state document_state,d.revision,d.current_version_id FROM knowledge_versions v "
                + "JOIN knowledge_documents d ON d.tenant_id=v.tenant_id AND d.venue_id=v.venue_id AND d.document_id=v.document_id "
                + "WHERE v.tenant_id=? AND v.venue_id=? AND v.document_id=? AND v.version_id=?",
                (rs, n) -> {
                    VersionState state = VersionState.valueOf(rs.getString("state"));
                    DocumentState documentState = DocumentState.valueOf(rs.getString("document_state"));
                    UUID current = nullableUuid(rs.getBytes("current_version_id"));
                    return new VersionObservation(new VersionMetadata(new VersionReference(key, version,
                            rs.getString("content_digest"), Visibility.valueOf(rs.getString("visibility"))),
                            new Source(uuid(rs.getBytes("source_id")), rs.getString("source_reference"), rs.getString("source_title")),
                            state, rs.getLong("staged_revision")), documentState, rs.getLong("revision"), current,
                            documentState == DocumentState.ACTIVE && state == VersionState.PUBLISHED
                                    && version.equals(current) && rs.getBoolean("payload_present"));
                }, with(key, bytes(version))).stream().findFirst();
    }
    @Override public void versionState(DocumentKey key, UUID version, VersionState state) {
        jdbc.update("UPDATE knowledge_versions SET state=? WHERE tenant_id=? AND venue_id=? AND document_id=? AND version_id=?",
                state.name(), bytes(key.tenantId().value()), bytes(key.venueId().value()), bytes(key.documentId()), bytes(version));
    }
    @Override public void publication(DocumentKey key, DocumentState state, long revision, UUID currentVersion) {
        jdbc.update("UPDATE knowledge_documents SET state=?,revision=?,current_version_id=? "
                + "WHERE tenant_id=? AND venue_id=? AND document_id=?", state.name(), revision,
                currentVersion == null ? null : bytes(currentVersion), bytes(key.tenantId().value()),
                bytes(key.venueId().value()), bytes(key.documentId()));
    }
    @Override public void withdrawVersions(DocumentKey key) {
        jdbc.update("UPDATE knowledge_versions SET state='WITHDRAWN' WHERE tenant_id=? AND venue_id=? AND document_id=? "
                + "AND state IN ('STAGED','VALIDATED','PUBLISHED')", scope(key));
    }
    @Override public int cleanupPayloads(DocumentKey key) {
        return jdbc.update("UPDATE knowledge_versions SET content=NULL WHERE tenant_id=? AND venue_id=? AND document_id=? "
                + "AND content IS NOT NULL", scope(key));
    }
    private static Object[] scope(DocumentKey key) {
        return new Object[] {bytes(key.tenantId().value()), bytes(key.venueId().value()), bytes(key.documentId())};
    }
    private static Object[] with(DocumentKey key, Object... rest) {
        Object[] values = java.util.Arrays.copyOf(scope(key), 3 + rest.length);
        System.arraycopy(rest, 0, values, 3, rest.length); return values;
    }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
    private static UUID uuid(byte[] value) { var b = ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong()); }
    private static UUID nullableUuid(byte[] value) { return value == null ? null : uuid(value); }
}
