package com.slotq.integration.mcp.knowledge;

import com.slotq.knowledge.domain.Corpus.*;
import java.time.Instant;
import java.util.List;

/** Receives only the already authorized current set. No global corpus or authority writes. */
@FunctionalInterface
public interface CandidateSearch {
    List<Hit> search(List<PublishedVersion> eligible, String query, Instant deadline);
    record Hit(VersionReference version, double score) {
        public Hit { if (!Double.isFinite(score)) throw new IllegalArgumentException("Finite score required"); }
    }
}
