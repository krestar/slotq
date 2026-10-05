package com.slotq.knowledge.application;

import com.slotq.auth.access.*;
import com.slotq.availability.application.PublicVenueQuery;
import com.slotq.knowledge.domain.Corpus;
import com.slotq.knowledge.domain.Corpus.*;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.slotq.knowledge.application.CorpusFailure.Reason.*;

@Service
public class CorpusService implements CorpusAuthoring, CorpusCatalog {
    private final ActorAccess actors;
    private final PublicVenueQuery venues;
    private final CorpusStore store;
    private final TransactionTemplate writes, reads;

    public CorpusService(ActorAccess actors, PublicVenueQuery venues, CorpusStore store, PlatformTransactionManager manager) {
        this.actors = actors; this.venues = venues; this.store = store;
        writes = transaction(manager, false); reads = transaction(manager, true);
    }
    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        var tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setReadOnly(readOnly); tx.setTimeout(10);
        return tx;
    }

    @Override public DocumentMetadata inspect(String credential, VenueId venue, UUID document) {
        return read(() -> {
            DocumentKey key = authorKey(credential, venue, document);
            return store.document(key, false).orElse(new DocumentMetadata(key, DocumentState.UNPUBLISHED, 0, null));
        });
    }
    @Override public VersionMetadata stage(String credential, VenueId venue, VersionInput input, long expectedRevision) {
        return write(() -> {
            DocumentKey key = authorKey(credential, venue, input.documentId());
            store.createDocument(key);
            DocumentMetadata document = locked(credential, key);
            requireWritable(document);
            String digest = Corpus.digest(input.content());
            var existing = store.version(key, input.versionId());
            if (existing.isPresent()) {
                StoredVersion saved = existing.get();
                if (!saved.metadata().reference().contentDigest().equals(digest)
                        || !saved.expectedDigest().equals(input.expectedDigest())
                        || !saved.metadata().source().equals(input.source())
                        || saved.metadata().reference().visibility() != input.visibility()
                        || !input.content().equals(saved.content())) throw failure(IMMUTABLE_VERSION);
                return saved.metadata(); // exact input replay never advances revision or republishes history
            }
            requireRevision(document, expectedRevision);
            long revision = Math.addExact(document.revision(), 1);
            store.insertVersion(key, input, digest, revision);
            store.publication(key, document.state(), revision, document.currentVersionId());
            return stored(new VersionReference(key, input.versionId(), digest, input.visibility())).metadata();
        });
    }
    @Override public VersionMetadata validate(String credential, VersionReference reference) {
        return write(() -> {
            requireWritable(locked(credential, reference.document()));
            StoredVersion version = stored(reference);
            if (version.metadata().state() == VersionState.STAGED) {
                boolean valid = version.content() != null && Corpus.digest(version.content()).equals(version.expectedDigest())
                        && Corpus.digest(version.content()).equals(reference.contentDigest());
                store.versionState(reference.document(), reference.versionId(), valid ? VersionState.VALIDATED : VersionState.FAILED);
                return stored(reference).metadata();
            }
            return version.metadata();
        });
    }
    @Override public VersionMetadata publish(String credential, VersionReference reference) {
        return write(() -> {
            DocumentMetadata document = locked(credential, reference.document());
            requireWritable(document);
            VersionMetadata version = stored(reference).metadata();
            if (version.state() == VersionState.PUBLISHED && reference.versionId().equals(document.currentVersionId())) return version;
            if (version.state() != VersionState.VALIDATED) throw failure(INVALID_STATE);
            requireRevision(document, version.stagedRevision());
            if (document.currentVersionId() != null) {
                store.versionState(document.key(), document.currentVersionId(), VersionState.SUPERSEDED);
            }
            store.versionState(document.key(), reference.versionId(), VersionState.PUBLISHED);
            store.publication(document.key(), DocumentState.ACTIVE, Math.addExact(document.revision(), 1), reference.versionId());
            return stored(reference).metadata();
        });
    }
    @Override public DocumentMetadata withdraw(String credential, VenueId venue, UUID id, long expectedRevision) {
        return write(() -> {
            DocumentMetadata document = locked(credential, authorKey(credential, venue, id));
            if (document.state() == DocumentState.WITHDRAWN) return document;
            requireRevision(document, expectedRevision);
            store.withdrawVersions(document.key());
            store.publication(document.key(), DocumentState.WITHDRAWN, Math.addExact(document.revision(), 1), null);
            return store.document(document.key(), false).orElseThrow();
        });
    }
    @Override public CleanupResult cleanup(String credential, VenueId venue, UUID id) {
        return write(() -> {
            DocumentMetadata document = locked(credential, authorKey(credential, venue, id));
            if (document.state() != DocumentState.WITHDRAWN) throw failure(INVALID_STATE);
            return new CleanupResult(store.cleanupPayloads(document.key()), false);
        });
    }
    @Override public DerivedWork requestReindex(String credential, VersionReference reference) {
        return write(() -> {
            DocumentMetadata document = locked(credential, reference.document());
            if (!eligible(document, reference)) throw failure(INVALID_STATE);
            return new DerivedWork(reference, document.revision());
        });
    }
    @Override public boolean completeReindex(String credential, DerivedWork work) {
        return write(() -> {
            DocumentMetadata document = locked(credential, work.version().document());
            return document.revision() == work.documentRevision() && eligible(document, work.version());
            // No publication or derived artifact writes. #134 owns the concrete index.
        });
    }
    private boolean eligible(DocumentMetadata document, VersionReference reference) {
        return document.state() == DocumentState.ACTIVE && reference.versionId().equals(document.currentVersionId())
                && store.version(document.key(), reference.versionId())
                    .filter(v -> v.metadata().reference().equals(reference) && v.metadata().state() == VersionState.PUBLISHED
                            && v.content() != null).isPresent();
    }

    @Override public Optional<PublishedVersion> current(UUID delegation, VenueId venue, UUID document) {
        return read(() -> {
            DelegatedActor actor = reader(delegation, venue);
            DocumentKey key = new DocumentKey(actor.tenantId(), venue, document);
            // Single joined statement is the final metadata observation, after live scope validation.
            return store.currentPublished(key).filter(v -> visible(actor, v.metadata().reference().visibility()));
        });
    }
    @Override public Optional<PublishedVersion> revalidateExact(UUID delegation, VersionReference candidate) {
        return current(delegation, candidate.document().venueId(), candidate.document().documentId())
                .filter(v -> v.metadata().reference().equals(candidate));
    }
    @Override public Optional<VersionObservation> observeExact(UUID delegation, VersionReference candidate) {
        return read(() -> {
            DelegatedActor actor = reader(delegation, candidate.document().venueId());
            if (!actor.tenantId().equals(candidate.document().tenantId())) return Optional.empty();
            return store.observe(candidate.document(), candidate.versionId())
                    .filter(v -> v.metadata().reference().equals(candidate) && visible(actor, candidate.visibility()));
        });
    }
    private DelegatedActor reader(UUID delegation, VenueId venue) {
        DelegatedActor actor = actors.revalidate(delegation);
        if (!actor.venueId().equals(venue) || !actor.tenantId().equals(activeTenant(venue))
                || !actor.tools().contains("knowledge.search")
                || (!actor.actions().contains(AccessAction.KNOWLEDGE_PUBLIC)
                    && !(actor.profile() == AccessProfile.MANAGEMENT && actor.actions().contains(AccessAction.KNOWLEDGE_OPERATOR)))) {
            throw new AccessFailure(AccessFailure.Reason.FORBIDDEN);
        }
        return actor;
    }
    private boolean visible(DelegatedActor actor, Visibility visibility) {
        return visibility == Visibility.VENUE_PUBLIC ? actor.actions().contains(AccessAction.KNOWLEDGE_PUBLIC)
                : actor.profile() == AccessProfile.MANAGEMENT && actor.actions().contains(AccessAction.KNOWLEDGE_OPERATOR);
    }
    private DocumentKey authorKey(String credential, VenueId venue, UUID document) {
        actors.requireOriginalConfigurationAccess(credential, venue);
        return new DocumentKey(activeTenant(venue), venue, document);
    }
    private TenantId activeTenant(VenueId venue) {
        return venues.findActive(venue).orElseThrow(() -> failure(NOT_FOUND)).tenantId();
    }
    private DocumentMetadata locked(String credential, DocumentKey key) {
        if (!authorKey(credential, key.venueId(), key.documentId()).equals(key)) throw failure(SCOPE_MISMATCH);
        DocumentMetadata document = store.document(key, true).orElseThrow(() -> failure(NOT_FOUND));
        // Lock waits must not preserve an earlier authority/credential observation.
        if (!authorKey(credential, key.venueId(), key.documentId()).equals(key)) throw failure(SCOPE_MISMATCH);
        return document;
    }
    private StoredVersion stored(VersionReference reference) {
        StoredVersion version = store.version(reference.document(), reference.versionId()).orElseThrow(() -> failure(NOT_FOUND));
        if (!version.metadata().reference().equals(reference)) throw failure(IMMUTABLE_VERSION);
        return version;
    }
    private static void requireWritable(DocumentMetadata document) {
        if (document.state() == DocumentState.WITHDRAWN) throw failure(INVALID_STATE);
    }
    private static void requireRevision(DocumentMetadata document, long revision) {
        if (document.revision() != revision) throw failure(STALE_REVISION);
    }
    private static CorpusFailure failure(CorpusFailure.Reason reason) { return new CorpusFailure(reason); }
    private <T> T write(Supplier<T> work) { return writes.execute(status -> work.get()); }
    private <T> T read(Supplier<T> work) { return reads.execute(status -> work.get()); }
}
