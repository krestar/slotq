package com.slotq.knowledge.domain;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Knowledge identities are independent of Product BookingPolicy versions. */
public final class Corpus {
    private Corpus() { }
    public enum Visibility { VENUE_PUBLIC, VENUE_OPERATOR }
    public enum DocumentState { UNPUBLISHED, ACTIVE, WITHDRAWN }
    public enum VersionState { STAGED, VALIDATED, FAILED, PUBLISHED, SUPERSEDED, WITHDRAWN }

    public record DocumentKey(TenantId tenantId, VenueId venueId, UUID documentId) {
        public DocumentKey {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(venueId); Objects.requireNonNull(documentId);
        }
    }
    public record Source(UUID sourceId, String reference, String title) {
        public Source {
            Objects.requireNonNull(sourceId);
            bounded(reference, 512); bounded(title, 160);
        }
    }
    public record VersionInput(UUID documentId, UUID versionId, Source source, Visibility visibility,
                               String content, String expectedDigest) {
        public VersionInput {
            Objects.requireNonNull(documentId); Objects.requireNonNull(versionId);
            Objects.requireNonNull(source); Objects.requireNonNull(visibility);
            if (content == null || content.isBlank() || content.length() > 16384
                    || content.getBytes(StandardCharsets.UTF_8).length > 65535
                    || expectedDigest == null || !expectedDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid bounded corpus input");
            }
        }
        @Override public String toString() { return "VersionInput[redacted]"; }
    }
    public record VersionReference(DocumentKey document, UUID versionId, String contentDigest, Visibility visibility) {
        public VersionReference {
            Objects.requireNonNull(document); Objects.requireNonNull(versionId); Objects.requireNonNull(visibility);
            if (contentDigest == null || !contentDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid content identity");
            }
        }
    }
    public record DocumentMetadata(DocumentKey key, DocumentState state, long revision, UUID currentVersionId) { }
    public record VersionMetadata(VersionReference reference, Source source, VersionState state, long stagedRevision) { }
    public record VersionObservation(VersionMetadata metadata, DocumentState documentState, long revision,
                                     UUID currentVersionId, boolean retrievalEligible) { }
    /** Payload remains immutable until explicit local cleanup of a withdrawn document. */
    public record StoredVersion(VersionMetadata metadata, String expectedDigest, String content) {
        @Override public String toString() { return "StoredVersion[redacted]"; }
    }
    public record PublishedVersion(VersionMetadata metadata, long documentRevision, String content) {
        @Override public String toString() { return "PublishedVersion[redacted]"; }
    }
    /** An eligibility observation, not a durable job, credential, or publication command. */
    public record DerivedWork(VersionReference version, long documentRevision) { }
    public record CleanupResult(int localPayloadsRemoved, boolean externalDeletionVerified) { }

    public static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void bounded(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid bounded source metadata");
        }
    }
}
