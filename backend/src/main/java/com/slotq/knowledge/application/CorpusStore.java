package com.slotq.knowledge.application;

import com.slotq.knowledge.domain.Corpus.*;
import java.util.Optional;
import java.util.UUID;

/** Module-owned persistence port. Mutations run under the document row lock. */
public interface CorpusStore {
    void createDocument(DocumentKey key);
    Optional<DocumentMetadata> document(DocumentKey key, boolean lock);
    Optional<StoredVersion> version(DocumentKey key, UUID version);
    Optional<PublishedVersion> currentPublished(DocumentKey key);
    Optional<VersionObservation> observe(DocumentKey key, UUID version);
    void insertVersion(DocumentKey key, VersionInput input, String digest, long revision);
    void versionState(DocumentKey key, UUID version, VersionState state);
    void publication(DocumentKey key, DocumentState state, long revision, UUID currentVersion);
    void withdrawVersions(DocumentKey key);
    int cleanupPayloads(DocumentKey key);
}
