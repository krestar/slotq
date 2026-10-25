package com.slotq.mcp;

import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.slotq.mcp.M8CapacityEvidence.*;

/** Positive evidence gates for this one workload; absence never supplies a zero or a successful assertion. */
final class M8CapacityQualification {
    private M8CapacityQualification() { }

    static String verdict(List<Map<String, Object>> checks) {
        if (checks.stream().anyMatch(c -> c.get("status").equals("FAIL"))) return "FAIL";
        return checks.isEmpty() || checks.stream().anyMatch(c -> c.get("status").equals("INCOMPLETE")) ? "INCOMPLETE" : "PASS";
    }

    static List<Map<String, Object>> assess(JsonNode raw) {
        var out = new ArrayList<Map<String, Object>>();
        if (!raw.path("schemaVersion").asString().equals("slotq-m8-capacity/v2")) {
            out.add(Map.of("gate", "legacy-observations", "status", "INCOMPLETE"));
            return out;
        }
        var manifest = raw.path("manifest");
        var criteria = manifest.path("criteria").path("values");
        var host = manifest.path("host");
        gate(out, "execution-environment", !host.path("cpu").asString().isBlank() && host.path("processors").asInt() > 0
            && host.path("ramBytes").asLong() > 0 && host.path("storageFreeBytes").asLong() > 0 && !host.path("network").asString().isBlank()
            && !manifest.path("java").asString().isBlank() && manifest.path("jvmFlags").isArray()
            && !manifest.path("docker").path("imageId").asString().isBlank() && manifest.path("docker").path("cpus").asInt() > 0
            && manifest.path("docker").path("memoryBytes").asLong() > 0 && !manifest.path("mysql").path("version").asString().isBlank()
            && !manifest.path("mysql").path("isolation_level").asString().isBlank() && manifest.path("mysql").path("buffer_pool_bytes").asLong() > 0
            && !manifest.path("runId").asString().isBlank() && !manifest.path("containerId").asString().isBlank() && manifest.path("sourceDigests").size() > 0,
            manifest.path("mysql").path("isolation_level").asString().equals("REPEATABLE-READ"));
        gate(out, "execution-and-prior-freeze", manifest.path("kind").asString().equals("EXECUTION") && manifest.path("measured").asBoolean()
            && manifest.path("stage").asString().equals("verification") && criteria.path("schemaVersion").asString().equals("slotq-m8-capacity-criteria/v2")
            && criteria.path("state").asString().equals("FROZEN") && !manifest.path("criteria").path("sha256").asString().isBlank(), true);
        for (String name : List.of("generator-calibration", "warmup", "distributed", "probes", "hot-slot", "burst", "recovery")) {
            var phase = find(raw.path("phases"), "name", name);
            var requests = select(raw.path("requests"), "phase", name);
            gate(out, name + ".arrivals", phase != null && !requests.isEmpty(), phase != null
                && requests.size() == phase.path("planned").asInt() && requests.stream().map(r -> r.path("scheduledNanos").asLong()).distinct().count() == requests.size());
            if (name.equals("generator-calibration") && phase != null) {
                gate(out, "generator-independent-https-echo", phase.has("echoReceived"), phase.path("echoReceived").asInt() == phase.path("planned").asInt()
                    && requests.stream().allMatch(r -> r.path("outcome").asString().equals("SUCCESS")));
                metric(out, "generator-dispatch-p99", distribution(requests, "dispatchLagMs").get("p99"), criteria.path("metrics").path("distributedDispatchP99Ms"));
            }
        }
        admission(raw, out);
        gate(out, "declared-mixed-workload", raw.path("backgroundActions").size() > 0 && List.of("product-hold", "product-get", "availability", "mcp-management", "mcp-observations", "runtime-synthetic")
            .stream().allMatch(operation -> select(raw.path("requests"), "operation", operation).stream().anyMatch(r -> r.path("phase").asString().equals("distributed"))), true);
        var acquisitions = list(raw.path("acquisitions"));
        var authCalls = list(raw.path("authCalls"));
        // REQUIRES_NEW suspends the outer transaction before Hikari is called. Correlate with Auth entry,
        // rather than incorrectly requiring that suspended transaction to remain active inside acquisition.
        boolean authObserved = List.of("requireHoldAdmission", "authenticateProduct", "authenticateMcp").stream()
            .allMatch(method -> acquisitions.stream().anyMatch(a -> a.path("authMethod").asString().equals(method)));
        gate(out, "hikari-actual-acquisition-and-nested-auth", authObserved && !authCalls.isEmpty(), acquisitions.stream().allMatch(a -> nonnegative(a, "elapsedMs"))
            && authCalls.stream().anyMatch(call -> call.path("method").asString().equals("requireHoldAdmission") && call.path("outerTransactionActive").asBoolean()
                && acquisitions.stream().anyMatch(a -> a.path("authMethod").asString().equals("requireHoldAdmission") && a.path("outcome").asString().equals("SUCCESS")
                    && a.path("trace").asString().equals(call.path("trace").asString()) && a.path("startedNanos").asLong() >= call.path("startedNanos").asLong()
                    && a.path("startedNanos").asLong() <= call.path("startedNanos").asLong() + call.path("elapsedMs").asDouble() * 1e6)));
        var providers = list(raw.path("providers"));
        gate(out, "controlled-provider-wait-and-failure", !providers.isEmpty(), providers.stream().allMatch(p -> p.has("injectedFailure")
            && p.path("endedNanos").asLong() > p.path("startedNanos").asLong() && !p.path("trace").asString().equals("unbound"))
            && providers.stream().anyMatch(p -> p.path("injectedFailure").asBoolean()));
        var samples = list(raw.path("samples"));
        var normal = samples.stream().filter(s -> s.path("phase").asString().equals("distributed")).toList();
        for (var entry : new TreeMap<>(Map.of("pending", "normalPendingMax", "backlog", "normalBacklogMax", "unmaterialized", "normalUnmaterializedMax",
                "heapBytes", "heapMaxBytes", "processCpuLoad", "processCpuMax")).entrySet()) {
            metric(out, "distributed." + entry.getKey() + ".max", normal.stream().mapToDouble(s -> s.path(entry.getKey()).asDouble(Double.NaN)).max().orElse(Double.NaN),
                criteria.path("metrics").path(entry.getValue()));
        }
        gate(out, "bounded-workers-and-pool", !samples.isEmpty() && samples.stream().allMatch(M8CapacityQualification::sensors), samples.stream().allMatch(s ->
            s.path("active").asInt() <= manifest.path("settings").path("spring.datasource.hikari.maximum-pool-size").asInt()
                && s.path("mcpWorkers").asInt() <= 8 && s.path("runtimeWorkers").asInt() <= 4
                && s.path("mcpPermits").asInt() <= 4 && s.path("runtimePermits").asInt() <= 4 && s.path("mcpIngress").asInt() <= 8
                && s.path("observationWorker").asInt() <= 1));
        for (String phase : List.of("distributed", "hot-slot", "burst", "burst-quiet", "recovery", "query-scale")) {
            var rows = samples.stream().filter(s -> s.path("phase").asString().equals(phase)).toList();
            gate(out, phase + ".jvm-http-pool-permits", !rows.isEmpty() && rows.stream().allMatch(s -> sensors(s)
                && nonnegative(s, "processCpuLoad") && nonnegative(s, "systemCpuLoad") && nonnegative(s, "heapBytes")
                && nonnegative(s, "hostFreeRamBytes") && !list(s.path("httpThreads")).isEmpty()), true);
            var resource = select(raw.path("resourceSamples"), "phase", phase);
            gate(out, phase + ".mysql-storage-network", !resource.isEmpty() && resource.stream().allMatch(s -> s.path("status").asString().equals("OBSERVED")
                && s.path("mysqlStatus").size() >= 8 && s.path("mysqlFileIo").has("read_picoseconds")
                && nonnegative(s, "databaseBytes") && nonnegative(s, "hostStorageFreeBytes") && docker(s)), true);
        }
        var oracle = raw.path("oracle");
        boolean fields = oracle.path("independentPhysicalMySql").asBoolean();
        for (String key : List.of("occupancy", "idempotency", "receipt", "retry", "doneWithoutReceipt", "bindingMismatch", "violations")) fields &= nonnegative(oracle, key);
        gate(out, "independent-mysql-invariants", fields && oracle.path("successfulHoldBindingsChecked").asInt() > 0,
            oracle.path("violations").asLong() == 0);
        probes(raw, out);
        for (String operation : List.of("availability", "management", "knowledge")) {
            var rows = select(raw.path("queryCosts"), "operation", operation);
            String sql = operation.equals("knowledge") ? "knowledgeSql" : "jdbcExecutions";
            gate(out, operation + ".query-observation", rows.size() >= 20 && rows.stream().allMatch(r -> nonnegative(r, sql) && nonnegative(r, "responseBytes")), rows.stream().allMatch(r -> r.path("outcome").asString().equals("SUCCESS")
                && r.path(sql).asLong(-1) > 0 && r.path("responseBytes").asLong() > 0
                && (operation.equals("knowledge") || r.path(sql).asLong() >= r.path("hibernateSql").asLong())));
            metric(out, operation + ".query-p95", distribution(rows, "elapsedMs").get("p95"), criteria.path("metrics").path("queryP95Ms"));
            metric(out, operation + ".query-sql-max", rows.stream().mapToDouble(r -> r.path(sql).asDouble(Double.NaN)).max().orElse(Double.NaN),
                criteria.path("metrics").path(operation.equals("knowledge") ? "knowledgeMaxSql" : operation.equals("management") ? "managementMaxSql" : "availabilityMaxSql"));
            metric(out, operation + ".response-bytes-max", rows.stream().mapToDouble(r -> r.path("responseBytes").asDouble(Double.NaN)).max().orElse(Double.NaN),
                criteria.path("metrics").path("queryMaxBytes"));
        }
        Object recovered = recovery(raw);
        gate(out, "burst.quiet-window-coverage", !(recovered instanceof String), true);
        metric(out, "burst.worker-permit-backlog-recovery", recovered instanceof Number n ? n.doubleValue() : null,
            criteria.path("metrics").path("recoveryBoundMs"));
        var recoveryRequests = select(raw.path("requests"), "phase", "recovery");
        gate(out, "post-overload-product-and-mcp-success", !recoveryRequests.isEmpty(),
            recoveryRequests.stream().anyMatch(r -> r.path("operation").asString().equals("product-hold") && r.path("outcome").asString().equals("SUCCESS"))
            && recoveryRequests.stream().anyMatch(r -> r.path("operation").asString().equals("mcp-management") && r.path("outcome").asString().equals("SUCCESS")));
        return out;
    }

    private static void admission(JsonNode raw, List<Map<String, Object>> out) {
        var http = list(raw.path("http"));
        var tools = list(raw.path("tools"));
        var clients = list(raw.path("requests"));
        var counts = new TreeMap<String, Map<String, Long>>();
        boolean correlated = true;
        for (String operation : List.of("product-hold", "product-get", "availability", "mcp-management", "mcp-observations")) {
            var requests = clients.stream().filter(r -> r.path("operation").asString().equals(operation)).toList();
            long received = 0, accepted = 0, terminal = 0, rejected = 0, admissionUnknown = 0, completed = 0;
            for (var request : requests) {
                var wire = find(http, "trace", request.path("trace").asString());
                if (wire != null) received++;
                if (wire != null && wire.has("completedNanos")) completed++;
                boolean mcp = operation.startsWith("mcp-");
                var handler = mcp ? find(tools, "requestId", request.path("wireRequestId").asString()) : wire;
                boolean admitted = handler != null && (mcp || handler.path("authenticatedHandler").asBoolean());
                if (admitted) accepted++;
                else if (Set.of("UNKNOWN", "UNAVAILABLE").contains(request.path("outcome").asString())) admissionUnknown++;
                if (request.path("responded").asBoolean()) terminal++;
                if (request.path("outcome").asString().equals("REJECTED")) rejected++;
                if (request.path("outcome").asString().equals("SUCCESS")) correlated &= wire != null && admitted;
            }
            counts.put(operation, Map.of("offered", (long) requests.size(), "httpReceived", received, "handlerAccepted", accepted,
                "serverCompleted", completed, "clientTerminalResponse", terminal, "quotaRejected", rejected, "admissionUnknown", admissionUnknown));
        }
        out.add(Map.of("gate", "product-mcp-admission-correlation", "status", http.isEmpty() || tools.isEmpty() ? "INCOMPLETE" : correlated ? "PASS" : "FAIL", "counts", counts));
    }

    private static void probes(JsonNode raw, List<Map<String, Object>> out) {
        var authority = find(raw.path("probes"), "name", "current-authority");
        gate(out, "current-authority-after-lock", observed(authority), authority != null && Set.of(401, 403).contains(authority.path("httpStatus").asInt())
            && authority.path("revokedAtNanos").asLong() > authority.path("gateEnteredNanos").asLong() && zeroCounts(authority.path("database")));
        var scope = find(raw.path("probes"), "name", "tenant-customer-scope");
        gate(out, "tenant-customer-no-disclosure-or-mutation", observed(scope), scope != null && Set.of(403, 404).contains(scope.path("httpStatus").asInt())
            && scope.has("disclosedForeignId") && !scope.path("disclosedForeignId").asBoolean() && scope.path("before").isObject()
            && scope.path("before").equals(scope.path("after")));
        var atomic = find(raw.path("probes"), "name", "event-atomicity");
        boolean consistent = false;
        if (observed(atomic)) {
            var commit = atomic.path("commit"); var rollback = atomic.path("rollback");
            consistent = reservation(commit.path("before"), "CONFIRMED", 1, 0) && commit.path("before").equals(commit.path("uncommitted"))
                && reservation(commit.path("after"), "CANCELLED", 0, 1) && commit.path("terminal").asString().equals("COMMITTED")
                && reservation(rollback.path("before"), "CONFIRMED", 1, 0) && rollback.path("before").equals(rollback.path("uncommitted"))
                && rollback.path("before").equals(rollback.path("after")) && rollback.path("terminal").asString().equals("INJECTED_ROLLBACK")
                && commit.path("receiptWhileUncommitted").path("receipt").asInt(-1) == 0 && commit.path("receiptWhileUncommitted").path("done").asInt(-1) == 0
                && commit.path("receiptAfterRetry").path("receipt").asInt() == 1 && commit.path("receiptAfterRetry").path("done").asInt() == 1
                && commit.path("receiptAfterRetry").path("attempts").asInt() >= 2 && commit.path("receiptAfterRetry").path("attempts").asInt() <= 5
                && commit.path("handlerInvocations").asInt() == 2;
        }
        gate(out, "business-event-receipt-done-retry-atomicity", observed(atomic), consistent);
        var response = find(raw.path("probes"), "name", "response-loss-reconciliation");
        gate(out, "unknown-preserved-exact-get-reconciliation", observed(response), response != null
            && response.path("originalOutcome").asString().equals("UNKNOWN") && response.path("reconciledOutcome").asString().equals("SUCCESS")
            && !response.path("knownTarget").asString().isBlank() && reservation(response.path("database"), "HELD", 1, 0)
            && response.path("targetRows").path("reservations").asInt() == 1 && response.path("targetRows").path("hold_idempotency_records").asInt() == 1);
    }

    static Object recovery(JsonNode raw) {
        var burst = find(raw.path("phases"), "name", "burst");
        if (burst == null) return "UNAVAILABLE";
        long end = burst.path("startNanos").asLong() + burst.path("seconds").asLong() * 1_000_000_000L;
        double bound = raw.path("manifest").path("criteria").path("values").path("metrics").path("recoveryBoundMs").asDouble(15_000);
        long previous = end;
        int consecutive = 0;
        boolean covered = true;
        for (var sample : raw.path("samples")) {
            if (sample.path("nanos").asLong() < end) continue;
            if (!Set.of("burst", "burst-quiet").contains(sample.path("phase").asString())) break;
            long at = sample.path("nanos").asLong();
            if (at - previous > 1_000_000_000L || !sensors(sample) || !sample.path("databaseObservation").asString().equals("OBSERVED")) covered = false;
            previous = at;
            boolean idle = sensors(sample) && sample.path("databaseObservation").asString().equals("OBSERVED");
            for (String key : idleKeys()) idle &= sample.path(key).asLong(-1) == 0;
            for (var thread : sample.path("httpThreads")) idle &= thread.path("currentThreadsBusy").asLong(-1) == 0;
            consecutive = idle ? consecutive + 1 : 0;
            if (consecutive == 3) return covered ? (at - end) / 1e6 : "UNAVAILABLE";
        }
        // Complete observation of an exceeded deadline is a failure, rather than missing instrumentation.
        return covered && (previous - end) / 1e6 >= bound ? bound + 1 : "UNAVAILABLE";
    }

    private static List<String> idleKeys() { return List.of("active", "pending", "inFlight", "mcpWorkers", "runtimeWorkers", "observationWorker", "mcpPermits", "runtimePermits", "mcpIngress", "backlog", "unmaterialized"); }
    private static boolean sensors(JsonNode s) {
        return idleKeys().stream().allMatch(k -> nonnegative(s, k)) && nonnegative(s, "idle") && nonnegative(s, "lockWaits")
            && !list(s.path("httpThreads")).isEmpty() && list(s.path("httpThreads")).stream().allMatch(t -> nonnegative(t, "currentThreadsBusy") && nonnegative(t, "maxThreads"));
    }
    private static boolean docker(JsonNode s) {
        var d = s.path("docker");
        return nonnegative(d.path("cpu_stats").path("cpu_usage"), "total_usage") && nonnegative(d.path("cpu_stats"), "system_cpu_usage")
            && nonnegative(d.path("memory_stats"), "usage") && d.has("blkio_stats") && d.path("networks").size() > 0
            && list(d.path("networks")).stream().allMatch(n -> nonnegative(n, "rx_bytes") && nonnegative(n, "tx_bytes"))
            && s.path("endedNanos").asLong() - s.path("dockerSampleNanos").asLong() <= 3_000_000_000L;
    }
    private static boolean nonnegative(JsonNode n, String key) { return n.has(key) && Double.isFinite(n.path(key).asDouble(Double.NaN)) && n.path(key).asDouble() >= 0; }
    private static boolean observed(JsonNode n) { return n != null && n.path("observed").asBoolean(); }
    private static boolean zeroCounts(JsonNode n) { return n.size() == 3 && list(n).stream().allMatch(v -> v.isNumber() && v.asLong() == 0); }
    private static boolean reservation(JsonNode n, String state, int active, int events) { return n.path("state").asString().equals(state) && n.path("active").asInt(-1) == active && n.path("events").asInt(-1) == events; }
    static List<JsonNode> list(JsonNode n) { var rows = new ArrayList<JsonNode>(); n.forEach(rows::add); return rows; }
    static List<JsonNode> select(JsonNode n, String key, String value) { return list(n).stream().filter(r -> r.path(key).asString().equals(value)).toList(); }
    static JsonNode find(JsonNode n, String key, String value) { return find(list(n), key, value); }
    static JsonNode find(List<JsonNode> n, String key, String value) { if (value.isBlank()) return null; return n.stream().filter(r -> r.path(key).asString().equals(value)).findFirst().orElse(null); }
    private static void gate(List<Map<String, Object>> out, String name, boolean observed, boolean valid) { out.add(Map.of("gate", name, "status", !observed ? "INCOMPLETE" : valid ? "PASS" : "FAIL")); }
    private static void metric(List<Map<String, Object>> out, String name, Double value, JsonNode upper) {
        boolean available = value != null && Double.isFinite(value) && upper.isNumber() && upper.asDouble() > 0;
        out.add(Map.of("gate", name, "status", !available ? "INCOMPLETE" : value <= upper.asDouble() ? "PASS" : "FAIL",
            "observed", value != null && Double.isFinite(value) ? value : "UNAVAILABLE", "upper", upper));
    }
}
