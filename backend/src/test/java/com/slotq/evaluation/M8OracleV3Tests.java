package com.slotq.evaluation;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;
import static org.assertj.core.api.Assertions.*;

/** Contract/scorer vectors only; none of these synthetic references is actual model or human evidence. */
class M8OracleV3Tests {
    static JsonNode v3(String name) { return JSON.readTree(resource("evaluation/m8-v3/" + name)); }

    @Test void authorizedThreeCaseScopePreservesAllOtherParentCasesAndPendingCriteria() {
        validateDataset(v3("dataset.json"), v3("case-history.json"), M8FixtureValidator::resource);
        assertThat(v3("dataset.json").path("cases")).hasSize(61);
        assertThat(resource("evaluation/m8-v3/criteria.json")).isEqualTo(resource("evaluation/m8-v2/criteria.json"));
        assertThat(resource("evaluation/m8-v3/review-rubric.json")).isEqualTo(resource("evaluation/m8-v2/review-rubric.json"));
        assertThat(resource("evaluation/m8-v3/oracle-change.json")).isEqualTo(resource("evaluation/m8-v2/oracle-change.json"));
        var parent = JSON.readTree(resource("evaluation/m8-v2/dataset.json"));
        for (var old : parent.path("cases")) {
            var current = v3("dataset.json").path("cases").valueStream().filter(c -> c.path("id").equals(old.path("id"))).findFirst().orElseThrow();
            if (!FIXTURE_ONLY.contains(old.path("id").asString())) assertThat(current).isEqualTo(old);
            else {
                assertThat(current.path("oracle")).isEqualTo(old.path("oracle"));
                assertThat(strings(current, "axes")).doesNotContain("execution_success");
                assertThat(current.path("version").asString()).isEqualTo("2.0.0");
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"third-case", "extra-case", "case-version", "axes", "oracle", "history", "denominator", "manager-actor", "manager-plan", "parent", "future-application"})
    void rejectsChangesOutsideUserAuthorizedScope(String defect) {
        var d = (ObjectNode) v3("dataset.json"); var h = (ObjectNode) v3("case-history.json"); var change = (ObjectNode) v3("dataset-change.json");
        switch (defect) {
            case "third-case" -> ((ObjectNode) d.path("cases").get(0)).put("required", false);
            case "extra-case" -> d.withArray("cases").add(d.path("cases").get(0));
            case "case-version" -> ((ObjectNode) d.path("cases").get(d.path("cases").size() - 1)).put("version", "2.0.0");
            case "axes" -> ((ObjectNode) d.path("cases").get(0)).putArray("axes").add("forbidden_action");
            case "oracle" -> ((ObjectNode) d.path("cases").get(0).path("oracle").path("forbidden")).put("disclosure", true);
            case "history" -> ((ObjectNode) h.path("entries").get(0)).put("everCalibrated", true);
            case "denominator" -> ((ObjectNode) d.path("denominators").path("REGRESSION")).put("execution_success", 0);
            case "manager-actor" -> ((ObjectNode) d.path("cases").get(d.path("cases").size() - 1).path("scope")).put("actor", "customer-a");
            case "manager-plan" -> ((ObjectNode) d.path("cases").get(d.path("cases").size() - 1)).putObject("serverPlan");
            case "parent" -> ((ObjectNode) change.path("parentDigests")).put("dataset.json", "0".repeat(64));
            case "future-application" -> change.put("effectiveAt", Instant.now().plusSeconds(300).toString());
        }
        assertThatThrownBy(() -> validateV3(d, h, change)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void v3FixtureAndReferenceGuardsRunInASeparateProfileWithoutCredentials() throws Exception {
        var control = M8ProtectedFiles.root("scorer-v3-guards-" + UUID.randomUUID()); M8ProtectedFiles.create(control);
        child(control, "guards", "3.0.0", "guards");
    }

    @Test void independentlySealedV3RunsRecalculateAndRejectOtherProfiles() throws Exception {
        var control = M8ProtectedFiles.root("scorer-v3-recalculation-" + UUID.randomUUID()); M8ProtectedFiles.create(control);
        var identities = new ArrayList<JsonNode>();
        for (int i = 0; i < 2; i++) {
            String runId = "scorer-v3-proof-" + UUID.randomUUID();
            child(control, "collect-" + i, "3.0.0", "collect", runId);
            var root = M8ProtectedFiles.root(runId); var p = JSON.readTree(Files.readAllBytes(root.resolve("plan.json")));
            assertThat(p.path("snapshots").has("dataset-change.json")).isTrue();
            var source = M8Evaluation.readArtifact(root, p.path("snapshots").path("source-build"));
            identities.add(M8ModelQualification.comparisonIdentity(source, p.path("snapshots")));
            child(control, "recalculate-" + i, "3.0.0", "recalculate", runId);
            child(control, "v1-reject-" + i, "1.0.0", "wrong-profile", runId);
            child(control, "v2-reject-" + i, "2.0.0", "wrong-profile", runId);
        }
        assertThat(identities.get(0)).isEqualTo(identities.get(1));
        var changed = (ObjectNode) identities.get(1).deepCopy(); changed.put("dataset-change.json", "0".repeat(64));
        assertThat(changed).isNotEqualTo(identities.get(0));
    }

    private static void child(Path control, String name, String version, String... args) throws Exception {
        var file = control.resolve(name + ".args");
        Files.writeString(file, "-Xmx256m\n-Dslotq.m8.datasetVersion=" + version + "\n--class-path\n\""
            + System.getProperty("slotq.test.runtime-classpath").replace('\\', '/') + "\"\n" + M8OracleV3Tests.class.getName() + "\n" + String.join("\n", args), StandardCharsets.UTF_8);
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + file)
            .redirectErrorStream(true).redirectOutput(control.resolve(name + ".log").toFile()).start();
        try { assertThat(process.waitFor(60, TimeUnit.SECONDS)).as(name).isTrue(); assertThat(process.exitValue()).as(name).isZero(); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    static final String MODEL = "gemini-3.5-flash-lite";
    static final M8Evaluation.Cell R = new M8Evaluation.Cell("m8/customer-invalid-party", "2.0.0", 1, MODEL, "ACTUAL_PROVIDER_FIXTURE");
    static final M8Evaluation.Cell H = new M8Evaluation.Cell("m8/holdout-customer-proposal", "1.0.0", 1, MODEL, "ACTUAL_PROVIDER_FIXTURE");

    static ObjectNode planFor(String purpose, List<M8Evaluation.Cell> cells) {
        var plan = M8ModelQualificationTests.plan(cells); plan.put("purpose", purpose);
        plan.withObject("providerPreflight").putObject("modelVersions").put(MODEL, "synthetic-version");
        if (Set.of("HOLDOUT_BASELINE", "FINAL_QUALIFICATION").contains(purpose)) {
            var frozen = M8ModelQualificationTests.qualificationPlan();
            for (String key : List.of("criteria", "frozenIdentity", "pilot", "baseline", "stabilityMethod")) plan.set(key, frozen.path(key));
            for (var threshold : plan.path("criteria").path("thresholds")) if (Set.of("quality", "security").contains(threshold.path("id").asString())) {
                ((ObjectNode) threshold.path("appliesTo")).put("dataset", "3.0.0").put("oracle", "3.0.0");
            }
            plan.put("phase", "QUALIFICATION");
        }
        return plan;
    }

    static ObjectNode row(M8Evaluation.Cell cell, String split, String startedAt) {
        var item = M8Evaluation.item(cell.caseId()); var row = JSON.createObjectNode(); row.set("cell", JSON.valueToTree(cell)); row.set("axes", item.path("axes"));
        row.put("role", "CUSTOMER").put("workload", "customer").put("split", split).put("automaticStatus", "PASS").put("status", "INCOMPLETE")
            .put("observationKind", "ACTUAL_PROVIDER_SYNTHETIC_FIXTURE").put("finalModelProposalObserved", true);
        row.putObject("timing").put("startedAt", startedAt); row.putArray("hardGateViolations");
        var usage = row.putObject("providerObservation"); usage.put("calls", 1).putArray("resolvedVersions").add("synthetic-version");
        var checks = row.putObject("checks"); checks.putObject("evidence_lane").put("status", "PASS"); checks.putObject("human_review").put("status", "PASS");
        var axes = row.putObject("axisResults"); for (String axis : strings(item, "axes")) axes.putObject(axis).put("status", "PASS");
        return row;
    }

    static ObjectNode reference(JsonNode ref, JsonNode rows, JsonNode summary, String endedAt) {
        var reference = JSON.createObjectNode(); reference.putObject("completion").put("endedAt", endedAt).put("stopPoint", "COMPLETED").put("executed", rows.size());
        var results = reference.putObject("results").put("runId", ref.path("runId").asString()).put("evidenceDigest", ref.path("digest").asString())
            .put("status", "INCOMPLETE").put("plannedCells", rows.size());
        results.putObject("integrity"); results.set("rows", rows); results.set("modelQualification", summary); return reference;
    }

    static void guards() {
        for (String id : FIXTURE_ONLY) {
            var item = M8Evaluation.item(id); var cell = new M8Evaluation.Cell(id, "2.0.0", 1, MODEL, "ACTUAL_PROVIDER_FIXTURE");
            var binding = M8Bindings.create(cell, "synthetic-v3-fixture-only", M8EvaluationTests.BASE, Map.of());
            var full = M8EvaluationTests.vector(item, binding); var raw = new TreeMap<String, JsonNode>();
            for (String key : List.of("bindings", "request", "answer")) raw.put(key, full.get(key));
            var result = JSON.createObjectNode().put("observationKind", "SCORER_TEST_VECTOR"); result.set("proposal", full.get("result").path("proposal")); raw.put("result", result);
            raw.put("disclosure", M8ActualProvider.fixtureDisclosure(raw.get("request"), result.path("proposal"), item));
            assertThat(M8Scorer.score(item, raw).checks().get("fixture_execution_scope").status()).isEqualTo("PASS");
            assertThat(M8Scorer.score(item, raw).status()).isEqualTo("PASS");
            assertThat(M8Scorer.score(item, raw).checks()).doesNotContainKeys("execution_db", "execution_effect");
            raw.put("independent-db", full.get("independent-db"));
            assertThat(M8Scorer.score(item, raw).status()).isEqualTo("FAIL");
        }
        var b = planFor("HOLDOUT_BASELINE", List.of(R, H)); var manifest = M8ModelQualificationTests.manifest(b);
        assertThat(manifest.path("versions").path("criteria").asString()).isEqualTo("1.1.0");
        var source = M8QualificationVerdictTests.configSource(manifest); source.put("sourceDirty", false); var snapshots = JSON.createObjectNode();
        var rr = JSON.createArrayNode().add(row(R, "REGRESSION", "2026-10-19T00:30:00Z"));
        var pRows = JSON.createArrayNode().add(row(R, "REGRESSION", "2026-10-18T00:00:00Z"));
        var pilot = reference(b.path("pilot"), pRows, M8ModelQualification.summarize(pRows, planFor("PILOT", List.of(R)), source, snapshots, null), "2026-10-19T00:00:00Z");
        var seed = reference(b.path("baseline"), rr, M8ModelQualification.summarize(rr, planFor("REGRESSION_SEED_BASELINE", List.of(R)), source, snapshots, null), "2026-10-19T01:00:00Z");
        M8ModelQualification.preflight(b, manifest, List.of(R, H), Instant.now()); M8ModelQualification.verifyReferences(b, source, snapshots, pilot, seed);
        var bRows = JSON.createArrayNode().add(row(R, "REGRESSION", "2026-10-21T00:00:00Z")).add(row(H, "HOLDOUT", "2026-10-21T00:00:01Z"));
        var bSummary = M8ModelQualification.summarize(bRows, b, source, snapshots, seed);
        assertThat(bSummary.path("cohorts")).allSatisfy(c -> assertThat(c.path("status").asString()).isEqualTo("INCOMPLETE"));
        var unreviewed = bRows.deepCopy(); ((ObjectNode) unreviewed.get(1).path("checks").path("human_review")).put("status", "INCOMPLETE");
        assertThat(M8ModelQualification.summarize(unreviewed, b, source, snapshots, seed).path("cohorts")).allSatisfy(c -> assertThat(c.path("status").asString()).isEqualTo("INCOMPLETE"));
        var q = planFor("FINAL_QUALIFICATION", List.of(R, H)); q.putObject("baseline").put("runId", "synthetic-predeclared-B").put("digest", "c".repeat(64));
        var baseline = reference(q.path("baseline"), bRows, bSummary, "2026-10-22T00:00:00Z");
        M8ModelQualification.verifyReferences(q, source, snapshots, pilot, baseline);
        var summary = M8ModelQualification.summarize(bRows, q, source, snapshots, baseline);
        assertThat(summary.path("cohorts")).allSatisfy(c -> assertThat(c.path("baselineComparable").asBoolean()).isTrue());
        for (String defect : List.of("purpose", "cohort", "version", "usage", "review", "pilot-review", "identity", "same-run", "late", "incomplete")) {
            var bad = baseline.deepCopy(); var altered = q.deepCopy(); var badPilot = pilot.deepCopy();
            switch (defect) {
                case "purpose" -> bad.withObject("results").withObject("modelQualification").put("purpose", "FINAL_QUALIFICATION");
                case "cohort" -> ((ObjectNode) bad.path("results").path("modelQualification").path("cohorts").get(0)).put("key", "OTHER_ROLE_SPLIT_MODEL_LANE");
                case "version" -> ((ObjectNode) bad.path("results").path("rows").get(0).path("providerObservation")).putArray("resolvedVersions").add("drift");
                case "usage" -> ((ObjectNode) bad.path("results").path("rows").get(0).path("providerObservation")).put("usageMissing", true);
                case "review" -> ((ObjectNode) bad.path("results").path("rows").get(0).path("checks").path("human_review")).put("status", "INCOMPLETE");
                case "pilot-review" -> ((ObjectNode) badPilot.path("results").path("rows").get(0).path("checks").path("human_review")).put("status", "INCOMPLETE");
                case "identity" -> bad.withObject("results").withObject("modelQualification").putObject("comparisonIdentity");
                case "same-run" -> altered.set("baseline", altered.path("pilot"));
                case "late" -> bad.withObject("completion").put("endedAt", Instant.now().plusSeconds(60).toString());
                case "incomplete" -> bad.withObject("completion").put("executed", 1);
            }
            assertThatThrownBy(() -> M8ModelQualification.verifyReferences(altered, source, snapshots, badPilot, bad)).as(defect).isInstanceOf(IllegalArgumentException.class);
        }
        var p = planFor("PILOT", List.of(H));
        assertThatThrownBy(() -> M8ModelQualification.preflight(p, M8ModelQualificationTests.manifest(p), List.of(H), Instant.now())).hasMessageContaining("holdout");
        var pending = planFor("PILOT", List.of(R)); pending.put("phase", "QUALIFICATION").put("purpose", "HOLDOUT_BASELINE");
        assertThatThrownBy(() -> M8ModelQualification.preflight(pending, M8ModelQualificationTests.manifest(pending), List.of(R), Instant.now())).hasMessageContaining("FROZEN");
        pending.put("phase", "BASELINE");
        assertThatThrownBy(() -> M8ModelQualification.preflight(pending, M8ModelQualificationTests.manifest(pending), List.of(R), Instant.now())).hasMessageContaining("phase");
        var flowCell = new M8Evaluation.Cell("m8/customer-held", "1.0.0", 1, MODEL, "ACTUAL_PROVIDER_FLOW");
        var holdoutFixture = new M8Evaluation.Cell("m8/holdout-unknown-commit", "1.0.0", 1, MODEL, "ACTUAL_PROVIDER_FLOW");
        var runtimePlan = planFor("PILOT", List.of(flowCell));
        assertThatThrownBy(() -> M8ModelQualification.runtimeFixtureCells(runtimePlan)).hasMessageContaining("fixtureCells");
        runtimePlan.set("fixtureCells", JSON.valueToTree(List.of(flowCell, holdoutFixture)));
        M8ModelQualification.preflight(runtimePlan, M8ModelQualificationTests.manifest(runtimePlan), List.of(flowCell), Instant.now());
        assertThat(M8ModelQualification.runtimeFixtureCells(runtimePlan)).containsExactly(flowCell, holdoutFixture);
        runtimePlan.set("fixtureCells", JSON.valueToTree(List.of(holdoutFixture)));
        assertThatThrownBy(() -> M8ModelQualification.runtimeFixtureCells(runtimePlan)).hasMessageContaining("contain every");
        var seedCell = new M8Evaluation.Cell("m7-router/customer-normal", "1.0.0", 1, MODEL, "ACTUAL_PROVIDER_FIXTURE");
        var seedRows = JSON.createArrayNode().add(row(seedCell, "REGRESSION", "2026-10-19T00:30:00Z"));
        var sparseSeed = reference(b.path("baseline"), seedRows, M8ModelQualification.summarize(seedRows,
            planFor("REGRESSION_SEED_BASELINE", List.of(seedCell)), source, snapshots, null), "2026-10-19T01:00:00Z");
        M8ModelQualification.verifyReferences(b, source, snapshots, pilot, sparseSeed);
        var missingResponse = baseline.deepCopy(); ((ObjectNode) missingResponse.path("results").path("rows").get(0).path("providerObservation")).put("calls", 0);
        assertThatThrownBy(() -> M8ModelQualification.verifyReferences(q, source, snapshots, pilot, missingResponse)).hasMessageContaining("model response missing");
        var manager = M8Evaluation.item(MANAGER_RETRIEVAL);
        var managerCell = new M8Evaluation.Cell(MANAGER_RETRIEVAL, "1.0.0", 1, "deterministic-runtime", "DETERMINISTIC");
        var managerBinding = M8Bindings.create(managerCell, "synthetic-v3-knowledge-guards", M8EvaluationTests.BASE, Map.of());
        assertThat(M8Scorer.score(manager, M8EvaluationTests.vector(manager, managerBinding)).status()).isEqualTo("PASS");
        for (String defect : List.of("scope", "publication", "version", "untrusted-proposal")) {
            var observations = M8EvaluationTests.vector(manager, managerBinding);
            switch (defect) {
                case "scope" -> ((ObjectNode) observations.get("publication-metadata").path("beforeCandidates").get(0)).put("tenant", UUID.randomUUID().toString());
                case "publication" -> ((ObjectNode) observations.get("publication-metadata").path("atFinal").get(0)).put("publication", "WITHDRAWN");
                case "version" -> ((ObjectNode) observations.get("retrieval-candidates").path("results").get(0)).put("versionId", UUID.randomUUID().toString());
                case "untrusted-proposal" -> ((ObjectNode) observations.get("result").path("proposal")).put("tool", "reservation.hold");
            }
            assertThat(M8Scorer.score(manager, observations).status()).as(defect).isEqualTo("FAIL");
        }
        var before = M8ExecutionComparisonTests.disposableManifest(); var after = before.deepCopy();
        after.withObject("environment").put("dbHost", "jdbc:mysql://localhost:49153/evaluation?useSSL=false");
        after.withObject("ingress").put("mcpOrigin", "https://localhost:49155").put("productOrigin", "https://localhost:49155");
        assertThat(M8ModelQualification.runtimeFreezeConfigDigest(before)).isEqualTo(M8ModelQualification.runtimeFreezeConfigDigest(after));
        var inventorySnapshot = JSON.createObjectNode(); inventorySnapshot.putObject("fixture-inventory").put("digest", "a".repeat(64));
        var firstSource = M8QualificationVerdictTests.configSource(before); firstSource.put("sourceDirty", false).put("runtimeFreezeConfigDigest", M8ModelQualification.runtimeFreezeConfigDigest(before));
        var nextSource = M8QualificationVerdictTests.configSource(after); nextSource.put("sourceDirty", false).put("runtimeFreezeConfigDigest", M8ModelQualification.runtimeFreezeConfigDigest(after));
        var frozenRuntime = b.deepCopy(); frozenRuntime.set("frozenIdentity", M8ModelQualification.identity(firstSource, inventorySnapshot));
        assertThat(firstSource.path("configDigest")).isNotEqualTo(nextSource.path("configDigest"));
        M8ModelQualification.verifyIdentity(frozenRuntime, nextSource, inventorySnapshot);
        for (String field : List.of("data", "criteria", "terms", "calls", "unverified-port")) {
            var changed = after.deepCopy();
            switch (field) {
                case "data" -> changed.withObject("data").put("slots", 99);
                case "criteria" -> changed.withObject("versions").put("criteria", "1.1.0");
                case "terms" -> changed.withObject("provider").put("termsCheckedAt", "2026-10-23T00:00:00Z");
                case "calls" -> changed.withObject("provider").put("maximumCalls", 99);
                case "unverified-port" -> changed.withObject("environment").put("storage", "missing verified image identity");
            }
            assertThat(M8ModelQualification.runtimeFreezeConfigDigest(changed)).as(field).isNotEqualTo(M8ModelQualification.runtimeFreezeConfigDigest(before));
            var changedSource = nextSource.deepCopy(); changedSource.put("runtimeFreezeConfigDigest", M8ModelQualification.runtimeFreezeConfigDigest(changed));
            assertThatThrownBy(() -> M8ModelQualification.verifyIdentity(frozenRuntime, changedSource, inventorySnapshot)).as(field).hasMessageContaining("identity mismatch");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("guards")) { guards(); System.out.println("v3 synthetic fixture/reference guards passed; no provider or human approval"); return; }
        String runId = args[1]; var root = M8ProtectedFiles.root(runId);
        if (args[0].equals("collect")) {
            var cells = List.of(new M8Evaluation.Cell("m8/attribution-two-sources", "2.0.0", 1, MODEL, "ACTUAL_PROVIDER_FIXTURE"), R);
            var p = planFor("PILOT", cells); var manifest = M8ModelQualificationTests.manifest(p);
            var session = new M8Evaluation(runId, cells, manifest, M8ModelQualification.budget(p), c -> M8Bindings.create(c, runId, M8EvaluationTests.BASE, Map.of()), null, p);
            session.execute((cell, input, recorder) -> {
                var full = M8EvaluationTests.vector(M8Evaluation.item(cell.caseId()), recorder.binding());
                var result = JSON.createObjectNode().put("observationKind", "SCORER_TEST_VECTOR"); result.set("proposal", full.get("result").path("proposal"));
                recorder.put("request", full.get("request")); recorder.put("result", result); recorder.put("answer", full.get("answer"));
                recorder.put("disclosure", M8ActualProvider.fixtureDisclosure(full.get("request"), result.path("proposal"), M8Evaluation.item(cell.caseId())));
            });
        } else if (args[0].equals("wrong-profile")) {
            assertThatThrownBy(() -> M8Evaluation.calculate(root, Files.readString(root.resolve("evidence.sha256")))).isInstanceOf(IllegalArgumentException.class);
        } else {
            var expected = Files.readString(root.resolve("evidence.sha256"));
            System.setProperty("slotq.m8.runId", runId); System.setProperty("slotq.m8.digest", expected);
            M8Evaluation.main(new String[0]);
            try (var directories = Files.list(root.resolve("recalculations"))) {
                var recalculated = directories.findFirst().orElseThrow();
                for (String name : List.of("results.json", "summary.ko.md")) assertThat(Files.readAllBytes(recalculated.resolve(name))).isEqualTo(Files.readAllBytes(root.resolve(name)));
            }
        }
    }
}
