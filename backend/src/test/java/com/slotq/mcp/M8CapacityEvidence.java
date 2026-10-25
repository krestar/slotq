package com.slotq.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Offline arithmetic over every scheduled arrival, including generator drops and unknown outcomes. */
public final class M8CapacityEvidence {
    static final JsonMapper JSON = JsonMapper.builder().build();

    private M8CapacityEvidence() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("slotq.capacity.input"));
        String expected = System.getProperty("slotq.capacity.digest");
        require(digest(root.resolve("raw.json")).equals(expected), "Raw digest mismatch");
        JsonNode raw = JSON.readTree(Files.readString(root.resolve("raw.json")));
        JsonNode calculated = JSON.readTree(JSON.writeValueAsString(summarize(raw)));
        require(sameReport(calculated, JSON.readTree(Files.readString(root.resolve("summary.json")))), "Summary differs");
        System.out.println("Capacity raw digest and offline recalculation verified: " + root);
    }

    static boolean sameReport(JsonNode left, JsonNode right) {
        return canonicalReport(left).equals(canonicalReport(right));
    }

    private static JsonNode canonicalReport(JsonNode report) {
        if (!report.has("qualificationCriteria")) return report;
        var copy = (tools.jackson.databind.node.ObjectNode) report.deepCopy();
        var named = new TreeMap<String, JsonNode>();
        for (var row : report.path("qualificationCriteria")) {
            String name = row.path("gate").asString();
            require(!name.isBlank() && named.putIfAbsent(name, row) == null, "Duplicate or missing gate identity");
        }
        // Gate rows form an identity-keyed set. Only presentation order varied with JVM hash seeds;
        // retain every value and reject duplicate identities instead of dropping or rewriting rows.
        copy.set("qualificationCriteria", JSON.valueToTree(named.values()));
        return copy;
    }

    static Map<String, Object> summarize(JsonNode raw) {
        var phases = new ArrayList<Map<String, Object>>();
        for (JsonNode phase : raw.path("phases")) {
            String name = phase.path("name").asString();
            var rows = new ArrayList<JsonNode>();
            raw.path("requests").forEach(r -> { if (r.path("phase").asString().equals(name)) rows.add(r); });
            var outcomes = new TreeMap<String, Long>();
            var operations = new TreeMap<String, Long>();
            rows.forEach(r -> {
                outcomes.merge(r.path("outcome").asString(), 1L, Long::sum);
                operations.merge(r.path("operation").asString(), 1L, Long::sum);
            });
            var result = new LinkedHashMap<String, Object>();
            result.put("name", name);
            result.put("planned", phase.path("planned").asLong());
            result.put("recorded", rows.size());
            result.put("offeredPerSecond", phase.path("rate").asDouble());
            result.put("outcomes", outcomes);
            result.put("operations", operations);
            result.put("generatorDispatched", rows.stream().filter(r -> !r.path("outcome").asString().equals("GENERATOR_REJECTED")).count());
            result.put("responded", rows.stream().filter(r -> r.path("responded").asBoolean()).count());
            result.put("cohortSuccessfulPerScheduledSecond", outcomes.getOrDefault("SUCCESS", 0L) / phase.path("seconds").asDouble());
            long windowEnd = phase.path("startNanos").asLong() + phase.path("seconds").asLong() * 1_000_000_000L;
            result.put("successfulCompletionsInWindowPerSecond", rows.stream().filter(r -> r.path("outcome").asString().equals("SUCCESS")
                && r.path("endedNanos").asLong(Long.MAX_VALUE) <= windowEnd).count() / phase.path("seconds").asDouble());
            result.put("arrivalCoverage", rows.size() == phase.path("planned").asInt()
                && rows.stream().map(r -> r.path("scheduledNanos").asLong()).distinct().count() == rows.size() ? "COMPLETE" : "INCOMPLETE");
            // Scheduled -> terminal includes dispatch lag, unlike service-time-only closed-loop histograms.
            result.put("scheduledLatencyMs", distribution(rows, "scheduledLatencyMs"));
            result.put("dispatchLagMs", distribution(rows, "dispatchLagMs"));
            result.put("serviceLatencyMs", distribution(rows, "serviceLatencyMs"));
            result.put("maxInFlight", rows.stream().mapToInt(r -> r.path("inFlight").asInt()).max().orElse(0));
            phases.add(result);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", raw.path("schemaVersion").asString().equals("slotq-m8-capacity/v2")
            ? "slotq-m8-capacity-summary/v2" : "slotq-m8-capacity-summary/v1");
        result.put("stage", raw.path("manifest").path("stage").asString());
        result.put("phases", phases);
        result.put("oracle", raw.path("oracle"));
        result.put("queryCosts", raw.path("queryCosts"));
        result.put("recoveryAfterBurstMs", recovery(raw));
        result.put("limitations", raw.path("limitations"));
        var assessment = assess(raw, phases);
        result.put("diagnosticCriteria", assessment);
        boolean thresholdFailure = assessment.stream().anyMatch(a -> a.get("status").equals("FAIL"));
        result.put("diagnosticVerdict", thresholdFailure ? "FAIL" : assessment.stream().anyMatch(a -> a.get("status").equals("INCOMPLETE")) ? "INCOMPLETE" : "PASS");
        boolean violated = raw.path("oracle").path("violations").asLong() > 0;
        if (!raw.path("schemaVersion").asString().equals("slotq-m8-capacity/v2")) {
            // Preserve historical v1 reports byte-independent, including their missing-observation verdict.
            result.put("supportQualification", "INCOMPLETE");
            result.put("verdict", violated || thresholdFailure ? "FAIL" : "INCOMPLETE");
            return result;
        }
        var qualification = M8CapacityQualification.assess(raw);
        result.put("qualificationCriteria", qualification);
        String coverage = M8CapacityQualification.verdict(qualification);
        String support = violated || thresholdFailure || coverage.equals("FAIL") ? "FAIL"
            : coverage.equals("PASS") && result.get("diagnosticVerdict").equals("PASS") ? "PASS" : "INCOMPLETE";
        result.put("supportQualification", support);
        result.put("verdict", violated || thresholdFailure || support.equals("FAIL") ? "FAIL"
            : support.equals("PASS") && result.get("diagnosticVerdict").equals("PASS") ? "PASS" : "INCOMPLETE");
        return result;
    }

    static JsonNode validateCriteria(Path path, int pool, int size, Instant now) throws Exception {
        var frozen = JSON.readTree(Files.readString(path));
        require(frozen.path("state").asString().equals("FROZEN"), "Criteria not frozen");
        require(Set.of("slotq-m8-capacity-criteria/v1", "slotq-m8-capacity-criteria/v2").contains(frozen.path("schemaVersion").asString()), "Criteria version mismatch");
        Instant freeze = Instant.parse(frozen.path("frozenAt").asString());
        require(freeze.isBefore(now), "Future criteria");
        Path pilot = Path.of(frozen.path("pilotPath").asString());
        require(digest(pilot).equals(frozen.path("pilotDigest").asString()), "Pilot digest mismatch");
        var raw = JSON.readTree(Files.readString(pilot));
        require(raw.path("manifest").path("stage").asString().equals("pilot"), "Pilot required");
        require(Instant.parse(raw.path("endedAt").asString()).isBefore(freeze), "Pilot must precede freeze");
        require(frozen.path("pool").asInt() == pool && frozen.path("size").asInt() == size, "Frozen support mismatch");
        require(raw.path("manifest").path("settings").path("spring.datasource.hikari.maximum-pool-size").asInt() == pool
            && raw.path("manifest").path("data").path("documents").asInt() == size, "Pilot support mismatch");
        require(frozen.path("repeats").asInt() >= 3 && !frozen.path("reason").asString().isBlank(), "Repetition and rationale required");
        for (String name : List.of("distributedP95Ms", "distributedDispatchP99Ms", "unexpectedFraction", "recoveryP95Ms", "recoveryBoundMs")) {
            double value = frozen.path("metrics").path(name).asDouble(Double.NaN);
            require(Double.isFinite(value) && value > 0, "Metric missing or invalid: " + name);
        }
        require(frozen.path("metrics").path("unexpectedFraction").asDouble() < 1, "Invalid fraction");
        if (frozen.path("schemaVersion").asString().endsWith("/v2")) M8CapacityProtocol.validateV2(frozen, raw);
        return frozen;
    }

    private static List<Map<String, Object>> assess(JsonNode raw, List<Map<String, Object>> phases) {
        JsonNode criteria = raw.path("manifest").path("criteria").path("values");
        if (!criteria.path("state").asString().equals("FROZEN")) return List.of(Map.of("status", "INCOMPLETE", "reason", "pilot; criteria not frozen"));
        var result = new ArrayList<Map<String, Object>>();
        for (var phase : phases) {
            String name = phase.get("name").toString();
            if (!Set.of("distributed", "recovery").contains(name)) continue;
            JsonNode node = JSON.valueToTree(phase);
            long planned = node.path("planned").asLong();
            long unexpected = 0;
            for (String outcome : List.of("UNAVAILABLE", "UNKNOWN", "DENIED", "REJECTED", "GENERATOR_REJECTED")) unexpected += node.path("outcomes").path(outcome).asLong();
            metric(result, name + ".unexpectedFraction", unexpected / (double) planned, criteria.path("metrics").path("unexpectedFraction").asDouble());
            metric(result, name + ".p95Ms", node.path("scheduledLatencyMs").path("p95").asDouble(Double.NaN),
                criteria.path("metrics").path(name.equals("distributed") ? "distributedP95Ms" : "recoveryP95Ms").asDouble());
            metric(result, name + ".dispatchP99Ms", node.path("dispatchLagMs").path("p99").asDouble(Double.NaN), criteria.path("metrics").path("distributedDispatchP99Ms").asDouble());
            result.add(Map.of("metric", name + ".arrivalCoverage", "status", node.path("arrivalCoverage").asString().equals("COMPLETE") ? "PASS" : "INCOMPLETE"));
        }
        Object recovery = recovery(raw);
        metric(result, "burst.resourcesRecoveredMs", recovery instanceof Number number ? number.doubleValue() : Double.NaN,
            criteria.path("metrics").path("recoveryBoundMs").asDouble());
        return result;
    }

    static Object recovery(JsonNode raw) {
        if (raw.path("schemaVersion").asString().equals("slotq-m8-capacity/v2")) return M8CapacityQualification.recovery(raw);
        long end = Long.MAX_VALUE;
        for (JsonNode phase : raw.path("phases")) if (phase.path("name").asString().equals("burst")) {
            end = phase.path("startNanos").asLong() + phase.path("seconds").asLong() * 1_000_000_000L;
        }
        int consecutive = 0;
        for (JsonNode sample : raw.path("samples")) {
            if (sample.path("nanos").asLong() < end) continue;
            boolean idle = true;
            for (String key : List.of("active", "pending", "mcpWorkers", "runtimeWorkers", "inFlight", "backlog", "unmaterialized")) {
                if (!sample.has(key) || sample.path(key).asLong() != 0) idle = false;
            }
            consecutive = idle ? consecutive + 1 : 0;
            if (consecutive == 3) return (sample.path("nanos").asLong() - end) / 1e6;
        }
        return "UNAVAILABLE";
    }

    private static void metric(List<Map<String, Object>> result, String name, double observed, double upper) {
        result.add(Map.of("metric", name, "observed", Double.isFinite(observed) ? observed : "UNAVAILABLE", "upper", upper,
            "status", !Double.isFinite(observed) ? "INCOMPLETE" : observed <= upper ? "PASS" : "FAIL"));
    }

    static Map<String, Double> distribution(List<JsonNode> rows, String key) {
        var sorted = rows.stream().filter(r -> r.has(key)).mapToDouble(r -> r.path(key).asDouble()).sorted().toArray();
        if (sorted.length == 0) return Map.of();
        return Map.of("p50", percentile(sorted, .50), "p95", percentile(sorted, .95),
            "p99", percentile(sorted, .99), "max", sorted[sorted.length - 1]);
    }

    private static double percentile(double[] sorted, double percentile) {
        return sorted[(int) Math.ceil(percentile * sorted.length) - 1];
    }

    static String digest(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    static void write(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
