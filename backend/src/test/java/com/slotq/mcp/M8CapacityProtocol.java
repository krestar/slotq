package com.slotq.mcp;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.mcp.M8CapacityEvidence.*;

/** Local-only freeze and repetition audit for the capacity runner, never an execution substitute. */
public final class M8CapacityProtocol {
    private M8CapacityProtocol() { }

    public static void main(String[] args) throws Exception {
        String action = System.getProperty("slotq.capacity.action");
        Path input = Path.of(System.getProperty("slotq.capacity.input")).toAbsolutePath().normalize();
        Path output = Path.of(System.getProperty("slotq.capacity.output")).toAbsolutePath().normalize();
        require(input.startsWith(Path.of("build").toAbsolutePath().normalize()) && output.startsWith(Path.of("build").toAbsolutePath().normalize()), "Use ignored local build paths");
        JsonNode result = switch (action) {
            case "freeze" -> freeze(input, Instant.now());
            case "series" -> series(input);
            default -> throw new IllegalArgumentException("Unknown protocol action");
        };
        Files.createDirectories(output.getParent());
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println(action + " written without replacing existing evidence: " + output);
    }

    static JsonNode freeze(Path plan, Instant now) throws Exception {
        var pending = (ObjectNode) JSON.readTree(Files.readString(plan));
        require(pending.path("state").asString().equals("PENDING"), "Pending local plan required");
        require(pending.path("schemaVersion").asString().equals("slotq-m8-capacity-criteria/v2"), "Criteria v2 required");
        Path pilotPath = Path.of(pending.path("pilotPath").asString());
        require(digest(pilotPath).equals(pending.path("pilotDigest").asString()), "Pilot digest mismatch");
        var pilot = JSON.readTree(Files.readString(pilotPath));
        require(pilot.path("schemaVersion").asString().equals("slotq-m8-capacity/v2") && pilot.path("manifest").path("stage").asString().equals("pilot"), "Observed v2 pilot required");
        require(Instant.parse(pilot.path("endedAt").asString()).isBefore(now), "Pilot must precede freeze");
        require(!pending.path("reason").asString().isBlank() && !pending.path("unsupported").isEmpty(), "Rationale and unsupported scope required");
        pending.put("state", "FROZEN"); pending.put("frozenAt", now.toString());
        pending.put("planDigest", digest(plan));
        pending.set("sourceDigests", pilot.path("manifest").path("sourceDigests"));
        validateV2(pending, pilot);
        return pending;
    }

    static void validateV2(JsonNode frozen, JsonNode pilot) {
        require(frozen.path("pool").asInt() == pilot.path("manifest").path("settings").path("spring.datasource.hikari.maximum-pool-size").asInt()
            && frozen.path("size").asInt() == pilot.path("manifest").path("data").path("documents").asInt()
            && frozen.path("rate").asInt() == pilot.path("manifest").path("targetRate").asInt()
            && frozen.path("seed").asLong(-1) == pilot.path("manifest").path("data").path("seed").asLong(-2), "Pilot support mismatch");
        require(frozen.path("repeats").asInt() >= 3 && !frozen.path("reason").asString().isBlank(), "Repetition and rationale required");
        for (String metric : List.of("distributedP95Ms", "distributedDispatchP99Ms", "unexpectedFraction", "recoveryP95Ms", "recoveryBoundMs",
                "queryP95Ms", "availabilityMaxSql", "managementMaxSql", "knowledgeMaxSql", "queryMaxBytes", "normalPendingMax", "normalBacklogMax",
                "normalUnmaterializedMax", "heapMaxBytes", "processCpuMax"))
            require(frozen.path("metrics").path(metric).asDouble(-1) > 0 && Double.isFinite(frozen.path("metrics").path(metric).asDouble()), "Invalid metric: " + metric);
        require(frozen.path("metrics").path("unexpectedFraction").asDouble() < 1, "Invalid fraction");
        require(frozen.path("sourceDigests").equals(pilot.path("manifest").path("sourceDigests")) && frozen.path("sourceDigests").size() > 0, "Source linkage required");
    }

    static JsonNode series(Path index) throws Exception {
        var specification = JSON.readTree(Files.readString(index));
        Path criteriaPath = Path.of(specification.path("criteriaPath").asString());
        require(digest(criteriaPath).equals(specification.path("criteriaDigest").asString()), "Criteria digest mismatch");
        var frozen = JSON.readTree(Files.readString(criteriaPath));
        validateCriteria(criteriaPath, frozen.path("pool").asInt(), frozen.path("size").asInt(), Instant.now());
        var results = new ArrayList<Map<String, Object>>();
        var runIds = new HashSet<String>(); var containers = new HashSet<String>();
        boolean incomplete = false, failed = false;
        for (var entry : specification.path("runs")) {
            Path root = Path.of(entry.path("path").asString());
            require(digest(root.resolve("raw.json")).equals(entry.path("digest").asString()), "Raw digest mismatch");
            var raw = JSON.readTree(Files.readString(root.resolve("raw.json")));
            var manifest = raw.path("manifest");
            require(manifest.path("criteria").path("values").equals(frozen)
                && manifest.path("criteria").path("sha256").asString().equals(digest(criteriaPath)), "Frozen criteria changed");
            require(manifest.path("sourceDigests").equals(frozen.path("sourceDigests")), "Source changed");
            require(Instant.parse(manifest.path("startedAt").asString()).isAfter(Instant.parse(frozen.path("frozenAt").asString())), "Verification must follow freeze");
            require(manifest.path("stage").asString().equals("verification") && manifest.path("kind").asString().equals("EXECUTION"), "Independent verification required");
            require(manifest.path("targetRate").asInt() == frozen.path("rate").asInt()
                && manifest.path("data").path("seed").asLong(-1) == frozen.path("seed").asLong(-2)
                && manifest.path("data").path("documents").asInt() == frozen.path("size").asInt()
                && manifest.path("settings").path("spring.datasource.hikari.maximum-pool-size").asInt() == frozen.path("pool").asInt(), "Support profile changed");
            require(!manifest.path("runId").asString().isBlank() && runIds.add(manifest.path("runId").asString())
                && !manifest.path("containerId").asString().isBlank() && containers.add(manifest.path("containerId").asString()), "Independent run/container identity required");
            var report = JSON.readTree(JSON.writeValueAsString(summarize(raw)));
            require(sameReport(report, JSON.readTree(Files.readString(root.resolve("summary.json")))), "Saved summary differs");
            String verdict = report.path("verdict").asString();
            incomplete |= verdict.equals("INCOMPLETE"); failed |= verdict.equals("FAIL");
            results.add(Map.of("path", root.toString(), "digest", entry.path("digest").asString(), "verdict", verdict));
        }
        incomplete |= results.size() < frozen.path("repeats").asInt();
        var scorerDigests = new TreeMap<String, String>();
        for (String name : List.of("M8CapacityEvidence", "M8CapacityQualification", "M8CapacityProtocol")) {
            Path source = Path.of("src/test/java/com/slotq/mcp/" + name + ".java");
            if (Files.exists(source)) scorerDigests.put(name, digest(source));
        }
        return JSON.valueToTree(Map.of("kind", "RECALCULATION", "comparison", "unique-gate-identity-and-all-values/v1", "scorerDigests", scorerDigests,
            "criteriaDigest", digest(criteriaPath), "runs", results,
            "verdict", failed ? "FAIL" : incomplete ? "INCOMPLETE" : "PASS", "scope", frozen.path("unsupported")));
    }
}
