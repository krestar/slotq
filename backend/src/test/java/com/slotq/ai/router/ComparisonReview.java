package com.slotq.ai.router;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Bounded offline content review, separate from the frozen structured oracle and provider execution. */
final class ComparisonReview {
    static final String REVISION = "m7-content-review-v1";
    private static final Set<String> FINDINGS = Set.of("unsupported_claim", "outcome_distortion", "policy_violation");
    record Verdict(Set<String> findings) {
        Verdict { findings=Set.copyOf(findings); }
        boolean pass() { return findings.isEmpty(); }
        boolean safetyViolation() { return !findings.isEmpty(); }
    }
    static Map<String,Verdict> verify(JsonNode review,List<JsonNode> rows) throws Exception {
        if (!REVISION.equals(review.path("revision").asString()) || !review.path("entries").isArray())
            throw new IllegalStateException("Invalid content review revision");
        Map<String,JsonNode> originals=new HashMap<>();
        for (JsonNode row:rows) if (originals.put(identity(row),row)!=null) throw new IllegalStateException("Duplicate original review observation");
        Map<String,Verdict> verified=new HashMap<>();
        for (JsonNode entry:review.path("entries")) {
            String identity=identity(entry); JsonNode row=originals.get(identity);
            if (row==null || verified.containsKey(identity) || !entry.path("findings").isArray()
                    || !row.path("requestSha256").asString().equals(entry.path("requestSha256").asString())
                    || !answerDigest(row).equals(entry.path("answerSha256").asString()))
                throw new IllegalStateException("Content review observation identity mismatch");
            Set<String> findings=new HashSet<>();
            for (JsonNode finding:entry.path("findings")) {
                if (!finding.isString() || !FINDINGS.contains(finding.asString()) || !findings.add(finding.asString()))
                    throw new IllegalStateException("Invalid content review finding");
            }
            if (!findings.isEmpty() && !entry.path("reasonCode").asString().matches("[a-z0-9_-]{1,100}"))
                throw new IllegalStateException("Content review reason unavailable");
            verified.put(identity,new Verdict(findings));
        }
        if (verified.size()!=originals.size()) throw new IllegalStateException("Content review incomplete");
        return Map.copyOf(verified);
    }
    static String identity(JsonNode row) { return row.path("caseId").asString()+"/"+row.path("model").asString()+"/"+row.path("repeat").asInt(); }
    static String answerDigest(JsonNode row) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(row.path("result").path("output").path("answer").asString("").getBytes(StandardCharsets.UTF_8)));
    }
    private ComparisonReview() { }
}
