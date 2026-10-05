package com.slotq.integration.mcp.knowledge;

import com.slotq.knowledge.domain.Corpus.*;
import java.time.*;
import java.util.*;

/** Bounded bag-of-terms baseline; no synonym expansion, stemming or Product policy inference. */
public final class LexicalSearch implements CandidateSearch {
    private final Clock clock;
    public LexicalSearch(Clock clock) { this.clock = clock; }
    static Set<String> terms(String text) {
        var result = new TreeSet<String>();
        for (String term : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
            if (!term.isEmpty() && !STOP.contains(term)) result.add(term);
        return result;
    }
    private static final Set<String> STOP = Set.of("a","an","the","is","are","was","to","from","and","of","in",
            "on","at","for","do","does","what","when","how","can","i","we","it","please","about");
    @Override public List<Hit> search(List<PublishedVersion> eligible, String query, Instant deadline) {
        Set<String> wanted = terms(query); var hits = new ArrayList<Hit>();
        for (var version : eligible) {
            check(deadline); Set<String> actual = terms(version.content());
            long overlap = wanted.stream().filter(actual::contains).count();
            if (overlap > 0) hits.add(new Hit(version.metadata().reference(), (double) overlap / Math.max(1, wanted.size())));
        }
        check(deadline);
        return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(h -> h.version().document().documentId())).toList();
    }
    private void check(Instant deadline) { if (!clock.instant().isBefore(deadline)) throw new RetrievalFailure(true); }
}
