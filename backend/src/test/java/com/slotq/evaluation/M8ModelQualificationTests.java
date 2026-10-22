package com.slotq.evaluation;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;
import static org.assertj.core.api.Assertions.*;

class M8ModelQualificationTests {
    static final M8Evaluation.Cell CELL = new M8Evaluation.Cell("m7-router/customer-normal", "1.0.0", 1, "gemini-3.5-flash-lite", "ACTUAL_PROVIDER_FIXTURE");

    static ObjectNode plan(List<M8Evaluation.Cell> cells) {
        var value = JSON.createObjectNode();
        value.put("version", M8ModelQualification.VERSION).put("kind", "EXECUTION").put("phase", "PILOT").put("maximumCalls", cells.size() * 2).put("maximumMetadataCalls", 2)
            .put("maximumTokens", cells.size() * 70000).put("maximumSeconds", 600).put("reservationTokens", 35000)
            .put("minimumSpacingMillis", 7000).put("maximumDollars", 0).put("parentRunId", "").put("parentEvidenceDigest", "")
            .put("stopPolicy", "HARD_GATE_OR_PROVIDER_FAILURE_OR_USAGE_MISSING");
        value.set("cells", JSON.valueToTree(cells)); value.set("criteria", read("criteria.json")); value.putObject("frozenIdentity");
        var reserve = value.putArray("cellReservations");
        var mapping = value.putObject("securityCases");
        for (var cell : cells) {
            reserve.addObject().put("providerCalls", 1).put("tokens", 35000);
            mapping.putArray(cell.caseId()).add("security/untrusted-instructions");
        }
        value.putObject("providerPreflight").put("freeOnlyProject", true).put("quotaAvailable", true)
            .put("syntheticDisclosureApproved", true).put("trainingHumanReviewAllowed", true).put("checkedAt", Instant.now().minusSeconds(1).toString())
            .put("pricingSource", "https://ai.google.dev/gemini-api/docs/pricing").put("termsSource", "https://ai.google.dev/gemini-api/terms")
            .put("quotaReference", "synthetic-test-attestation").put("authorizationReference", "synthetic-test-only-no-network");
        return value;
    }

    static ObjectNode manifest(JsonNode plan) {
        var manifest = M8FixtureValidatorTests.executionManifest();
        ((ObjectNode) manifest.path("features")).put("productWrite", false);
        ((ObjectNode) manifest.path("versions")).put("scorer", M8Evaluation.SCORER);
        M8ModelQualification.enableProvider(manifest, plan); return manifest;
    }

    @Test void pendingCriteriaAllowsOnlyPilotAndNeverOptInImplicitly() {
        var plan = plan(List.of(CELL)); var manifest = manifest(plan);
        M8ModelQualification.preflight(plan, manifest, List.of(CELL), Instant.now());
        plan.put("phase", "QUALIFICATION");
        assertThatThrownBy(() -> M8ModelQualification.preflight(plan, manifest, List.of(CELL), Instant.now())).hasMessageContaining("FROZEN");
        String original = System.getProperty("slotq.m8.modelOptIn");
        try {
            System.clearProperty("slotq.m8.modelOptIn");
            assertThatThrownBy(() -> new M8ActualProvider(plan)).hasMessageContaining("opt-in");
        } finally {
            if (original == null) System.clearProperty("slotq.m8.modelOptIn"); else System.setProperty("slotq.m8.modelOptIn", original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "version", "lane", "holdout", "quota", "expired-preflight", "calls", "tokens", "security", "reservation", "lineage"})
    void rejectsInvalidPlanBeforeProviderAccess(String defect) {
        var plan = plan(List.of(CELL)); var manifest = manifest(plan);
        switch (defect) {
            case "duplicate" -> plan.withArray("cells").add(JSON.valueToTree(CELL));
            case "version" -> ((ObjectNode) plan.path("cells").get(0)).put("caseVersion", "9.0.0");
            case "lane" -> ((ObjectNode) plan.path("cells").get(0)).put("lane", "DETERMINISTIC");
            case "holdout" -> {
                String id = array(read("dataset.json"), "cases").stream().filter(c -> c.path("split").asString().equals("HOLDOUT")
                    && strings(c, "lanes").contains("ACTUAL_PROVIDER_FIXTURE")).findFirst().orElseThrow().path("id").asString();
                ((ObjectNode) plan.path("cells").get(0)).put("caseId", id);
                plan.withObject("securityCases").removeAll();
                plan.withObject("securityCases").putArray(id).add("security/untrusted-instructions");
            }
            case "quota" -> plan.withObject("providerPreflight").put("quotaAvailable", false);
            case "expired-preflight" -> plan.withObject("providerPreflight").put("checkedAt", Instant.now().minusSeconds(90000).toString());
            case "calls" -> plan.put("maximumCalls", 0);
            case "tokens" -> plan.put("maximumTokens", 0);
            case "security" -> plan.withObject("securityCases").putArray(CELL.caseId()).add("security/not-an-oracle");
            case "reservation" -> ((ObjectNode) plan.path("cellReservations").get(0)).put("tokens", 1);
            case "lineage" -> plan.put("parentRunId", "old-run").put("parentEvidenceDigest", "missing");
        }
        assertThatThrownBy(() -> M8ModelQualification.preflight(plan, manifest, M8ModelQualification.cells(plan), Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void versionMismatchIsRejectedBeforeNumericQualificationCriteria() {
        var plan = plan(List.of(CELL)); var manifest = manifest(plan); plan.put("phase", "QUALIFICATION");
        plan.set("criteria", M8FixtureValidatorTests.frozenCriteria());
        assertThatThrownBy(() -> M8ModelQualification.preflight(plan, manifest, List.of(CELL), Instant.now())).hasMessageContaining("different version");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TIMEOUT_REMOTE_UNKNOWN", "RATE_LIMIT", "TEMPORARY_OVERLOAD", "MALFORMED", "POLICY_REFUSAL", "AUTH_CONFIGURATION", "ACCOUNT_QUOTA"})
    void recordsEveryFailureAndRetainsUnknownUsageReservation(String failure) {
        var journal = journal(failure, false);
        var account = M8ModelQualification.account(Map.of("provider-call-a", journal));
        assertThat(account.path("calls").asInt()).isEqualTo(1);
        assertThat(account.path("failed").asInt()).isEqualTo(1);
        assertThat(account.path("chargedTokens").asInt()).isEqualTo(35000);
        assertThat(account.path("usageMissing").asBoolean()).isTrue();
        assertThat(account.path("stop").asBoolean()).isTrue();
        assertThat(account.path("failures").path(failure).asInt()).isEqualTo(1);
    }

    static ObjectNode journal(String failure, boolean usage) {
        var journal = JSON.createObjectNode().put("reservationTokens", 35000);
        var result = journal.putObject("result").put("attempts", 1).put("failure", failure).put("resolvedVersion", "synthetic-version");
        var tokens = result.putObject("usage").put("status", usage ? "measured" : "unavailable");
        if (usage) tokens.put("total", 123);
        return journal;
    }

    @Test void pendingRepeatedReportKeepsFailuresMissingAndRoleSafetyVisible() {
        var rows = JSON.createArrayNode();
        for (int i = 1; i <= 3; i++) {
            var row = rows.addObject(); row.set("cell", JSON.valueToTree(new M8Evaluation.Cell(CELL.caseId(), CELL.caseVersion(), i, CELL.model(), CELL.lane())));
            row.put("role", "CUSTOMER").put("workload", "customer").put("split", "REGRESSION").put("automaticStatus", i == 2 ? "FAIL" : "INCOMPLETE");
            row.putArray("axes").add("tool_selection"); row.putArray("hardGateViolations");
            row.withObject("axisResults").putObject("tool_selection").put("status", i == 1 ? "PASS" : "INCOMPLETE");
            if (i < 3) {
                row.putObject("timing"); row.set("providerObservation", M8ModelQualification.account(Map.of("provider-call-a", journal(i == 2 ? "RATE_LIMIT" : "", i == 1))));
            }
            if (i == 2) row.withArray("hardGateViolations").add("TENANT_DISCLOSURE");
        }
        var result = M8ModelQualification.summarize(rows, plan(List.of(CELL)), JSON.createObjectNode(), JSON.createObjectNode(), null);
        assertThat(result.path("status").asString()).isEqualTo("FAIL");
        assertThat(result.path("cohorts").valueStream().mapToInt(c -> c.path("planned").asInt()).sum()).isEqualTo(3);
        assertThat(result.path("cohorts").valueStream().mapToInt(c -> c.path("unexecuted").asInt()).sum()).isEqualTo(1);
        assertThat(result.path("cohorts").valueStream().mapToInt(c -> c.path("failed").asInt()).sum()).isEqualTo(1);
        assertThat(result.path("requiredRoleAxes").valueStream().anyMatch(c -> c.path("role").asString().equals("OWNER")
            && c.path("status").asString().equals("INCOMPLETE"))).isTrue();
        assertThat(M8ModelQualification.summarize(rows, plan(List.of(CELL)), JSON.createObjectNode(), JSON.createObjectNode(), null)
            .path("cohorts")).isEqualTo(result.path("cohorts"));
    }

    @Test void interruptedPilotCannotTurnInjectedObservationIntoActualProviderEvidence() throws Exception {
        var cells = List.of(CELL, new M8Evaluation.Cell(CELL.caseId(), CELL.caseVersion(), 2, CELL.model(), CELL.lane()));
        var plan = plan(cells); String runId = "model-adapter-test-" + UUID.randomUUID();
        var session = new M8Evaluation(runId, cells, manifest(plan), M8ModelQualification.budget(plan),
            c -> M8Bindings.create(c, runId, M8EvaluationTests.BASE, Map.of()), null, plan);
        var result = session.execute((c, input, recorder) -> {
            recorder.put("provider-call-a", journal("RATE_LIMIT", false));
            throw new IllegalStateException("test-only interruption; no external call performed");
        });
        assertThat(result.path("rows")).hasSize(2);
        assertThat(result.path("status").asString()).isEqualTo("FAIL");
        assertThat(result.path("rows").get(0).path("checks").path("evidence_lane").path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(JSON.readTree(Files.readAllBytes(session.directory().resolve("completion.json"))).path("tokens").asInt()).isEqualTo(35000);
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest())).isEqualTo(result);
    }

    @Test void frozenIdentityCannotAcceptBuildOrReviewPolicyDrift() {
        var plan = plan(List.of(CELL)); var source = JSON.createObjectNode().put("buildDigest", "a".repeat(64));
        var snapshots = JSON.createObjectNode(); plan.set("frozenIdentity", M8ModelQualification.identity(source, snapshots));
        M8ModelQualification.verifyIdentity(plan, source, snapshots);
        source.put("buildDigest", "b".repeat(64));
        assertThatThrownBy(() -> M8ModelQualification.verifyIdentity(plan, source, snapshots)).hasMessageContaining("identity mismatch");
    }

    @Test void zeroAffordableCellsStillProduceTheEntireUnexecutedDenominator() throws Exception {
        var plan = plan(List.of(CELL)); String runId = "zero-budget-" + UUID.randomUUID();
        var criteria = (ObjectNode) plan.path("criteria"); criteria.put("version", "1.1.0");
        criteria.withArray("history").addObject().put("version", "1.1.0").put("reason", "synthetic snapshot test only").put("previous", "1.0.0");
        var manifest = manifest(plan); ((ObjectNode) manifest.path("versions")).put("criteria", "1.1.0");
        var reserve = (ObjectNode) plan.path("cellReservations").get(0); reserve.put("providerCalls", 3).put("tokens", 105000);
        var session = new M8Evaluation(runId, List.of(CELL), manifest, M8ModelQualification.budget(plan),
            c -> M8Bindings.create(c, runId, M8EvaluationTests.BASE, Map.of()), null, plan);
        var result = session.execute((c, input, recorder) -> { throw new AssertionError("must not execute"); });
        assertThat(result.path("rows")).hasSize(1);
        assertThat(result.path("rows").get(0).path("automaticStatus").asString()).isEqualTo("INCOMPLETE");
        assertThat(result.path("modelQualification").path("cohorts").get(0).path("unexecuted").asInt()).isEqualTo(1);
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest())).isEqualTo(result);
        var capturedCriteria = Files.readAllBytes(session.directory().resolve("snapshots/criteria.json"));
        assertThat(JSON.readTree(capturedCriteria).path("version").asString()).isEqualTo("1.1.0");
        assertThat(result.path("rows").get(0).path("record").path("identity").path("criteriaDigest").asString()).isEqualTo(digest(capturedCriteria));
        assertThat(read("criteria.json").path("version").asString()).isEqualTo("1.0.0");
    }

    @Test void observedTokenOverrunAndModelVersionDriftStopBeforeNextCell() {
        var journal = journal("", true); ((ObjectNode) journal.path("result").path("usage")).put("total", 35001);
        journal.put("versionMismatch", true);
        var usage = M8ModelQualification.account(Map.of("provider-call-a", journal));
        assertThat(usage.path("stop").asBoolean()).isTrue();
        assertThat(usage.path("chargedTokens").asInt()).isEqualTo(35001);
        assertThat(usage.path("failures").path("TOKEN_RESERVATION_EXCEEDED").asInt()).isEqualTo(1);
        assertThat(usage.path("failures").path("MODEL_VERSION_MISMATCH").asInt()).isEqualTo(1);
    }

    @Test void positiveFrozenPreflightIsOnlyAMetadataTestAndStillRequiresRealPilotAtExecution() {
        var plan = plan(List.of(CELL)); var manifest = manifest(plan); plan.put("phase", "QUALIFICATION");
        var criteria = M8FixtureValidatorTests.frozenCriteria(); criteria.put("version", "1.1.0");
        criteria.withArray("history").addObject().put("version", "1.1.0").put("reason", "synthetic test only").put("previous", "1.0.0");
        ((ObjectNode) manifest.path("versions")).put("criteria", "1.1.0");
        for (var threshold : criteria.path("thresholds")) {
            if (!Set.of("quality", "security").contains(threshold.path("id").asString())) continue;
            ((ObjectNode) threshold).put("version", "1.1.0");
            ((ObjectNode) threshold.path("appliesTo")).put("scorer", M8Evaluation.SCORER);
            if (!threshold.path("id").asString().equals("quality")) {
                ((ObjectNode) threshold.path("after")).put("permission_cases", 19).put("zero_mandatory_violations", 0); continue;
            }
            var quality = (ObjectNode) threshold; var after = (ObjectNode) quality.path("after");
            for (String axis : AXES) after.put(axis, 1.0);
            after.put("absolute_floor", 1.0).put("regression_margin", 0.0).put("stability", 0.0).put("minimum_samples", 1).put("repeats", 1);
            quality.put("denominator", "PLANNED_CASE_REPEAT_MODEL_LANE_PER_ROLE_AXIS").put("stabilityMethod", "PLANNED_RATE_REPEAT_RANGE_WILSON95_DESCRIPTIVE");
        }
        plan.set("criteria", criteria); plan.withObject("frozenIdentity").put("buildDigest", "a".repeat(64));
        plan.put("stabilityMethod", "PLANNED_RATE_REPEAT_RANGE_WILSON95_DESCRIPTIVE");
        plan.putObject("pilot").put("runId", "synthetic-pilot").put("digest", "a".repeat(64));
        plan.putObject("baseline").put("runId", "synthetic-baseline").put("digest", "b".repeat(64));
        plan.withObject("providerPreflight").putObject("modelVersions").put(CELL.model(), "synthetic-version");
        M8ModelQualification.preflight(plan, manifest, List.of(CELL), Instant.now());
        assertThatThrownBy(() -> M8ModelQualification.reference(plan.path("pilot"))).isInstanceOf(Exception.class);
        ((ObjectNode) criteria.path("thresholds").get(0).path("after")).put("regression_margin", "not-a-number");
        assertThatThrownBy(() -> M8ModelQualification.preflight(plan, manifest, List.of(CELL), Instant.now())).hasMessageContaining("numeric unit threshold");
    }

    @Test void injectedAttemptCountsCannotQualifyAsActualPilotOrBaseline() {
        var plan = plan(List.of(CELL));
        var source = JSON.createObjectNode(); var snapshots = JSON.createObjectNode();
        var references = new HashMap<String, JsonNode>();
        for (String name : List.of("pilot", "baseline")) {
            String runId = "injected-reference-test-" + name;
            plan.putObject(name).put("runId", runId).put("digest", "a".repeat(64));
            var reference = JSON.createObjectNode(); var results = reference.putObject("results");
            results.put("runId", runId).put("evidenceDigest", "a".repeat(64)); results.putObject("integrity");
            var quality = results.putObject("modelQualification").put("phase", "PILOT");
            quality.set("comparisonIdentity", M8ModelQualification.comparisonIdentity(source, snapshots));
            var row = results.putArray("rows").addObject(); row.set("cell", JSON.valueToTree(CELL));
            row.put("role", "CUSTOMER").put("workload", "customer").put("split", "REGRESSION");
            row.putArray("axes").add("tool_selection"); row.putArray("hardGateViolations");
            row.withObject("axisResults").putObject("tool_selection").put("status", "PASS");
            row.put("observationKind", "SCORER_TEST_VECTOR"); row.putObject("providerObservation").put("calls", 1);
            row.withObject("checks").putObject("evidence_lane").put("status", "INCOMPLETE");
            references.put(name, reference);
        }
        assertThatThrownBy(() -> M8ModelQualification.verifyReferences(plan, source, snapshots, references.get("pilot"), references.get("baseline")))
            .hasMessageContaining("actual-provider provenance missing");
        var summary = M8ModelQualification.summarize(references.get("pilot").path("results").path("rows"), plan, source, snapshots, null);
        assertThat(summary.path("cohorts").get(0).path("succeeded").asInt()).isZero();
        assertThat(summary.path("cohorts").get(0).path("status").asString()).isEqualTo("INCOMPLETE");
    }
}
