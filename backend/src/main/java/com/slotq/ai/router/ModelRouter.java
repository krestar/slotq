package com.slotq.ai.router;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** Pure snapshot computation. Its caller owns current authority, admission and disclosure approval. */
public final class ModelRouter {
    public enum DataClass { SYNTHETIC, PUBLIC_APPROVED, CONFIDENTIAL }
    public record Classification(DataClass dataClass, boolean disclosureApproved, boolean trainingAllowed,
                                 String requiredRegion, Integer maximumRetentionDays, int inputTokens, int outputTokens) { }
    public record Remaining(int attempts, long tokens, BigDecimal dollars, Instant observedAt, Instant deadline) { }
    public record Controls(boolean fixedEndpoint, boolean redirectsDisabled, boolean observableAttempts,
                           boolean hostedExecutionDisabled, Set<DataClass> allowedData, Boolean training,
                           String region, Integer retentionDays) {
        public Controls { allowedData = allowedData == null ? Set.of() : Set.copyOf(allowedData); }
    }
    public record Price(String revision, String tier, BigDecimal inputPerMillion, BigDecimal outputPerMillion,
                        String source, Instant observedAt) { }
    public record Measurement(String revision, String workload, int cases, int repeats, int attempts,
                              BigDecimal quality, int safetyViolations, BigDecimal p95Millis,
                              BigDecimal meanDollars, String costStatus, Instant measuredAt) { }
    public record Candidate(String id, String provider, String model, String apiMode, String endpoint, String resolvedVersion,
                            Boolean structuredOutput, Integer contextLimit, Integer outputLimit,
                            Controls controls, Price price, Measurement measurement) { }
    public record Policy(String revision, BigDecimal qualityFloor, BigDecimal qualityWeight,
                         BigDecimal latencyWeight, BigDecimal costWeight, BigDecimal latencyAnchorMillis,
                         BigDecimal costAnchorDollars, String tieBreak) {
        public static Policy bounded() {
            return new Policy("m7-router-v1", new BigDecimal("0.85"), new BigDecimal("0.60"),
                new BigDecimal("0.30"), new BigDecimal("0.10"), new BigDecimal("30000"),
                new BigDecimal("0.01"), "candidate-id-ascending");
        }
    }
    public record Snapshot(String workloadRevision, String workload, Classification classification,
                           List<Candidate> candidates, Policy policy, Remaining remaining) {
        public Snapshot { candidates = List.copyOf(candidates); }
    }
    public record Components(BigDecimal quality, BigDecimal latency, BigDecimal cost, BigDecimal total) { }
    public record Decision(Snapshot snapshot, Map<String, List<String>> excluded,
                           Map<String, Components> scores, String selected, String result, String tieBreak) {
        public Decision {
            excluded = Collections.unmodifiableMap(new TreeMap<>(excluded));
            scores = Collections.unmodifiableMap(new TreeMap<>(scores));
        }
    }

    public Decision route(Snapshot s) {
        validate(s);
        Map<String, List<String>> excluded = new TreeMap<>();
        Map<String, Components> scores = new TreeMap<>();
        for (Candidate c : s.candidates()) {
            List<String> reasons = exclusions(s, c);
            if (!reasons.isEmpty()) { excluded.put(c.id(), List.copyOf(reasons)); continue; }
            var p = s.policy();
            BigDecimal q = c.measurement().quality();
            BigDecimal l = normalize(c.measurement().p95Millis(), p.latencyAnchorMillis());
            BigDecimal cost = normalize(c.measurement().meanDollars(), p.costAnchorDollars());
            BigDecimal total = q.multiply(p.qualityWeight()).add(l.multiply(p.latencyWeight()))
                .add(cost.multiply(p.costWeight())).setScale(6, RoundingMode.HALF_EVEN);
            scores.put(c.id(), new Components(q, l, cost, total));
        }
        String selected = scores.keySet().stream().sorted(Comparator
            .<String, BigDecimal>comparing(id -> scores.get(id).total()).reversed()
            .thenComparing(Comparator.naturalOrder())).findFirst().orElse(null);
        return new Decision(s, excluded, scores, selected,
            scores.isEmpty() ? "no_candidate" : scores.size() == 1 ? "one_candidate" : "selected", s.policy().tieBreak());
    }

    public boolean replay(Decision decision) { return route(decision.snapshot()).equals(decision); }

    private static List<String> exclusions(Snapshot s, Candidate c) {
        List<String> r = new ArrayList<>();
        var in = s.classification(); var b = s.remaining(); var ctrl = c.controls(); var m = c.measurement(); var price = c.price();
        if (!"gemini".equals(c.provider()) || !GeminiAdapter.MODELS.contains(c.model())
                || !GeminiAdapter.API_MODE.equals(c.apiMode()) || !GeminiAdapter.ENDPOINT.equals(c.endpoint())) r.add("unsupported_provider_model_mode");
        if (!Boolean.TRUE.equals(c.structuredOutput())) r.add("structured_output_unavailable");
        if (c.contextLimit() == null || c.outputLimit() == null || c.contextLimit() <= 0 || c.outputLimit() <= 0)
            r.add("invalid_limits");
        else if (in.inputTokens() > c.contextLimit() || in.outputTokens() > c.outputLimit()) r.add("context_output_overflow");
        if (ctrl == null || !ctrl.fixedEndpoint() || !ctrl.redirectsDisabled() || !ctrl.observableAttempts()
                || !ctrl.hostedExecutionDisabled()) r.add("security_controls_unavailable");
        if (!in.disclosureApproved() || ctrl == null || !ctrl.allowedData().contains(in.dataClass())
                || ctrl.training() == null || (ctrl.training() && !in.trainingAllowed())
                || (in.requiredRegion() != null && !in.requiredRegion().equals(ctrl.region()))
                || (in.maximumRetentionDays() != null && (ctrl.retentionDays() == null
                    || ctrl.retentionDays() < 0 || ctrl.retentionDays() > in.maximumRetentionDays()))) r.add("disclosure_ineligible");
        if (ctrl != null && ctrl.retentionDays() != null && ctrl.retentionDays() < 0) r.add("invalid_retention");
        if (price == null || blank(price.revision()) || blank(price.source()) || price.observedAt() == null || price.observedAt().isAfter(b.observedAt())
                || !"free-attested".equals(price.tier()) || !nonnegative(price.inputPerMillion())
                || !nonnegative(price.outputPerMillion()) || price.inputPerMillion().signum() != 0
                || price.outputPerMillion().signum() != 0) r.add("price_or_free_tier_unavailable");
        if (m == null || blank(m.revision()) || !s.workload().equals(m.workload()) || m.cases() < 4
                || m.cases() > 64 || m.repeats() < 3 || m.repeats() > 10 || m.attempts() != (long)m.cases() * m.repeats()
                || m.measuredAt() == null || m.measuredAt().isAfter(b.observedAt())
                || !unit(m.quality()) || m.safetyViolations() < 0 || !positive(m.p95Millis())
                || !nonnegative(m.meanDollars()) || !Set.of("measured", "estimated").contains(String.valueOf(m.costStatus())))
            r.add("measurement_missing_invalid_or_unavailable");
        else {
            if (m.quality().compareTo(s.policy().qualityFloor()) < 0) r.add("quality_floor");
            if (m.safetyViolations() != 0) r.add("safety_floor");
        }
        if (b.attempts() < 1 || b.tokens() < (long) in.inputTokens() + in.outputTokens()
                || !b.deadline().isAfter(b.observedAt())) r.add("budget_or_deadline_exhausted");
        if (price != null && nonnegative(price.inputPerMillion()) && nonnegative(price.outputPerMillion())) {
            BigDecimal reserve = price.inputPerMillion().multiply(BigDecimal.valueOf(in.inputTokens()))
                .add(price.outputPerMillion().multiply(BigDecimal.valueOf(in.outputTokens())))
                .divide(BigDecimal.valueOf(1_000_000));
            if (reserve.compareTo(b.dollars()) > 0) r.add("cost_budget_exhausted");
        }
        return r;
    }

    private static void validate(Snapshot s) {
        if (s == null || blank(s.workloadRevision()) || blank(s.workload()) || s.classification() == null
                || s.classification().dataClass() == null || s.classification().inputTokens() < 1
                || s.classification().outputTokens() < 1 || s.candidates().size() > 16 || s.remaining() == null
                || !nonnegative(s.remaining().dollars()) || s.remaining().observedAt() == null || s.remaining().deadline() == null)
            throw new IllegalArgumentException("Invalid routing snapshot");
        var p = s.policy();
        if (p == null || !"m7-router-v1".equals(p.revision()) || !unit(p.qualityFloor()) || !unit(p.qualityWeight())
                || !unit(p.latencyWeight()) || !unit(p.costWeight()) || !positive(p.latencyAnchorMillis())
                || !positive(p.costAnchorDollars()) || p.qualityWeight().add(p.latencyWeight()).add(p.costWeight()).compareTo(BigDecimal.ONE) != 0
                || !"candidate-id-ascending".equals(p.tieBreak())) throw new IllegalArgumentException("Invalid routing policy");
        Set<String> ids = new HashSet<>();
        for (Candidate c : s.candidates()) if (c == null || blank(c.id()) || !ids.add(c.id()))
            throw new IllegalArgumentException("Invalid candidate identity");
    }
    private static BigDecimal normalize(BigDecimal value, BigDecimal anchor) {
        return BigDecimal.ONE.subtract(value.divide(anchor, 6, RoundingMode.HALF_EVEN)).max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean nonnegative(BigDecimal n) { return n != null && n.signum() >= 0; }
    private static boolean positive(BigDecimal n) { return n != null && n.signum() > 0; }
    private static boolean unit(BigDecimal n) { return nonnegative(n) && n.compareTo(BigDecimal.ONE) <= 0; }
}
