package com.slotq.mcp;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.slotq.mcp.M8CapacityEvidence.*;

/** The drill cannot start a restored writer until this exact frozen-input check succeeds. */
final class M8BackupProtocol {
    static final Set<String> REQUIRED = Set.of("backup", "emptyTarget", "sourceStopped", "sameRecoveryPoint",
        "schema", "binary", "configuration", "credentials", "securityAuthority", "securityReconciled", "databaseOracle");

    private M8BackupProtocol() { }

    static String resumeVerdict(Map<String, String> checks, Long rpoMillis, Long rtoMillis, long rpoLimit, long rtoLimit) {
        if (checks.values().stream().anyMatch("FAIL"::equals)
                || rpoMillis != null && (rpoMillis < 0 || rpoMillis > rpoLimit)
                || rtoMillis != null && (rtoMillis < 0 || rtoMillis > rtoLimit)) return "FAIL";
        if (rpoMillis == null || rtoMillis == null || !checks.keySet().containsAll(REQUIRED)
                || REQUIRED.stream().anyMatch(key -> !"PASS".equals(checks.get(key)))) return "INCOMPLETE";
        return "PASS";
    }

    static JsonNode plan(Path path, String expected, Instant now, int size, int rate) throws Exception {
        require(Files.isRegularFile(path) && digest(path).equals(expected), "Missing or changed backup plan");
        var plan = JSON.readTree(Files.readString(path));
        require(plan.path("schemaVersion").asString().equals("slotq-m8-backup-plan/v1")
            && plan.path("state").asString().equals("FROZEN")
            && Instant.parse(plan.path("frozenAt").asString()).isBefore(now), "Plan was not frozen before this run");
        require(size == 16 && rate == 20 || size == 64 && rate == 5, "Unsupported #181 profile");
        require(plan.path("restartReadyMillis").asLong() == 60000
            && plan.path("durableConvergenceMillis").asLong() == 90000
            && plan.path("postRecoveryQuietMillis").asLong() == 15000, "Changed #182 criteria");
        require(plan.path("rpoMillis").asLong() > 0 && plan.path("rtoMillis").asLong() > 0, "Missing RPO/RTO criteria");
        require(plan.path("method").asString().equals("QUIESCED_LOGICAL_DUMP")
            && plan.path("retentionDays").asInt() >= 90, "Missing backup and retention policy");
        for (String field : List.of("pilot", "capacity16", "capacity64", "recoveryPlan", "previousBinary", "candidateBinary")) {
            var evidence = plan.path(field);
            Path file = Path.of(evidence.path("path").asString());
            require(Files.isRegularFile(file) && digest(file).equals(evidence.path("sha256").asString()), "Unverified plan evidence: " + field);
        }
        var pilot = JSON.readTree(Files.readString(Path.of(plan.path("pilot").path("path").asString())));
        require(pilot.path("kind").asString().equals("PILOT_EXECUTION") && pilot.path("complete").asBoolean()
            && Instant.parse(pilot.path("endedAt").asString()).isBefore(Instant.parse(plan.path("frozenAt").asString())), "Pilot missing or newer than freeze");
        var source = plan.path("sourceDigests");
        require(source.isObject() && source.size() > 100, "Missing pilot source inventory");
        var currentFiles = new TreeSet<String>();
        try (var paths = Files.walk(Path.of("src"))) {
            paths.filter(Files::isRegularFile).forEach(file -> currentFiles.add(file.toString()));
        }
        currentFiles.add("build.gradle");
        require(new TreeSet<>(source.propertyNames()).equals(currentFiles), "Source inventory added or removed since pilot");
        for (String file : currentFiles) require(digest(Path.of(file)).equals(source.path(file).asString()), "Source changed since pilot: " + file);
        return plan;
    }

    static void artifact(Path path, String expected) throws Exception {
        require(expected != null && expected.matches("[0-9a-f]{64}")
            && Files.isRegularFile(path) && digest(path).equals(expected), "Missing or corrupt recovery artifact");
    }

    static void schema(org.springframework.jdbc.core.JdbcTemplate db) {
        require(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=FALSE", Long.class) == 0
            && db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='23' AND success=TRUE", Long.class) == 1
            && db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL", Long.class) == 23,
            "Unsupported schema for identified V1-V23 artifacts");
    }

    static void securityIdentity(JsonNode identity, String run, Instant point, Instant cutoff) {
        require(identity.path("runId").asString().equals(run)
            && identity.path("recoveryPoint").asString().equals(point.toString())
            && identity.path("sourceCutoff").asString().equals(cutoff.toString())
            && !cutoff.isBefore(point) && identity.path("sha256").asString().matches("[a-f0-9]{64}"),
            "Wrong or missing current-security recovery identity");
    }
}
