package com.slotq.evaluation;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;
import static org.assertj.core.api.Assertions.*;

class M8FixtureValidatorTests {
    @Test void normalFixturesAndBothManifestTemplatesValidateWithoutNetworkOrCredential() {
        M8FixtureValidator.main(new String[0]);
        assertThat(array(read("dataset.json"), "cases")).hasSize(60);
        assertThat(read("criteria.json").path("thresholds")).allMatch(t -> t.path("state").asString().equals("PENDING"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate-id", "missing-version", "wrong-version", "missing-oracle", "bad-origin", "missing-required-case",
        "unknown-actor", "actor-tenant", "actor-venue", "actor-customer", "unknown-target", "cross-tenant-mislabeled",
        "cross-venue-mislabeled", "cross-customer-mislabeled", "scope-denial-effect", "source-missing", "allowed-source-weakened",
        "claim-missing", "claim-source-swapped", "claim-source-valid-swap", "model-plan-confusion", "wrong-denominator", "holdout-reclassified",
        "legacy-required-disabled", "legacy-oracle", "m6-source-version", "flow-required-source", "flow-allowed-origin",
        "unknown-effect-rewritten", "invalid-plan-tool", "invalid-plan-window", "sensitive-field", "sensitive-value", "real-data"})
    void rejectsBrokenDataset(String defect) {
        ObjectNode dataset = (ObjectNode) read("dataset.json");
        ObjectNode customer = item(dataset, "m7-router/customer-normal");
        ObjectNode attribution = object(customer, "oracle/attribution");
        switch (defect) {
            case "duplicate-id" -> ((ArrayNode) dataset.path("cases")).add(customer.deepCopy());
            case "missing-version" -> customer.remove("version");
            case "wrong-version" -> customer.put("version", "vNext");
            case "missing-oracle" -> object(customer, "oracle").remove("execution");
            case "bad-origin" -> object(customer, "origin").put("caseId", "nonexistent");
            case "missing-required-case" -> ((ArrayNode) dataset.path("cases")).remove(0);
            case "unknown-actor" -> object(customer, "scope").put("actor", "customer-missing");
            case "actor-tenant" -> object(customer, "scope").put("tenant", "tenant-b");
            case "actor-venue" -> object(customer, "scope").put("venue", "venue-b");
            case "actor-customer" -> object(customer, "scope").put("customer", "customer-a2");
            case "unknown-target" -> object(customer, "scope").put("target", "unknown");
            case "cross-tenant-mislabeled" -> object(item(dataset, "m8/cross-tenant"), "scope").put("boundary", "WITHIN_SCOPE");
            case "cross-venue-mislabeled" -> object(item(dataset, "m8/cross-venue"), "scope").put("boundary", "WITHIN_SCOPE");
            case "cross-customer-mislabeled" -> object(item(dataset, "m8/cross-customer"), "scope").put("boundary", "WITHIN_SCOPE");
            case "scope-denial-effect" -> object(item(dataset, "m8/cross-tenant"), "oracle/execution").put("db", "HOLD_ATOMIC");
            case "source-missing" -> attribution.putArray("requiredSources");
            case "allowed-source-weakened" -> attribution.withArray("allowedSources").add("other-source");
            case "claim-missing" -> attribution.putArray("requiredClaims");
            case "claim-source-swapped" -> object(item(dataset, "m8/attribution-two-sources"), "oracle/attribution/claimSources")
                .putArray("knowledge:guide").add("wrong-source");
            case "claim-source-valid-swap" -> object(item(dataset, "m8/attribution-two-sources"), "oracle/attribution/claimSources")
                .putArray("knowledge:guide").add("hours-v2");
            case "model-plan-confusion" -> {
                ObjectNode fixed = item(dataset, "m8/fixed-plan-date");
                fixed.withArray("axes").add("parameter_extraction");
                fixed.put("parameterOrigin", "MODEL_PROPOSAL");
            }
            case "wrong-denominator" -> object(dataset, "denominators/REGRESSION").put("tool_selection", 1);
            case "holdout-reclassified" -> item(dataset, "m8/holdout-customer-clarify").put("split", "CALIBRATION");
            case "legacy-required-disabled" -> customer.put("required", false);
            case "legacy-oracle" -> object(customer, "oracle/proposal").put("target", "slot-demo-B");
            case "m6-source-version" -> object(item(dataset, "m6/tenant-a-hours"), "oracle/retrieval/sources/0").put("versionId", "40000000-0000-0000-0000-000000000009");
            case "flow-required-source" -> object(item(dataset, "m7-flow/management-owner"), "oracle/attribution").putArray("requiredSources");
            case "flow-allowed-origin" -> object(item(dataset, "m7-flow/customer-hold-proposal"), "oracle/attribution").put("allowedSetOrigin", "STATIC");
            case "unknown-effect-rewritten" -> object(item(dataset, "m8/unknown-committed"), "oracle/execution").put("effect", "NOT_APPLICABLE");
            case "invalid-plan-tool" -> object(item(dataset, "m8/fixed-plan-date"), "serverPlan").putObject("reservation.cancel");
            case "invalid-plan-window" -> object(item(dataset, "m8/fixed-plan-window"), "serverPlan/management.event-observations.list").put("to", "2026-01-01T00:00:00Z");
            case "sensitive-field" -> customer.put("idempotencyKey", "synthetic-key-must-not-be-stored");
            case "sensitive-value" -> customer.put("input", "Bearer syntheticCredentialSentinel");
            case "real-data" -> dataset.put("dataClass", "REAL_CUSTOMER");
            default -> throw new AssertionError(defect);
        }
        assertThatThrownBy(() -> validate(dataset)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsChangedOriginalBytesEvenIfCaseCountAndIdAreUnchanged() {
        assertThatThrownBy(() -> validateDataset(read("dataset.json"), read("case-history.json"), path -> {
            byte[] bytes = resource(path);
            return path.equals("model-router/comparison-v3.json")
                ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace("slot-demo-A", "slot-demo-B")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8) : bytes;
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("legacy source digest changed");
    }

    @Test void rejectsTuningHistoryReclassifiedAsIndependentHoldout() {
        var history = read("case-history.json");
        ObjectNode holdout = (ObjectNode) array(history, "entries").stream()
            .filter(e -> e.path("split").asString().equals("HOLDOUT")).findFirst().orElseThrow();
        holdout.put("everCalibrated", true);
        assertThatThrownBy(() -> validateDataset(read("dataset.json"), history, M8FixtureValidator::resource))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tuned case");
    }

    @Test void preservesOptionalKnowledgeClaimsAndRuntimeBoundAllowedSets() {
        var dataset = read("dataset.json");
        var optional = item(dataset, "m7-router/management-insufficient").path("oracle").path("attribution");
        assertThat(strings(optional, "requiredSources")).isEmpty();
        assertThat(strings(optional, "allowedSources")).containsExactly("hours-v2");
        assertThat(strings(optional, "requiredClaims")).containsExactly("product:list-unavailable");
        assertThat(optional.path("claimSources").path("knowledge:closing-21").get(0).asString()).isEqualTo("hours-v2");
        var runtimeBound = item(dataset, "m7-flow/management-owner").path("oracle").path("attribution");
        assertThat(runtimeBound.path("allowedSetOrigin").asString()).isEqualTo("REQUEST_CONTEXT");
        assertThat(strings(runtimeBound, "requiredSources")).containsExactlyInAnyOrder("product:reservations", "knowledge:guide");
    }

    @Test void newHoldoutCoversEveryAxisAndWorkloadWithoutTuningHistory() {
        var holdout = array(read("dataset.json"), "cases").stream()
            .filter(c -> c.path("split").asString().equals("HOLDOUT")).toList();
        assertThat(holdout).hasSize(6);
        assertThat(holdout.stream().flatMap(c -> strings(c, "axes").stream()).collect(java.util.stream.Collectors.toSet()))
            .isEqualTo(AXES);
        assertThat(holdout.stream().map(c -> c.path("workload").asString()).collect(java.util.stream.Collectors.toSet()))
            .containsExactlyInAnyOrder("customer", "management", "ops");
        assertThat(array(read("case-history.json"), "entries").stream()
            .filter(c -> c.path("split").asString().equals("HOLDOUT")))
            .allMatch(c -> !c.path("everCalibrated").asBoolean());
    }

    @Test void ownerMembershipInAnotherVenueCannotExpandTheExactDelegatedVenue() {
        ObjectNode dataset = (ObjectNode) read("dataset.json");
        var scope = object(item(dataset, "m8/fixed-plan-date"), "scope");
        scope.put("actor", "owner-a");
        scope.put("target", "reservation-other-venue");
        assertThatThrownBy(() -> validate(dataset)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scope boundary misclassified");
    }

    @ParameterizedTest
    @ValueSource(strings = {"multi-instance", "passwordless", "public", "bootstrap", "mysql", "kafka", "actual-unmeasured", "data",
        "endpoint", "model", "paid", "retry", "secret", "evidence-location", "evidence-tracked", "missing-timeout", "unknown-fields", "missing-version", "tls", "cors-wildcard", "product-origin"})
    void rejectsUnsafeManifest(String defect) {
        ObjectNode manifest = (ObjectNode) read("manifest-regression.json");
        ObjectNode environment = object(manifest, "environment");
        switch (defect) {
            case "multi-instance" -> environment.put("instances", 2);
            case "passwordless" -> environment.put("dbPasswordSource", "");
            case "public" -> environment.put("publicExposure", true);
            case "bootstrap" -> environment.put("devBootstrap", true);
            case "mysql" -> environment.put("mysql", "9.0");
            case "kafka" -> object(manifest, "features").put("kafka", true);
            case "actual-unmeasured" -> manifest.put("kind", "EXECUTION");
            case "data" -> environment.put("dataClass", "CONFIDENTIAL");
            case "endpoint" -> object(manifest, "provider").put("endpoint", "https://unapproved.invalid/");
            case "model" -> object(manifest, "provider").putArray("models").add("latest");
            case "paid" -> object(manifest, "provider").put("paidCalls", true);
            case "retry" -> object(manifest, "provider").put("automaticRetry", true);
            case "secret" -> object(manifest, "credentials").put("source", "raw-secret");
            case "evidence-location" -> object(manifest, "evidence").put("root", "docs/results");
            case "evidence-tracked" -> object(manifest, "evidence").put("gitTracked", true);
            case "missing-timeout" -> object(manifest, "configuration").remove("socketTimeoutMs");
            case "unknown-fields" -> environment.put("password", "sentinel");
            case "missing-version" -> object(manifest, "versions").remove("oracle");
            case "tls" -> object(manifest, "ingress").put("tlsEnabled", false);
            case "cors-wildcard" -> object(manifest, "ingress").withArray("corsOrigins").add("*");
            case "product-origin" -> object(manifest, "ingress").put("productOrigin", "https://other.invalid");
            default -> throw new AssertionError(defect);
        }
        assertThatThrownBy(() -> M8EnvironmentContract.validate(manifest)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateManifestIdsAreRejected() {
        var regression = read("manifest-regression.json");
        ObjectNode release = (ObjectNode) read("manifest-pre-release.json");
        release.put("id", regression.path("id").asString());
        assertThatThrownBy(() -> M8EnvironmentContract.validateSet(List.of(regression, release)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate manifest ID");
    }

    @Test void qualificationCannotBeginWithPendingThresholds() {
        assertThatThrownBy(() -> M8RunContract.preflight(executionManifest(), read("criteria.json"), Set.of("quality"), Instant.parse("2026-10-20T01:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FROZEN");
    }

    @Test void frozenThresholdRequiresPilotReviewAndApplicableVersionBeforeRun() {
        var criteria = frozenCriteria();
        M8RunContract.preflight(executionManifest(), criteria, Set.of("quality"), Instant.parse("2026-10-20T01:00:00Z"));
        ObjectNode quality = (ObjectNode) criteria.path("thresholds").get(0);
        quality.put("approvedAt", "2026-10-20T02:00:00Z");
        quality.put("effectiveAt", "2026-10-20T02:00:00Z");
        assertThatThrownBy(() -> M8RunContract.preflight(executionManifest(), criteria, Set.of("quality"), Instant.parse("2026-10-20T01:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("post-result threshold");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-evidence", "missing-review", "hard-gate", "bad-status", "bad-denominator", "pending-threshold",
        "historical-pass", "not-executed-pass", "na-required", "review-digest", "review-other-answer", "historical-artifact", "required-list-omitted", "wrong-case-version", "wrong-axes", "missing-identity", "secret"})
    void rejectsFalsePassOrBrokenEvidenceMetadata(String defect) {
        var item = item(read("dataset.json"), "m8/attribution-two-sources");
        ObjectNode record = record(item);
        JsonNode criteria = frozenCriteria();
        switch (defect) {
            case "missing-evidence" -> object(record, "evidence").putArray("provided");
            case "missing-review" -> object(record, "review").put("state", "PENDING");
            case "hard-gate" -> record.withArray("hardGateViolations").add("PARTIAL_COMMIT");
            case "bad-status" -> record.put("status", "SKIPPED");
            case "bad-denominator" -> record.put("denominatorUnit", "SUCCESSFUL_CALLS");
            case "pending-threshold" -> criteria = read("criteria.json");
            case "historical-pass" -> record.put("lane", "HISTORICAL");
            case "not-executed-pass" -> record.put("lane", "NOT_EXECUTED");
            case "na-required" -> { record.put("status", "NOT_APPLICABLE"); record.put("reason", "PUBLIC_LOGIN"); record.withArray("exclusions").add("PUBLIC_LOGIN"); }
            case "review-digest" -> object(record, "review").put("answerDigest", "not-a-digest");
            case "review-other-answer" -> object(record, "review").put("answerDigest", "b".repeat(64));
            case "historical-artifact" -> object(record, "evidence/provided/0").put("kind", "HISTORICAL");
            case "required-list-omitted" -> object(record, "evidence").putArray("required");
            case "wrong-case-version" -> record.put("caseVersion", "0.0.0");
            case "wrong-axes" -> record.putArray("axes").add("parameter_extraction");
            case "missing-identity" -> object(record, "identity").remove("datasetDigest");
            case "secret" -> object(record, "identity").put("credential", "sentinel");
            default -> throw new AssertionError(defect);
        }
        JsonNode selectedCriteria = criteria;
        assertThatThrownBy(() -> M8RunContract.validateRecord(record, item, executionManifest(), selectedCriteria)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void completeMetadataIsAcceptedButIncompleteAndHardGateStatesStayDistinct() {
        var item = item(read("dataset.json"), "m8/attribution-two-sources");
        var record = record(item);
        M8RunContract.validateRecord(record, item, executionManifest(), frozenCriteria());
        record.put("status", "INCOMPLETE");
        record.put("reason", "필수 응답과 사람 검토 미확보");
        object(record, "evidence").putArray("provided");
        object(record, "review").put("state", "PENDING");
        M8RunContract.validateRecord(record, item, executionManifest(), read("criteria.json"));
        record.put("status", "FAIL");
        record.withArray("hardGateViolations").add("TENANT_DISCLOSURE");
        M8RunContract.validateRecord(record, item, executionManifest(), read("criteria.json"));
    }

    @Test void parserRejectsDuplicateFieldsAndTrailingJson() {
        assertThatThrownBy(() -> JSON.readTree("{\"version\":1,\"version\":2}")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> JSON.readTree("{} {} ")).isInstanceOf(Exception.class);
    }

    @Test void baselineTracksTheCurrentSupportedProviderAndServerPlanContract() {
        var manifest = read("manifest-regression.json");
        assertThat(manifest.path("provider").path("endpoint").asString()).isEqualTo(com.slotq.ai.router.GeminiAdapter.ENDPOINT);
        assertThat(manifest.path("provider").path("api").asString()).isEqualTo(com.slotq.ai.router.GeminiAdapter.API_MODE);
        assertThat(strings(manifest.path("provider"), "models")).isEqualTo(com.slotq.ai.router.GeminiAdapter.MODELS);
        assertThat(manifest.path("versions").path("plan").asString()).isEqualTo(com.slotq.ai.flow.RepresentativeFlows.REVISION);
        assertThat(manifest.path("versions").path("schema").asString()).isEqualTo(com.slotq.ai.router.ProviderProtocol.REVISION);
        assertThat(manifest.path("versions").path("context").asString()).isEqualTo(com.slotq.ai.flow.RepresentativeContext.REVISION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-metric", "missing-pilot", "future-effective", "wrong-version", "wrong-owner", "wrong-applies-to", "missing-history"})
    void rejectsUnfrozenOrMismatchedCriterionContract(String defect) {
        ObjectNode criteria = frozenCriteria();
        var quality = (ObjectNode) criteria.path("thresholds").get(0);
        switch (defect) {
            case "missing-metric" -> quality.putArray("metrics").add("tool_selection");
            case "missing-pilot" -> quality.putNull("pilotEvidence");
            case "future-effective" -> quality.put("effectiveAt", "2026-10-21T00:00:00Z");
            case "wrong-version" -> quality.put("version", "1");
            case "wrong-owner" -> quality.put("owner", 179);
            case "wrong-applies-to" -> object(quality, "appliesTo").put("oracle", "0.0.0");
            case "missing-history" -> criteria.putArray("history");
            default -> throw new AssertionError(defect);
        }
        assertThatThrownBy(() -> M8RunContract.preflight(executionManifest(), criteria, Set.of("quality"), Instant.parse("2026-10-20T01:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static void validate(JsonNode dataset) {
        validateDataset(dataset, read("case-history.json"), M8FixtureValidator::resource);
    }

    static ObjectNode item(JsonNode dataset, String id) {
        return (ObjectNode) array(dataset, "cases").stream().filter(c -> c.path("id").asString().equals(id)).findFirst().orElseThrow();
    }

    static ObjectNode object(JsonNode node, String path) {
        for (String part : path.split("/")) node = node.isArray() ? node.path(Integer.parseInt(part)) : node.path(part);
        return (ObjectNode) node;
    }

    static ObjectNode executionManifest() {
        ObjectNode manifest = (ObjectNode) read("manifest-regression.json");
        manifest.put("kind", "EXECUTION");
        object(manifest, "environment").put("measured", true).put("cpu", "synthetic-test-cpu").put("storage", "synthetic-test-disk");
        object(manifest, "environment").put("os", "synthetic-test-os").put("jvmFlags", "synthetic-test-jvm").put("networkMeasurements", "synthetic-loopback");
        object(manifest, "versions").put("scorer", "synthetic-test-scorer-v1");
        return manifest;
    }

    static ObjectNode frozenCriteria() {
        ObjectNode criteria = (ObjectNode) read("criteria.json");
        for (var threshold : criteria.path("thresholds")) {
            if (!Set.of("quality", "security").contains(threshold.path("id").asString())) continue;
            ObjectNode quality = (ObjectNode) threshold;
            quality.put("state", "FROZEN");
            var values = quality.putObject("after");
            for (String metric : strings(quality, "metrics")) {
                values.put(metric, "synthetic-test-only-not-a-release-threshold");
            }
            quality.put("denominator", "PLANNED_CASE_REPEAT_MODEL_LANE");
            quality.put("minimumSamples", 1).put("repeats", 1).put("stabilityMethod", "synthetic test only");
            quality.putObject("pilotEvidence").put("runId", "synthetic-pilot").put("digest", "a".repeat(64))
                .put("completedAt", "2026-10-19T00:00:00Z");
            quality.put("approvedAt", "2026-10-19T01:00:00Z").put("effectiveAt", "2026-10-20T00:00:00Z");
            quality.putObject("appliesTo").put("dataset", "1.0.0").put("oracle", "1.0.0")
                .put("scorer", "synthetic-test-scorer-v1").put("support", "m8-supported-v1");
        }
        return criteria;
    }

    static ObjectNode record(JsonNode item) {
        ObjectNode record = JSON.createObjectNode();
        record.put("version", "1.0.0").put("runId", "synthetic-run").put("caseId", text(item, "id")).put("caseVersion", "1.0.0")
            .put("repeat", 1).put("model", "synthetic-fake").put("lane", "DETERMINISTIC").put("phase", "QUALIFICATION")
            .put("status", "PASS").put("reason", "").put("denominatorUnit", "PLANNED_CASE_REPEAT_MODEL_LANE");
        record.putArray("exclusions");
        record.set("axes", item.path("axes").deepCopy());
        record.putArray("thresholdIds").add("quality").add("security");
        record.putArray("hardGateViolations");
        var identity = record.putObject("identity");
        identity.put("sourceRevision", "a".repeat(40)).put("sourceDirty", false);
        for (String key : List.of("buildDigest", "configDigest", "datasetDigest", "oracleDigest", "promptDigest", "schemaDigest",
            "scorerDigest", "criteriaDigest", "supportDigest", "bindingsDigest")) identity.put(key, "a".repeat(64));
        identity.put("startedAt", "2026-10-20T01:00:00Z").put("endedAt", "2026-10-20T01:00:01Z")
            .put("stopPoint", "complete").put("parentRunId", "").put("originalEvidenceDigest", "");
        var evidence = record.putObject("evidence");
        var required = evidence.putArray("required");
        var provided = evidence.putArray("provided");
        for (String id : M8RunContract.requiredEvidence(item)) {
            required.add(id);
            provided.addObject().put("id", id).put("path", "build/m8-evidence/synthetic-run/" + id + ".json")
                .put("digest", "a".repeat(64)).put("kind", "DETERMINISTIC");
        }
        record.putObject("review").put("state", "APPROVED").put("rubricVersion", "m8-answer-v1").put("requestDigest", "a".repeat(64))
            .put("answerDigest", "a".repeat(64)).put("reviewer", "synthetic-reviewer").put("reviewedAt", "2026-10-20T01:01:00Z");
        return record;
    }
}
