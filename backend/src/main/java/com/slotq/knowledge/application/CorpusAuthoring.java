package com.slotq.knowledge.application;

import com.slotq.knowledge.domain.Corpus.*;
import com.slotq.venue.domain.VenueId;
import java.util.UUID;

/** Controlled original-Actor adapter; no upload endpoint or delegated authoring. */
public interface CorpusAuthoring {
    DocumentMetadata inspect(String originalCredential, VenueId venue, UUID document);
    VersionMetadata stage(String originalCredential, VenueId venue, VersionInput input, long expectedRevision);
    VersionMetadata validate(String originalCredential, VersionReference version);
    VersionMetadata publish(String originalCredential, VersionReference version);
    DocumentMetadata withdraw(String originalCredential, VenueId venue, UUID document, long expectedRevision);
    CleanupResult cleanup(String originalCredential, VenueId venue, UUID document);
    DerivedWork requestReindex(String originalCredential, VersionReference version);
    boolean completeReindex(String originalCredential, DerivedWork work);
}
