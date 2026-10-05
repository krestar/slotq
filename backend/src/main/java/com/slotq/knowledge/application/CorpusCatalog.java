package com.slotq.knowledge.application;

import com.slotq.knowledge.domain.Corpus.*;
import com.slotq.venue.domain.VenueId;
import java.util.Optional;
import java.util.UUID;

/** #134 metadata authority: every call derives live scope and reads newly committed state. */
public interface CorpusCatalog {
    Optional<PublishedVersion> current(UUID delegationId, VenueId venue, UUID document);
    Optional<PublishedVersion> revalidateExact(UUID delegationId, VersionReference candidate);
    /** Scoped lifecycle metadata, including ineligible superseded/withdrawn versions; never their payload. */
    Optional<VersionObservation> observeExact(UUID delegationId, VersionReference candidate);
}
