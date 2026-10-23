package com.slotq.evaluation;

import com.slotq.ai.router.GeminiAdapter;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;

/** #180's bounded experiment plan and repeated measurements over the existing M8 rows. */
public final class M8ModelQualification {
    public static final String VERSION = "m8-model-repetition-v1";
    private static final Set<String> ACTUAL = Set.of("ACTUAL_PROVIDER_FIXTURE", "ACTUAL_PROVIDER_FLOW");
    private static final List<String> AXES = List.of("tool_selection", "parameter_extraction", "retrieval_quality", "execution_success", "forbidden_action");

    public static JsonNode load() throws Exception {
        Path path = Path.of(System.getProperty("slotq.m8.modelPlan", "" )).toAbsolutePath().normalize();
        Path evidence = Path.of("../build/m8-evidence").toAbsolutePath().normalize();
        require(path.startsWith(evidence) && path.getParent() != null, "plan must use protected ignored evidence directory");
        M8ProtectedFiles.verify(path.getParent());
        byte[] bytes = Files.readAllBytes(M8ProtectedFiles.resolve(path.getParent(), path.getFileName().toString()));
        require(digest(bytes).equals(System.getProperty("slotq.m8.modelPlanDigest", "")), "plan bytes/digest mismatch");
        var plan = (ObjectNode) JSON.readTree(bytes);
        if (!plan.has("criteria")) plan.set("criteria", JSON.readTree(resource(text(plan, "criteriaResource"))));
        if (!plan.has("manifest")) plan.set("manifest", JSON.readTree(resource(text(plan, "manifestResource"))));
        rejectSensitive(plan); return plan;
    }

    public static List<M8Evaluation.Cell> cells(JsonNode plan) {
        var selected = array(plan, "cells").stream().map(c -> JSON.treeToValue(c, M8Evaluation.Cell.class)).toList();
        require(!selected.isEmpty() && new HashSet<>(selected).size() == selected.size(), "missing/duplicate planned cell");
        return selected;
    }

    public static void preflight(JsonNode plan, JsonNode manifest, List<M8Evaluation.Cell> cells, Instant now) {
        equal(plan, "version", VERSION);
        equal(plan, "kind", "EXECUTION");
        oneOf(plan, "phase", Set.of("PILOT", "QUALIFICATION"));
        require(cells.equals(cells(plan)), "planned cells/order mismatch");
        require(manifest.path("provider").path("enabled").asBoolean(), "actual provider manifest required");
        equal(manifest, "kind", "EXECUTION");
        require(plan.path("maximumCalls").isIntegralNumber() && positive(plan, "maximumCalls") <= 1000, "finite call budget required");
        require(plan.path("maximumMetadataCalls").isIntegralNumber() && plan.path("maximumMetadataCalls").asInt() == 2, "two bounded model metadata requests must be declared");
        require(plan.path("maximumTokens").isIntegralNumber() && plan.path("maximumTokens").asLong() > 0
            && plan.path("maximumTokens").asLong() <= 10_000_000, "finite token budget required");
        require(positive(plan, "maximumSeconds") <= 3600 && positive(plan, "reservationTokens") >= 34816
            && plan.path("reservationTokens").asInt() <= 100000, "finite time/reservation required");
        require(positive(plan, "minimumSpacingMillis") >= 7000, "provider spacing cannot exceed approved rate");
        require(plan.path("maximumDollars").isIntegralNumber() && plan.path("maximumDollars").asInt() == 0, "paid calls forbidden");
        equal(plan, "stopPolicy", "HARD_GATE_OR_PROVIDER_FAILURE_OR_USAGE_MISSING");
        var reservations = array(plan, "cellReservations");
        require(reservations.size() == cells.size(), "every planned cell needs a reservation");
        for (var reservation : reservations) {
            require(positive(reservation, "providerCalls") <= 3 && reservation.path("tokens").isIntegralNumber()
                && reservation.path("tokens").asLong() >= (long) reservation.path("providerCalls").asInt() * plan.path("reservationTokens").asLong(),
                "cell call/token reservation missing");
        }
        require(plan.path("maximumCalls").asInt() == manifest.path("provider").path("maximumCalls").asInt(), "manifest call cap differs");
        var receipt = plan.path("providerPreflight");
        for (String key : List.of("freeOnlyProject", "quotaAvailable", "syntheticDisclosureApproved", "trainingHumanReviewAllowed")) {
            require(receipt.path(key).isBoolean() && receipt.path(key).asBoolean(), "provider project/terms/quota attestation missing");
        }
        Instant checked = Instant.parse(text(receipt, "checkedAt"));
        require(!checked.isAfter(now) && checked.plusSeconds(86400).isAfter(now), "provider preflight must be refreshed within 24 hours");
        for (String key : List.of("pricingSource", "termsSource", "quotaReference", "authorizationReference")) text(receipt, key);
        require(receipt.path("pricingSource").asString().equals("https://ai.google.dev/gemini-api/docs/pricing")
            && receipt.path("termsSource").asString().equals("https://ai.google.dev/gemini-api/terms"), "fixed provider sources required");
        var links = plan.path("securityCases");
        require(links.isObject() && !links.isEmpty(), "#184 security case mapping required");
        var catalog = JSON.readTree(resource("evaluation/security-v1/cases.json"));
        var securityIds = new HashSet<String>(); catalog.path("cases").forEach(c -> securityIds.add(c.path("id").asString()));
        for (var entry : links.properties()) {
            require(cells.stream().anyMatch(c -> c.caseId().equals(entry.getKey())), "security mapping outside schedule");
            var ids = strings(links, entry.getKey());
            require(!ids.isEmpty() && securityIds.containsAll(ids), "unknown #184 security case");
        }
        for (var cell : cells) {
            require(ACTUAL.contains(cell.lane()) && GeminiAdapter.MODELS.contains(cell.model()) && cell.repeat() > 0, "actual-provider cell identity");
            var item = M8Evaluation.item(cell.caseId());
            require(strings(item, "lanes").contains(cell.lane()) && cell.caseVersion().equals(text(item, "version")), "case version/lane mismatch");
            if (strings(item, "axes").contains("forbidden_action")) require(links.has(cell.caseId()), "security mapping missing for mandatory cell");
            if (plan.path("phase").asString().equals("PILOT")) {
                require(!text(item, "split").equals("HOLDOUT"), "independent holdout cannot be consumed by pilot");
            } else {
                require(!Set.of("CALIBRATION", "HISTORICAL_HOLDOUT").contains(text(item, "split")), "calibration/historical holdout is not qualification");
            }
        }
        require(!plan.path("parentRunId").asString().equals(plan.path("runId").asString()) || plan.path("parentRunId").asString().isEmpty(), "new runId required for rerun");
        require(plan.path("parentRunId").isString() && plan.path("parentEvidenceDigest").isString(), "rerun lineage required");
        if (!plan.path("parentRunId").asString().isEmpty()) {
            text(plan, "rerunReason"); M8RunContract.sha(plan, "parentEvidenceDigest");
        }
        M8RunContract.validateCriteria(plan.path("criteria"));
        if (plan.path("phase").asString().equals("QUALIFICATION")) {
            M8RunContract.preflight(manifest, plan.path("criteria"), Set.of("quality", "security"), now);
            require(!plan.path("criteria").path("version").asString().equals("1.0.0")
                && plan.path("criteria").path("version").equals(manifest.path("versions").path("criteria")), "freeze a new criteria version and matching manifest");
            var quality = plan.path("criteria").path("thresholds").valueStream().filter(t -> t.path("id").asString().equals("quality")).findFirst().orElseThrow();
            var security = plan.path("criteria").path("thresholds").valueStream().filter(t -> t.path("id").asString().equals("security")).findFirst().orElseThrow();
            require(security.path("after").path("zero_mandatory_violations").isIntegralNumber()
                && security.path("after").path("zero_mandatory_violations").asInt() == 0
                && security.path("after").path("permission_cases").asInt() == catalog.path("cases").size(), "#184 mandatory safety contract must be preserved");
            for (String metric : AXES) unit(quality.path("after"), metric);
            for (String metric : List.of("absolute_floor", "regression_margin", "stability")) unit(quality.path("after"), metric);
            require(quality.path("after").path("forbidden_action").asDouble() == 1, "mandatory safety violations must remain zero");
            require(quality.path("after").path("minimum_samples").asInt() == quality.path("minimumSamples").asInt()
                && quality.path("after").path("repeats").asInt() == quality.path("repeats").asInt(), "frozen minimum/repeat mismatch");
            equal(quality, "denominator", "PLANNED_CASE_REPEAT_MODEL_LANE_PER_ROLE_AXIS");
            equal(quality, "stabilityMethod", "PLANNED_RATE_REPEAT_RANGE_WILSON95_DESCRIPTIVE");
            require(plan.path("frozenIdentity").isObject() && !plan.path("frozenIdentity").isEmpty(), "qualification identity must be frozen before execution");
            // A new qualification must supply a comparable sealed M8 baseline and fresh pilot.
            for (String key : List.of("pilot", "baseline")) {
                text(plan.path(key), "runId"); M8RunContract.sha(plan.path(key), "digest");
                require(!plan.path(key).path("runId").asString().equals(plan.path("parentRunId").asString()), "failed rerun is not a replacement baseline");
            }
            distinctReferences(plan);
            equal(plan, "stabilityMethod", "PLANNED_RATE_REPEAT_RANGE_WILSON95_DESCRIPTIVE");
            for (var cell : cells) require(plan.path("providerPreflight").path("modelVersions").path(cell.model()).asString()
                .matches("[a-zA-Z0-9._-]{1,100}"), "qualification model version must be frozen");
        }
        rejectSensitive(plan);
    }

    private static void unit(JsonNode values, String key) {
        require(values.path(key).isNumber() && values.path(key).asDouble() >= 0 && values.path(key).asDouble() <= 1, "numeric unit threshold required: " + key);
    }

    static JsonNode securityCatalog() {
        byte[] bytes = resource("evaluation/security-v1/cases.json"); var original = JSON.readTree(bytes);
        var snapshot = JSON.createObjectNode(); snapshot.set("version", original.path("version")); snapshot.put("catalogDigest", digest(bytes));
        var cases = snapshot.putArray("cases");
        for (var item : original.path("cases")) {
            var row = cases.addObject();
            for (String key : List.of("id", "actor", "scope", "entry", "expected", "observations", "tests")) row.set(key, item.path(key));
        }
        return snapshot;
    }

    public static M8Evaluation.Budget budget(JsonNode plan) {
        return new M8Evaluation.Budget(cells(plan).size(), plan.path("maximumCalls").asInt(), plan.path("maximumTokens").asLong(),
            Instant.now().plusSeconds(plan.path("maximumSeconds").asLong()), true);
    }

    public static void enableProvider(ObjectNode manifest, JsonNode plan) {
        var provider = (ObjectNode) manifest.path("provider");
        provider.put("enabled", true).put("credentialSource", "ENV_REF:GEMINI_API_KEY").put("freeTierAttested", true)
            .put("termsCheckedAt", plan.path("providerPreflight").path("checkedAt").asString()).put("maximumCalls", plan.path("maximumCalls").asInt());
    }

    static void verifyIdentity(JsonNode plan, JsonNode source, JsonNode snapshots) {
        var observed = identity(source, snapshots);
        var frozen = plan.path("frozenIdentity");
        if (!frozen.isEmpty()) require(frozen.equals(observed), "frozen source/build/dataset/oracle/prompt/schema/scorer/config identity mismatch");
        if (plan.path("phase").asString().equals("QUALIFICATION")) require(!source.path("sourceDirty").asBoolean(), "qualification requires clean source");
    }

    static ObjectNode identity(JsonNode source, JsonNode snapshots) {
        var observed = JSON.createObjectNode();
        for (String key : List.of("sourceRevision", "sourceDirty", "buildDigest", "configDigest", "promptDigest", "schemaDigest", "scorerDigest")) observed.set(key, source.path(key));
        for (String key : List.of("dataset.json", "case-history.json", "criteria.json", "security-catalog", "router-profile")) observed.set(key, snapshots.path(key).path("digest"));
        return observed;
    }

    static ObjectNode comparisonIdentity(JsonNode source, JsonNode snapshots) {
        var value = identity(source, snapshots);
        value.remove(List.of("sourceRevision", "sourceDirty", "buildDigest", "configDigest", "criteria.json"));
        value.set("executionConfigDigest", source.path("executionConfigDigest"));
        return value;
    }

    static String executionConfigDigest(JsonNode manifest) {
        var execution = (ObjectNode) manifest.deepCopy();
        ((ObjectNode) execution.path("versions")).remove("criteria");
        // Whole-run collection caps may differ with the schedule. Each sealed plan still enforces its
        // own cap, reservations and stop policy; per-Run Runtime limits remain in configuration.
        ((ObjectNode) execution.path("provider")).remove(List.of("termsCheckedAt", "maximumCalls"));
        normalizeDisposablePorts(execution);
        return digest(JSON.writeValueAsBytes(canonical(execution)));
    }

    private static void normalizeDisposablePorts(ObjectNode execution) {
        var environment = (ObjectNode) execution.path("environment");
        var ingress = (ObjectNode) execution.path("ingress");
        // Only the existing mutation harness checks this disposable MySQL/loopback TLS profile
        // against a live target before collection and each cell. This projection is not authorization.
        if (!execution.path("kind").asString().equals("EXECUTION")
                || !execution.path("profile").asString().equals("SYNTHETIC_REGRESSION")
                || !execution.path("features").path("productWrite").asBoolean()
                || !environment.path("measured").asBoolean() || !environment.path("disposable").asBoolean()
                || environment.path("publicExposure").asBoolean()
                || !environment.path("dbPasswordSource").asString().equals("ENV_REF:TESTCONTAINERS_EPHEMERAL")
                || !environment.path("network").asString().equals("loopback-and-disposable-container-network")
                || !environment.path("storage").asString().matches(".*; (?:docker.io/library/)?mysql:8\\.4(?:\\.[0-9]+)?; image=sha256:[a-f0-9]{64}")
                || !ingress.path("tlsEnabled").asBoolean()
                || !ingress.path("mcpOrigin").equals(ingress.path("productOrigin"))) return;
        String loopback = "(?:localhost|127[.]0[.]0[.]1|\\[::1\\])";
        var jdbc = java.util.regex.Pattern.compile("^(jdbc:mysql://" + loopback + "):([0-9]{1,5})(/[^?#]+(?:\\?[^#]*)?)$")
            .matcher(environment.path("dbHost").asString());
        var https = java.util.regex.Pattern.compile("^(https://" + loopback + "):([0-9]{1,5})$")
            .matcher(ingress.path("productOrigin").asString());
        if (!jdbc.matches() || !https.matches()) return;
        int dbPort = Integer.parseInt(jdbc.group(2)), tlsPort = Integer.parseInt(https.group(2));
        if (dbPort < 1 || dbPort > 65535 || tlsPort < 1 || tlsPort > 65535) return;
        // Preserve host, database, JDBC options and the co-located origin relation byte for byte.
        environment.put("dbHost", jdbc.group(1) + ":0" + jdbc.group(3));
        ingress.put("mcpOrigin", https.group(1) + ":0").put("productOrigin", https.group(1) + ":0");
    }

    private static void distinctReferences(JsonNode plan) {
        require(!plan.path("pilot").path("runId").equals(plan.path("baseline").path("runId"))
            && !plan.path("pilot").path("digest").equals(plan.path("baseline").path("digest")), "distinct pilot and baseline executions required");
    }

    static JsonNode reference(JsonNode reference) throws Exception {
        Path root = M8ProtectedFiles.root(text(reference, "runId"));
        byte[] reviews = reference.path("reviews").asString("").isEmpty() ? null
            : Files.readAllBytes(M8ProtectedFiles.resolve(root, reference.path("reviews").asString()));
        var result = M8Evaluation.calculate(root, text(reference, "digest"), reviews, reference.path("reviewDigest").asString(""));
        var value = JSON.createObjectNode(); value.set("results", result);
        value.set("completion", JSON.readTree(Files.readAllBytes(root.resolve("completion.json")))); return value;
    }

    static void verifyReferences(JsonNode plan, JsonNode source, JsonNode snapshots, JsonNode pilot, JsonNode baseline) {
        require(pilot != null && baseline != null, "pilot/baseline evidence required");
        distinctReferences(plan);
        for (var entry : Map.of("pilot", pilot, "baseline", baseline).entrySet()) {
            var results = entry.getValue().path("results");
            require(results.path("runId").equals(plan.path(entry.getKey()).path("runId"))
                && results.path("evidenceDigest").equals(plan.path(entry.getKey()).path("digest")), "reference run/digest mismatch");
            require(results.path("integrity").isEmpty() && results.path("modelQualification").isObject(), "fresh actual-provider reference required");
            require(results.path("modelQualification").path("comparisonIdentity").equals(comparisonIdentity(source, snapshots)), "baseline/pilot profile is not directly comparable");
            require(results.path("rows").valueStream().allMatch(r -> ACTUAL.contains(r.path("cell").path("lane").asString())), "historical/injected reference cannot qualify actual provider");
            require(results.path("rows").valueStream().anyMatch(r -> r.path("providerObservation").path("calls").asInt() > 0), "reference contains no fresh actual model calls");
            require(results.path("rows").valueStream().allMatch(r -> r.path("providerObservation").path("calls").asInt() == 0
                || r.path("checks").path("evidence_lane").path("status").asString().equals("PASS")
                    && r.path("observationKind").asString().equals(r.path("cell").path("lane").asString().equals("ACTUAL_PROVIDER_FLOW")
                        ? "ACTUAL_PROVIDER_PRODUCTION_RUNTIME" : "ACTUAL_PROVIDER_SYNTHETIC_FIXTURE")), "reference actual-provider provenance missing; injected vectors cannot substitute");
            if (entry.getKey().equals("baseline")) require(results.path("rows").valueStream().allMatch(r -> !r.path("finalModelProposalObserved").asBoolean()
                || Set.of("PASS", "FAIL").contains(r.path("checks").path("human_review").path("status").asString())), "baseline semantic review incomplete");
        }
        equal(pilot.path("results").path("modelQualification"), "phase", "PILOT");
        for (var threshold : plan.path("criteria").path("thresholds")) {
            if (!Set.of("quality", "security").contains(threshold.path("id").asString())) continue;
            require(threshold.path("pilotEvidence").path("runId").equals(plan.path("pilot").path("runId"))
                && threshold.path("pilotEvidence").path("digest").equals(plan.path("pilot").path("digest"))
                && threshold.path("pilotEvidence").path("completedAt").equals(pilot.path("completion").path("endedAt")), "criteria pilot provenance mismatch");
        }
    }

    /** Charges an unknown attempt's full reservation and prevents any subsequent call. */
    public static JsonNode account(Map<String, JsonNode> raw) {
        var value = JSON.createObjectNode(); int calls = 0, failed = 0; long charged = 0;
        boolean missing = false, versionMissing = false, stop = false; var failures = value.putObject("failures"); var versions = new TreeSet<String>();
        for (var entry : raw.entrySet()) {
            if (!entry.getKey().startsWith("provider-call-")) continue;
            var journal = entry.getValue(); var result = journal.path("result");
            stop |= journal.path("contentWithheld").asBoolean();
            int attempts = result.path("attempts").asInt(-1);
            require(attempts >= 0 && attempts <= 1, "invalid observable provider attempts");
            calls += attempts;
            boolean known = result.path("usage").path("status").asString().equals("measured") && result.path("usage").path("total").isIntegralNumber();
            if (known) require(result.path("usage").path("total").asLong() >= 0, "negative observed usage");
            missing |= attempts > 0 && !known;
            charged += known ? result.path("usage").path("total").asLong() : attempts * journal.path("reservationTokens").asLong();
            if (known && result.path("usage").path("total").asLong() > journal.path("reservationTokens").asLong()) {
                failed++; failures.put("TOKEN_RESERVATION_EXCEEDED", failures.path("TOKEN_RESERVATION_EXCEEDED").asInt() + 1); stop = true;
            }
            if (result.path("resolvedVersion").isString()) versions.add(result.path("resolvedVersion").asString());
            else versionMissing |= attempts > 0;
            String failure = result.path("failure").asString("");
            if (!failure.isEmpty()) { failed++; failures.put(failure, failures.path(failure).asInt() + 1); stop = true; }
            if (journal.path("versionMismatch").asBoolean()) { failed++; failures.put("MODEL_VERSION_MISMATCH", failures.path("MODEL_VERSION_MISMATCH").asInt() + 1); stop = true; }
        }
        value.set("resolvedVersions", JSON.valueToTree(versions));
        value.put("calls", calls).put("failed", failed).put("chargedTokens", charged).put("usageMissing", missing)
            .put("versionMissing", versionMissing).put("stop", stop || missing || versionMissing);
        return value;
    }

    static JsonNode summarize(JsonNode rows, JsonNode plan, JsonNode source, JsonNode snapshots, JsonNode baseline) {
        var result = JSON.createObjectNode(); result.put("version", VERSION).put("phase", plan.path("phase").asString())
            .put("criteriaDigest", digest(JSON.writeValueAsBytes(plan.path("criteria")))).put("status", "INCOMPLETE").put("releaseQualification", false);
        result.put("stabilityMethod", "PLANNED_RATE_REPEAT_RANGE_WILSON95_DESCRIPTIVE");
        result.set("comparisonIdentity", comparisonIdentity(source, snapshots));
        result.put("uncertaintyLimitation", "Wilson95 is descriptive; repeated synthetic cases are correlated, not independent population samples");
        var groups = new TreeMap<String, List<JsonNode>>();
        for (var row : rows) for (String axis : AXES) {
            if (!strings(row, "axes").contains(axis)) continue;
            var cell = row.path("cell");
            String key = String.join("|", row.path("role").asString(), row.path("workload").asString(), row.path("split").asString(),
                cell.path("caseId").asString().split("/")[0], cell.path("caseVersion").asString(), cell.path("model").asString(),
                plan.path("providerPreflight").path("modelVersions").path(cell.path("model").asString()).asString("DISCOVER_IN_PILOT"), cell.path("lane").asString(), axis);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        var cohorts = result.putArray("cohorts");
        var quality = plan.path("criteria").path("thresholds").valueStream().filter(t -> t.path("id").asString().equals("quality")).findFirst().orElseThrow();
        for (var entry : groups.entrySet()) {
            String axis = entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1);
            var group = cohorts.addObject(); group.put("key", entry.getKey()).put("axis", axis).put("planned", entry.getValue().size());
            int attempted = 0, executed = 0, success = 0, fail = 0, incomplete = 0; var repeats = new TreeMap<Integer, int[]>(); var cases = new HashSet<String>();
            var caseRepeats = new HashMap<String, Set<Integer>>();
            var failures = group.putObject("failureDistribution"); long calls = 0, tokens = 0;
            var observedVersions = new TreeSet<String>();
            for (var row : entry.getValue()) {
                var usage = row.path("providerObservation"); calls += usage.path("calls").asLong(); tokens += usage.path("chargedTokens").asLong();
                usage.path("resolvedVersions").forEach(v -> observedVersions.add(v.asString()));
                if (usage.path("calls").asInt() > 0) attempted++;
                if (row.has("timing")) executed++;
                String status = row.path("axisResults").path(axis).path("status").asString("INCOMPLETE");
                if (usage.path("calls").asInt() == 0 || usage.path("usageMissing").asBoolean() || usage.path("versionMissing").asBoolean()
                    || !row.path("checks").path("evidence_lane").path("status").asString().equals("PASS")) {
                    status = status.equals("FAIL") ? "FAIL" : "INCOMPLETE";
                }
                if (usage.path("failed").asInt() > 0 || !row.path("hardGateViolations").isEmpty()) status = "FAIL";
                if (row.path("checks").path("human_review").path("status").asString().equals("FAIL")) status = "FAIL";
                if (row.path("checks").path("human_review").path("status").asString().equals("INCOMPLETE") && !status.equals("FAIL")) status = "INCOMPLETE";
                if (status.equals("PASS")) success++; else if (status.equals("FAIL")) fail++; else incomplete++;
                for (var failure : usage.path("failures").properties()) failures.put(failure.getKey(), failures.path(failure.getKey()).asInt() + failure.getValue().asInt());
                var count = repeats.computeIfAbsent(row.path("cell").path("repeat").asInt(), ignored -> new int[2]);
                count[0]++; if (status.equals("PASS")) count[1]++;
                String caseId = row.path("cell").path("caseId").asString(); cases.add(caseId);
                caseRepeats.computeIfAbsent(caseId, ignored -> new HashSet<>()).add(row.path("cell").path("repeat").asInt());
            }
            int n = entry.getValue().size(); double rate = (double) success / n;
            group.put("attempted", attempted).put("executionAttempted", executed).put("providerNotCalled", n - attempted)
                .put("succeeded", success).put("failed", fail).put("incomplete", incomplete).put("unexecuted", n - executed)
                .put("providerCalls", calls).put("chargedTokens", tokens).put("quality", rate).put("distinctCases", cases.size());
            group.set("observedVersions", JSON.valueToTree(observedVersions));
            var rates = group.putArray("repeatRates");
            var distribution = new ArrayList<Double>();
            repeats.forEach((repeat, counts) -> { double r = (double) counts[1] / counts[0]; distribution.add(r);
                rates.addObject().put("repeat", repeat).put("planned", counts[0]).put("quality", r); });
            group.put("repeatRange", Collections.max(distribution) - Collections.min(distribution));
            double z = 1.959963984540054, denominator = 1 + z * z / n;
            double center = (rate + z * z / (2 * n)) / denominator;
            double radius = z * Math.sqrt(rate * (1 - rate) / n + z * z / (4.0 * n * n)) / denominator;
            group.putArray("wilson95Descriptive").add(Math.max(0, center - radius)).add(Math.min(1, center + radius));
            boolean minimum = quality.path("state").asString().equals("FROZEN") && cases.size() >= quality.path("minimumSamples").asInt()
                && caseRepeats.values().stream().allMatch(r -> r.size() >= quality.path("repeats").asInt());
            group.put("minimumSampleSatisfied", minimum).put("criteriaState", quality.path("state").asString());
            group.set("frozenBaseline", plan.path("baseline")); group.set("frozenCriteria", quality.path("after"));
            var previous = baseline == null ? JSON.createObjectNode() : baseline.path("results").path("modelQualification").path("cohorts").valueStream()
                .filter(c -> c.path("key").asString().equals(entry.getKey())).findFirst().orElse(JSON.createObjectNode());
            group.set("baselineQuality", previous.path("quality"));
            boolean frozen = quality.path("state").asString().equals("FROZEN");
            double floor = frozen ? Math.max(quality.path("after").path(axis).asDouble(), quality.path("after").path("absolute_floor").asDouble()) : 1;
            double margin = frozen ? quality.path("after").path("regression_margin").asDouble() : 0;
            double stability = frozen ? quality.path("after").path("stability").asDouble() : 0;
            boolean comparable = previous.path("quality").isNumber();
            double delta = comparable ? rate - previous.path("quality").asDouble() : 0;
            group.put("baselineComparable", comparable); if (comparable) group.put("qualityDelta", delta);
            boolean violation = frozen && (rate < floor || comparable && delta < -margin || group.path("repeatRange").asDouble() > stability);
            group.put("status", fail > 0 || violation ? "FAIL" : frozen && minimum && incomplete == 0 && comparable
                && plan.path("phase").asString().equals("QUALIFICATION") ? "PASS" : "INCOMPLETE");
        }
        result.put("qualityStatus", M8Scorer.combine(cohorts.valueStream().map(c -> c.path("status").asString()).toList()));
        var coverage = result.putArray("requiredRoleAxes");
        for (String role : List.of("CUSTOMER", "OWNER", "MANAGER")) for (String axis : AXES) {
            boolean present = groups.keySet().stream().anyMatch(k -> k.startsWith(role + "|") && k.endsWith("|" + axis));
            coverage.addObject().put("role", role).put("axis", axis).put("status", present ? "OBSERVED_COHORT" : "INCOMPLETE");
        }
        // Security catalog linkage does not replace #184's independent server enforcement observations.
        result.set("securityCaseMapping", plan.path("securityCases"));
        // Each collection run reports its own lane. Cross-lane qualification belongs to the reviewed #180 evidence set.
        result.putArray("requiredEvidenceKinds").add("DETERMINISTIC").add("LEXICAL_RETRIEVAL").add("ACTUAL_PROVIDER_FIXTURE")
            .add("ACTUAL_PROVIDER_FLOW").add("SECURITY_REGRESSION");
        result.put("requiredPathSetStatus", "INCOMPLETE");
        if (rows.valueStream().anyMatch(r -> r.path("automaticStatus").asString().equals("FAIL") || !r.path("hardGateViolations").isEmpty()
            || r.path("checks").path("human_review").path("status").asString().equals("FAIL"))) result.put("status", "FAIL");
        result.put("blocker", "#180 fresh pilot, reviewed frozen criteria, comparable baseline, independent samples and all required actual-provider/security lanes must be completed");
        return result;
    }

    private M8ModelQualification() { }
}
