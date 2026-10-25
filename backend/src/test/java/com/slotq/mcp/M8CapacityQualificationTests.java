package com.slotq.mcp;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.*;
import static org.assertj.core.api.Assertions.*;
import static com.slotq.mcp.M8CapacityEvidence.*;

/** Entirely invented arithmetic vectors. These values and PASS never represent a capacity execution. */
class M8CapacityQualificationTests {
    @TempDir Path root;

    @Test void completePositiveEvidenceCanQualifyWithoutAnUnconditionalIncomplete() {
        assertThat(summarize(vector()).get("verdict")).isEqualTo("PASS");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 250_000_000L})
    void observationGapBeforeThreeIdleSamplesCannotQualifyRecovery(long observedPrefixNanos) {
        var raw = vector();
        var samples = (ArrayNode) raw.path("samples");
        for (int i = samples.size() - 1; i >= 0; i--) {
            if (samples.get(i).path("phase").asString().equals("burst-quiet")) {
                samples.remove(i);
            }
        }
        long burstEnd = 1_000_000_000L;
        if (observedPrefixNanos > 0) {
            var busy = sample("burst-quiet", burstEnd + observedPrefixNanos);
            busy.put("pending", 1);
            samples.add(busy);
        }
        long firstIdle = burstEnd + observedPrefixNanos + 1_000_000_001L;
        for (int i = 0; i < 3; i++) {
            samples.add(sample("burst-quiet", firstIdle + i * 250_000_000L));
        }

        assertThat(M8CapacityQualification.recovery(raw)).isEqualTo("UNAVAILABLE");
        var report = summarize(raw);
        assertThat(report.get("supportQualification")).isEqualTo("INCOMPLETE");
        assertThat(report.get("verdict")).isEqualTo("INCOMPLETE");
    }

    @Test void exactlyOneSecondSamplingIntervalPreservesObservedRecoveryPass() {
        var raw = vector();
        long at = 2_000_000_000L;
        for (var sample : raw.path("samples")) {
            if (sample.path("phase").asString().equals("burst-quiet")) {
                ((ObjectNode) sample).put("nanos", at);
                at += 250_000_000L;
            }
        }

        assertThat(M8CapacityQualification.recovery(raw)).isEqualTo(1500.0);
        assertThat(summarize(raw).get("verdict")).isEqualTo("PASS");
    }

    @Test void gatePresentationOrderDoesNotChangeRecalculationButEveryValueAndIdentityIsRequired() {
        var original = JSON.readTree(JSON.writeValueAsString(summarize(vector())));
        var reordered = (ObjectNode) original.deepCopy();
        var rows = M8CapacityQualification.list(original.path("qualificationCriteria"));
        Collections.reverse(rows);
        reordered.set("qualificationCriteria", JSON.valueToTree(rows));
        assertThat(sameReport(original, reordered)).isTrue();
        ((ObjectNode) reordered.path("qualificationCriteria").get(0)).put("status", "FAIL");
        assertThat(sameReport(original, reordered)).isFalse();
        reordered.set("qualificationCriteria", JSON.valueToTree(rows.subList(1, rows.size())));
        assertThat(sameReport(original, reordered)).isFalse();
        rows.add(rows.getFirst()); reordered.set("qualificationCriteria", JSON.valueToTree(rows));
        assertThatThrownBy(() -> sameReport(original, reordered)).hasMessageContaining("Duplicate");
    }

    @Test void latencyOrErrorFailureAlsoFailsTheSupportQualificationField() {
        var raw = vector(); ((ObjectNode) M8CapacityQualification.find(raw.path("requests"), "phase", "distributed")).put("outcome", "UNKNOWN");
        assertThat(summarize(raw).get("supportQualification")).isEqualTo("FAIL");
    }

    @ParameterizedTest @ValueSource(strings = {"samples", "resourceSamples", "probes", "acquisitions", "authCalls", "queryCosts", "http", "tools", "providers", "backgroundActions"})
    void missingMandatoryObservationCannotPass(String field) {
        var raw = vector(); raw.remove(field);
        assertThat(summarize(raw).get("verdict")).isEqualTo("INCOMPLETE");
    }

    @ParameterizedTest @ValueSource(strings = {"active", "idle", "pending", "inFlight", "mcpWorkers", "runtimeWorkers", "observationWorker", "mcpPermits", "runtimePermits", "mcpIngress", "backlog", "unmaterialized", "lockWaits", "httpThreads", "heapBytes", "processCpuLoad", "systemCpuLoad", "hostFreeRamBytes"})
    void missingOneRequiredSensorRemainsIncomplete(String field) {
        var raw = vector(); ((ObjectNode) raw.path("samples").get(0)).remove(field);
        assertThat(summarize(raw).get("verdict")).isEqualTo("INCOMPLETE");
    }

    @Test void emptyDockerObjectsAndMissingHostCannotQualify() {
        var raw = vector(); ((ObjectNode) raw.path("resourceSamples").get(0)).set("docker", JSON.createObjectNode());
        assertThat(summarize(raw).get("verdict")).isEqualTo("INCOMPLETE");
        raw = vector(); ((ObjectNode) raw.path("manifest")).remove("host");
        assertThat(summarize(raw).get("verdict")).isEqualTo("INCOMPLETE");
    }

    @Test void observedSecurityViolationDominatesOtherMissingEvidence() {
        var raw = vector(); raw.remove("samples");
        ((ObjectNode) raw.path("probes").get(0)).put("httpStatus", 201);
        assertThat(summarize(raw).get("verdict")).isEqualTo("FAIL");
    }

    @Test void unknownCannotBeRelabeledAsSuccessAndReceiptMustCommitWithDone() {
        var raw = vector();
        ((ObjectNode) raw.path("probes").get(3)).put("originalOutcome", "SUCCESS");
        assertThat(summarize(raw).get("verdict")).isEqualTo("FAIL");
        raw = vector();
        ((ObjectNode) raw.path("probes").get(2).path("commit").path("receiptAfterRetry")).put("done", 0);
        assertThat(summarize(raw).get("verdict")).isEqualTo("FAIL");
    }

    @Test void observedExceededRecoveryIsFailButMissingOrConfoundedWindowIsIncomplete() {
        var raw = vector(); var samples = (ArrayNode) raw.path("samples"); samples.removeAll();
        for (int i = 1; i <= 45; i++) { var s = sample("burst-quiet", 1_000_000_000L + i * 250_000_000L); s.put("pending", 1); samples.add(s); }
        assertThat(((Number) M8CapacityQualification.recovery(raw)).doubleValue()).isGreaterThan(10_000);
        assertThat(summarize(raw).get("verdict")).isEqualTo("FAIL");
        ((ObjectNode) samples.get(4)).remove("runtimePermits");
        assertThat(M8CapacityQualification.recovery(raw)).isEqualTo("UNAVAILABLE");
        samples.removeAll();
        for (int i = 1; i <= 45; i++) samples.add(sample("recovery", 1_000_000_000L + i * 250_000_000L));
        assertThat(M8CapacityQualification.recovery(raw)).isEqualTo("UNAVAILABLE");
    }

    @Test void freezeRequiresRealPriorPilotLinkageAndDoesNotChangeTheMetrics() throws Exception {
        var pilot = vector(); ((ObjectNode) pilot.path("manifest")).put("stage", "pilot");
        write(root.resolve("pilot.json"), pilot);
        var plan = (ObjectNode) pilot.path("manifest").path("criteria").path("values").deepCopy();
        plan.put("state", "PENDING"); plan.put("pilotPath", root.resolve("pilot.json").toString());
        plan.put("pilotDigest", digest(root.resolve("pilot.json"))); plan.put("pool", 4); plan.put("size", 4); plan.put("rate", 1);
        plan.put("seed", 1); plan.put("repeats", 3); plan.put("reason", "invented test rationale"); plan.set("unsupported", JSON.valueToTree(List.of("all production claims")));
        write(root.resolve("plan.json"), plan);
        var frozen = M8CapacityProtocol.freeze(root.resolve("plan.json"), Instant.parse("2026-01-03T00:00:00Z"));
        assertThat(frozen.path("metrics")).isEqualTo(plan.path("metrics"));
        Files.writeString(root.resolve("pilot.json"), "{}");
        assertThatThrownBy(() -> M8CapacityProtocol.freeze(root.resolve("plan.json"), Instant.parse("2026-01-03T00:00:00Z"))).hasMessageContaining("digest");
    }

    @Test void seriesRequiresIndependentRepetitionsAndUnmodifiedRawAndCriteria() throws Exception {
        var pilot = vector(); ((ObjectNode) pilot.path("manifest")).put("stage", "pilot");
        write(root.resolve("pilot.json"), pilot);
        var plan = (ObjectNode) pilot.path("manifest").path("criteria").path("values").deepCopy();
        plan.put("state", "PENDING"); plan.put("pilotPath", root.resolve("pilot.json").toString()); plan.put("pilotDigest", digest(root.resolve("pilot.json")));
        plan.put("pool", 4); plan.put("size", 4); plan.put("rate", 1); plan.put("seed", 1); plan.put("repeats", 3); plan.put("reason", "invented rationale");
        plan.set("unsupported", JSON.valueToTree(List.of("production"))); write(root.resolve("plan.json"), plan);
        var frozen = M8CapacityProtocol.freeze(root.resolve("plan.json"), Instant.parse("2026-01-03T00:00:00Z"));
        write(root.resolve("frozen.json"), frozen);
        var index = JSON.createObjectNode(); index.put("criteriaPath", root.resolve("frozen.json").toString()); index.put("criteriaDigest", digest(root.resolve("frozen.json")));
        var runs = index.putArray("runs");
        for (int i = 0; i < 3; i++) {
            var raw = vector(); var manifest = (ObjectNode) raw.path("manifest");
            ((ObjectNode) manifest.path("criteria")).set("values", frozen); ((ObjectNode) manifest.path("criteria")).put("sha256", digest(root.resolve("frozen.json")));
            manifest.put("startedAt", "2026-01-04T00:00:00Z"); manifest.put("runId", "run-" + i); manifest.put("containerId", "container-" + i);
            Path dir = Files.createDirectory(root.resolve("run-" + i)); write(dir.resolve("raw.json"), raw); write(dir.resolve("summary.json"), summarize(raw));
            runs.add(JSON.valueToTree(Map.of("path", dir.toString(), "digest", digest(dir.resolve("raw.json")))));
        }
        write(root.resolve("index.json"), index);
        assertThat(M8CapacityProtocol.series(root.resolve("index.json")).path("verdict").asString()).isEqualTo("PASS");
        runs.remove(2); write(root.resolve("short.json"), index);
        assertThat(M8CapacityProtocol.series(root.resolve("short.json")).path("verdict").asString()).isEqualTo("INCOMPLETE");
        runs.add(runs.get(0)); write(root.resolve("duplicate.json"), index);
        assertThatThrownBy(() -> M8CapacityProtocol.series(root.resolve("duplicate.json"))).hasMessageContaining("identity");
        Files.writeString(root.resolve("run-0/raw.json"), "{}");
        assertThatThrownBy(() -> M8CapacityProtocol.series(root.resolve("index.json"))).hasMessageContaining("digest");
    }

    private static ObjectNode vector() {
        var r = JSON.createObjectNode(); r.put("schemaVersion", "slotq-m8-capacity/v2"); r.put("endedAt", "2026-01-01T00:00:00Z");
        var c = Map.of("schemaVersion", "slotq-m8-capacity-criteria/v2", "state", "FROZEN", "metrics", Map.of(
            "distributedP95Ms", 2000, "distributedDispatchP99Ms", 100, "unexpectedFraction", .05, "recoveryP95Ms", 1000, "recoveryBoundMs", 10000,
            "queryP95Ms", 1000, "availabilityMaxSql", 100, "managementMaxSql", 100, "knowledgeMaxSql", 100, "queryMaxBytes", 100000));
        r.set("manifest", JSON.valueToTree(Map.of("stage", "verification", "kind", "EXECUTION", "measured", true, "criteria", Map.of("values", c, "sha256", "synthetic"),
            "targetRate", 1, "settings", Map.of("spring.datasource.hikari.maximum-pool-size", 4), "data", Map.of("documents", 4, "seed", 1), "sourceDigests", Map.of("invented", "test-only"))));
        var metric = (ObjectNode) r.path("manifest").path("criteria").path("values").path("metrics");
        for (String key : List.of("normalPendingMax", "normalBacklogMax", "normalUnmaterializedMax", "heapMaxBytes", "processCpuMax")) metric.put(key, 1);
        var manifest = (ObjectNode) r.path("manifest");
        manifest.set("host", JSON.valueToTree(Map.of("cpu", "invented", "processors", 1, "ramBytes", 1, "storageFreeBytes", 1, "network", "invented")));
        manifest.put("java", "test-only"); manifest.set("jvmFlags", JSON.valueToTree(List.of("test-only")));
        manifest.set("docker", JSON.valueToTree(Map.of("imageId", "invented", "cpus", 1, "memoryBytes", 1)));
        manifest.set("mysql", JSON.valueToTree(Map.of("version", "invented", "isolation_level", "REPEATABLE-READ", "buffer_pool_bytes", 1)));
        manifest.put("runId", "invented"); manifest.put("containerId", "invented");
        r.set("backgroundActions", JSON.valueToTree(List.of(Map.of("operation", "background-cancel", "outcome", "SUCCESS"))));
        var phases = r.putArray("phases"); var requests = r.putArray("requests"); var http = r.putArray("http"); var tools = r.putArray("tools");
        int sequence = 0;
        for (String name : List.of("generator-calibration", "warmup", "distributed", "probes", "hot-slot", "burst", "recovery")) {
            phases.add(JSON.valueToTree(Map.of("name", name, "rate", 6, "seconds", 1, "planned", 6, "startNanos", 0, "echoReceived", 6)));
            for (String operation : List.of("product-hold", "mcp-management", "product-get", "availability", "runtime-synthetic", "mcp-observations")) {
                String id = "test-" + sequence++;
                var row = JSON.createObjectNode(); row.put("phase", name); row.put("operation", operation); row.put("trace", id); row.put("wireRequestId", id);
                row.put("scheduledNanos", sequence); row.put("endedNanos", sequence + 1); row.put("scheduledLatencyMs", 1); row.put("dispatchLagMs", 1);
                row.put("outcome", "SUCCESS"); row.put("responded", true); requests.add(row);
                http.add(JSON.valueToTree(Map.of("trace", id, "authenticatedHandler", true)));
                tools.add(JSON.valueToTree(Map.of("requestId", id)));
            }
        }
        var samples = r.putArray("samples"); var resources = r.putArray("resourceSamples");
        for (String phase : List.of("distributed", "hot-slot", "burst", "burst-quiet", "recovery", "query-scale")) {
            for (int i = 1; i <= 3; i++) samples.add(sample(phase, phase.equals("burst-quiet") ? 1_000_000_000L + i * 250_000_000L : 0));
            resources.add(JSON.valueToTree(Map.of("phase", phase, "status", "OBSERVED", "mysqlStatus", Collections.nCopies(9, Map.of()),
                "mysqlFileIo", Map.of("read_picoseconds", 1), "databaseBytes", 1, "hostStorageFreeBytes", 1,
                "docker", Map.of("cpu_stats", Map.of("cpu_usage", Map.of("total_usage", 1), "system_cpu_usage", 1), "memory_stats", Map.of("usage", 1),
                    "blkio_stats", Map.of(), "networks", Map.of("invented", Map.of("rx_bytes", 1, "tx_bytes", 1))), "endedNanos", 1, "dockerSampleNanos", 1)));
        }
        r.set("acquisitions", JSON.valueToTree(List.of(Map.of("authMethod", "requireHoldAdmission", "elapsedMs", 1, "outerTransactionActive", false, "outcome", "SUCCESS", "trace", "nested", "startedNanos", 2),
            Map.of("authMethod", "authenticateProduct", "elapsedMs", 1), Map.of("authMethod", "authenticateMcp", "elapsedMs", 1))));
        r.set("authCalls", JSON.valueToTree(List.of(Map.of("method", "requireHoldAdmission", "elapsedMs", 1, "outerTransactionActive", true, "trace", "nested", "startedNanos", 1))));
        r.set("providers", JSON.valueToTree(List.of(Map.of("injectedFailure", true, "startedNanos", 1, "endedNanos", 2, "trace", "synthetic"))));
        var oracle = r.putObject("oracle");
        for (String key : List.of("occupancy", "idempotency", "receipt", "retry", "doneWithoutReceipt", "bindingMismatch", "violations")) oracle.put(key, 0);
        oracle.put("successfulHoldBindingsChecked", 1); oracle.put("independentPhysicalMySql", true);
        var probes = r.putArray("probes");
        probes.add(JSON.valueToTree(Map.of("name", "current-authority", "observed", true, "httpStatus", 401, "revokedAtNanos", 2, "gateEnteredNanos", 1,
            "database", Map.of("reservations", 0, "allocations", 0, "keys", 0))));
        var before = Map.of("state", "CONFIRMED", "active", 1, "events", 0);
        probes.add(JSON.valueToTree(Map.of("name", "tenant-customer-scope", "observed", true, "httpStatus", 404, "disclosedForeignId", false, "before", before, "after", before)));
        probes.add(JSON.valueToTree(Map.of("name", "event-atomicity", "observed", true,
            "commit", Map.of("before", before, "uncommitted", before, "after", Map.of("state", "CANCELLED", "active", 0, "events", 1), "terminal", "COMMITTED",
                "receiptWhileUncommitted", Map.of("receipt", 0, "done", 0), "receiptAfterRetry", Map.of("receipt", 1, "done", 1, "attempts", 2), "handlerInvocations", 2),
            "rollback", Map.of("before", before, "uncommitted", before, "after", before, "terminal", "INJECTED_ROLLBACK"))));
        probes.add(JSON.valueToTree(Map.of("name", "response-loss-reconciliation", "observed", true, "originalOutcome", "UNKNOWN", "reconciledOutcome", "SUCCESS", "knownTarget", "invented",
            "database", Map.of("state", "HELD", "active", 1, "events", 0), "targetRows", Map.of("reservations", 1, "hold_idempotency_records", 1))));
        var queries = r.putArray("queryCosts");
        for (String operation : List.of("availability", "management", "knowledge")) for (int i = 0; i < 20; i++)
            queries.add(JSON.valueToTree(Map.of("operation", operation, "outcome", "SUCCESS", "elapsedMs", 1, "jdbcExecutions", 2, "hibernateSql", 1, "knowledgeSql", 2, "responseBytes", 10)));
        return r;
    }

    private static ObjectNode sample(String phase, long at) {
        var s = JSON.createObjectNode(); s.put("phase", phase); s.put("nanos", at);
        for (String key : List.of("active", "pending", "inFlight", "mcpWorkers", "runtimeWorkers", "observationWorker", "mcpPermits", "runtimePermits", "mcpIngress", "backlog", "unmaterialized",
                "idle", "lockWaits", "processCpuLoad", "systemCpuLoad", "heapBytes", "hostFreeRamBytes")) s.put(key, 0);
        s.put("databaseObservation", "OBSERVED"); s.set("httpThreads", JSON.valueToTree(List.of(Map.of("currentThreadsBusy", 0, "maxThreads", 64)))); return s;
    }
}
