package com.slotq.evaluation;

import com.slotq.ai.router.ProviderProtocol;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;
import static org.assertj.core.api.Assertions.*;

class M8OracleV2Tests {
    private static JsonNode v2(String file) { return JSON.readTree(resource("evaluation/m8-v2/" + file)); }
    private static JsonNode item(String id) { return v2("dataset.json").path("cases").valueStream().filter(c -> c.path("id").asString().equals(id)).findFirst().orElseThrow(); }

    @Test void approvedCorrectionPreservesAllOtherCasesAndPendingCriteria() {
        validateDataset(v2("dataset.json"), v2("case-history.json"), M8FixtureValidator::resource);
        assertThat(resource("evaluation/m8-v2/criteria.json")).isEqualTo(resource("evaluation/m8-v1/criteria.json"));
        assertThat(resource("evaluation/m8-v2/review-rubric.json")).isEqualTo(resource("evaluation/m8-v1/review-rubric.json"));
        assertThat(v2("dataset.json").path("cases")).hasSize(60);
        assertThatThrownBy(() -> selectedVersion("2.1.0")).hasMessageContaining("unsupported dataset");
        var manifest = (ObjectNode) read("manifest-regression.json");
        ((ObjectNode) manifest.path("versions")).put("dataset", DATASET_VERSION.equals("1.0.0") ? "2.0.0" : "1.0.0");
        assertThatThrownBy(() -> M8EnvironmentContract.validate(manifest)).hasMessageContaining("dataset");
    }

    @ParameterizedTest @ValueSource(strings = {"third-case", "execution", "history", "version", "approval", "future-application", "review-obligation"})
    void unapprovedScopeVersionHistoryOrApprovalChangesAreRejected(String defect) {
        var dataset = (ObjectNode) v2("dataset.json"); var history = (ObjectNode) v2("case-history.json"); var change = (ObjectNode) v2("oracle-change.json");
        switch (defect) {
            case "third-case" -> ((ObjectNode) dataset.path("cases").get(0)).put("required", false);
            case "execution" -> ((ObjectNode) dataset.path("cases").valueStream().filter(c -> c.path("id").asString().equals("m8/unknown-committed")).findFirst().orElseThrow().path("oracle").path("execution")).put("effect", "SUCCEEDED");
            case "history" -> ((ObjectNode) history.path("entries").get(0)).put("everCalibrated", true);
            case "version" -> dataset.put("oracleVersion", "1.0.0");
            case "approval" -> change.put("approvalReference", "unapproved");
            case "future-application" -> change.put("effectiveAt", java.time.Instant.now().plusSeconds(3600).toString());
            case "review-obligation" -> ((ObjectNode) change.path("semanticReviewObligations")).remove("m8/unknown-committed");
        }
        assertThatThrownBy(() -> {
            validateDataset(dataset, history, M8FixtureValidator::resource);
            validateV2(dataset, history, change);
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void finalProviderRequestIsBoundAndReferenceSetOrderIsNotMeaningful() {
        String id = "m8/unknown-committed"; var raw = vector(id); var request = (ObjectNode) raw.get("request");
        var protocol = (ObjectNode) request.deepCopy(); protocol.remove(List.of("context", "claimSources"));
        raw.put("provider-call-a", JSON.createObjectNode().set("request", protocol));
        ((ObjectNode) raw.get("result")).put("observationKind", "ACTUAL_PROVIDER_PRODUCTION_RUNTIME");
        for (String field : List.of("knownSources", "knownClaims")) {
            var refs = new ArrayList<>(strings(request, field)); Collections.reverse(refs); request.set(field, JSON.valueToTree(refs));
        }
        assertThat(M8Scorer.score(item(id), raw).checks().get("request_provider_identity").status()).isEqualTo("PASS");
        raw.remove("provider-call-a");
        assertThat(M8Scorer.score(item(id), raw).checks().get("request_provider_identity").status()).isEqualTo("INCOMPLETE");
    }

    @ParameterizedTest @ValueSource(strings = {"m8/customer-business-rejected", "m8/unknown-committed"})
    void realContextReferencesAreRequiredRatherThanAnEmptyAllowedSet(String id) {
        var raw = vector(id); var score = M8Scorer.score(item(id), raw);
        for (String check : List.of("tool_selection", "answer_sources", "answer_claims", "answer_claim_sources", "request_attribution_context")) {
            assertThat(score.checks().get(check).status()).as(check).isEqualTo("PASS");
        }
        ((ObjectNode) raw.get("result").path("proposal")).putArray("claimRefs");
        assertThat(M8Scorer.score(item(id), raw).checks().get("answer_claims").status()).isEqualTo("FAIL");
    }

    @ParameterizedTest @ValueSource(strings = {"source", "claim", "mapping", "mapping-empty", "expanded-set", "transmitted-request"})
    void outsideReferencesUnsupportedMappingAndPostHocExpansionStillFail(String defect) {
        String id = "m8/unknown-committed"; var raw = vector(id); var output = (ObjectNode) raw.get("result").path("proposal"); var request = (ObjectNode) raw.get("request");
        switch (defect) {
            case "source" -> output.withArray("sourceRefs").add("invented:source");
            case "claim" -> output.withArray("claimRefs").add("invented:claim");
            case "mapping" -> request.withObject("claimSources").putArray("product:mutation").add("product:current");
            case "mapping-empty" -> request.withObject("claimSources").putArray("product:mutation");
            case "expanded-set" -> request.withArray("knownSources").add("invented:source");
            case "transmitted-request" -> {
                var protocol = (ObjectNode) request.deepCopy(); protocol.remove(List.of("context", "claimSources"));
                protocol.put("maximumOutputTokens", 512);
                raw.put("provider-call-a", JSON.createObjectNode().set("request", protocol));
                ((ObjectNode) raw.get("result")).put("observationKind", "ACTUAL_PROVIDER_PRODUCTION_RUNTIME");
            }
        }
        assertThat(M8Scorer.score(item(id), raw).status()).isEqualTo("FAIL");
    }

    @ParameterizedTest @ValueSource(strings = {"absent", "sent", "target", "status", "state", "context", "mapping", "request"})
    void absentGetOrRequestEvidenceDoesNotActivateAnswerDisposition(String missing) {
        String id = "m8/unknown-committed"; var raw = vector(id); var get = (ObjectNode) raw.get("tool-transmissions").path("exactGet");
        switch (missing) {
            case "absent" -> ((ObjectNode) raw.get("tool-transmissions")).remove("exactGet");
            case "context", "mapping" -> ((ObjectNode) raw.get("request")).remove(missing.equals("context") ? "context" : "claimSources");
            case "request" -> raw.remove("request");
            default -> get.remove(missing);
        }
        var score = M8Scorer.score(item(id), raw);
        assertThat(score.checks().get("answer_observation_basis").status()).isEqualTo("INCOMPLETE");
        assertThat(score.checks().get("tool_selection").status()).isEqualTo("INCOMPLETE");
        assertThat(score.status()).isEqualTo("INCOMPLETE");
    }

    @ParameterizedTest @ValueSource(strings = {"not-sent", "other-target", "404", "confirmed", "context-state", "tool", "outcome", "disposition", "approval", "partial-commit"})
    void observedContradictionsAndHardFailuresCannotBeHiddenByMissingEvidence(String defect) {
        String id = "m8/unknown-committed"; var raw = vector(id); var get = (ObjectNode) raw.get("tool-transmissions").path("exactGet");
        switch (defect) {
            case "not-sent" -> get.put("sent", false);
            case "other-target" -> get.put("target", UUID.randomUUID().toString());
            case "404" -> get.put("status", 404);
            case "confirmed" -> get.put("state", "CONFIRMED");
            case "context-state" -> ((ObjectNode) raw.get("request").path("context").path("currentExactGet").path("facts")).put("state", "CONFIRMED");
            case "disposition" -> ((ObjectNode) raw.get("result").path("proposal")).put("disposition", "INSUFFICIENT");
            case "tool" -> { get.remove("status"); ((ObjectNode) raw.get("result").path("proposal")).put("tool", "reservation.hold"); }
            case "outcome" -> { get.remove("status"); ((ObjectNode) raw.get("result").path("proposal")).put("outcome", "HELD"); }
            case "approval" -> { get.remove("status"); ((ObjectNode) raw.get("tool-transmissions").path("approval")).put("approved", false); }
            case "partial-commit" -> { get.remove("status"); ((ObjectNode) raw.get("independent-db").path("after")).put("allocations", 0); }
        }
        var score = M8Scorer.score(item(id), raw); assertThat(score.status()).isEqualTo("FAIL");
        assertThat(score.checks().get("execution_effect").status()).isEqualTo("PASS");
    }

    @Test void separateV2JvmsSealComparableIdentityAndRecalculateWithoutProvider() throws Exception {
        var control = M8ProtectedFiles.root("oracle-v2-proof-" + UUID.randomUUID()); M8ProtectedFiles.create(control);
        var identities = new ArrayList<JsonNode>();
        for (int i = 0; i < 2; i++) {
            String run = "oracle-v2-" + UUID.randomUUID(); child(control, "collect-" + i, "2.0.0", M8OracleV2Tests.class.getName(), run);
            var root = M8ProtectedFiles.root(run); var plan = JSON.readTree(Files.readAllBytes(root.resolve("plan.json")));
            var source = M8Evaluation.readArtifact(root, plan.path("snapshots").path("source-build"));
            assertThat(plan.path("scorer").asString()).isEqualTo("m8-scorer-v2");
            assertThat(plan.path("snapshots").has("oracle-change.json")).isTrue();
            var result = JSON.readTree(Files.readAllBytes(root.resolve("results.json")));
            assertThat(result.path("rows")).allSatisfy(row -> {
                assertThat(row.path("automaticStatus").asString()).isEqualTo("PASS");
                assertThat(row.path("checks").path("human_review").path("status").asString()).isEqualTo("INCOMPLETE");
                assertThat(row.path("cell").path("caseVersion").asString()).isEqualTo("2.0.0");
            });
            identities.add(M8ModelQualification.comparisonIdentity(source, plan.path("snapshots")));
            child(control, "recalculate-" + i, "2.0.0", M8OracleV2Tests.class.getName(), "--recalculate", run);
            child(control, "wrong-profile-" + i, "1.0.0", M8OracleV2Tests.class.getName(), "--expect-profile-rejection", run);
            try (var paths = Files.list(root.resolve("recalculations"))) {
                var recalculated = paths.findFirst().orElseThrow();
                for (String file : List.of("results.json", "summary.ko.md")) assertThat(Files.readAllBytes(recalculated.resolve(file))).isEqualTo(Files.readAllBytes(root.resolve(file)));
            }
        }
        assertThat(identities.get(0)).isEqualTo(identities.get(1));
        var changed = (ObjectNode) identities.get(1).deepCopy(); changed.put("oracle-change.json", "0".repeat(64));
        assertThat(changed).isNotEqualTo(identities.get(0));
    }

    private static void child(Path control, String name, String datasetVersion, String type, String... args) throws Exception {
        var file = control.resolve(name + ".args");
        Files.writeString(file, "-Xmx256m\n-Dslotq.m8.datasetVersion=" + datasetVersion + "\n--class-path\n\""
            + System.getProperty("slotq.test.runtime-classpath").replace('\\', '/') + "\"\n" + type + "\n" + String.join("\n", args), StandardCharsets.UTF_8);
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + file)
            .redirectErrorStream(true).redirectOutput(control.resolve(name + ".log").toFile()).start();
        try { assertThat(process.waitFor(60, TimeUnit.SECONDS)).as(name).isTrue(); assertThat(process.exitValue()).as(name).isZero(); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("--expect-profile-rejection")) {
            var root = M8ProtectedFiles.root(args[1]);
            assertThatThrownBy(() -> M8Evaluation.calculate(root, Files.readString(root.resolve("evidence.sha256"))))
                .hasMessageContaining("scorer"); return;
        }
        if (args[0].equals("--recalculate")) {
            System.setProperty("slotq.m8.runId", args[1]);
            System.setProperty("slotq.m8.digest", Files.readString(M8ProtectedFiles.root(args[1]).resolve("evidence.sha256")));
            M8Evaluation.main(new String[0]); return;
        }
        var cells = M8Evaluation.cells(List.of("m8/customer-business-rejected", "m8/unknown-committed"), Set.of("deterministic"), Set.of("DETERMINISTIC"), 1);
        var session = new M8Evaluation(args[0], cells, M8EvaluationTests.manifest(), M8EvaluationTests.budget(2),
            c -> M8Bindings.create(c, args[0], M8EvaluationTests.BASE, Map.of()), null);
        var result = session.execute((cell, input, recorder) -> vector(cell.caseId(), recorder.binding()).forEach((k, v) -> M8EvaluationTests.put(recorder, k, v)));
        // A synthetic reviewer verdict exercises the existing rubric binding; it is not a real human approval.
        var row = result.path("rows").get(1); var bundle = JSON.createObjectNode().put("version", "m8-answer-v1").put("runId", args[0]);
        var review = bundle.putArray("reviews").addObject().put("runId", args[0]).put("state", "REJECTED")
            .put("rubricVersion", "m8-answer-v1").put("reviewer", "synthetic-rubric-regression").put("reviewedAt", java.time.Instant.now().toString());
        review.set("cell", row.path("cell")); review.set("requestDigest", row.path("evidence").path("request").path("digest"));
        review.set("answerDigest", row.path("evidence").path("answer").path("digest"));
        var findings = review.putObject("findings"); for (String key : strings(read("review-rubric.json"), "required")) findings.put(key, true);
        findings.put("outcome_distortion", false);
        byte[] bytes = JSON.writeValueAsBytes(bundle);
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest(), bytes, digest(bytes)).path("status").asString()).isEqualTo("FAIL");
        review.put("state", "APPROVED").put("answerDigest", "0".repeat(64)); findings.put("outcome_distortion", true);
        bytes = JSON.writeValueAsBytes(bundle);
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest(), bytes, digest(bytes)).path("rows").get(1)
            .path("checks").path("human_review").path("status").asString()).isEqualTo("INCOMPLETE");
    }

    private static Map<String, JsonNode> vector(String id) {
        return vector(id, M8Bindings.create(M8EvaluationTests.cell(id), "v2-vector", M8EvaluationTests.BASE, Map.of()));
    }

    private static Map<String, JsonNode> vector(String id, JsonNode binding) {
        var raw = M8EvaluationTests.vector(item(id), binding); boolean unknown = id.equals("m8/unknown-committed");
        var context = JSON.createObjectNode().put("workload", "customer").put("mutationOutcome", unknown ? "UNKNOWN" : "REJECTED").put("limitations", "고정된 합성 관측의 한계");
        context.putObject("productMutation").put("sourceRef", "product:mutation").put("claimRef", "product:mutation").put("readOrProductOutcome", unknown ? "outcome_unknown" : "rejected");
        var sources = new HashSet<>(Set.of("product:mutation")); var claims = new HashSet<>(Set.of("product:mutation", "limitation:scope"));
        if (unknown) {
            context.putObject("currentExactGet").put("sourceRef", "product:current").put("claimRef", "product:current").put("readOrProductOutcome", "succeeded").putObject("facts").put("state", "HELD");
            sources.add("product:current"); claims.add("product:current");
        }
        raw.put("request", M8Bindings.request(new ProviderProtocol.Request("고정된 합성 context:\n" + JSON.writeValueAsString(context) + "\n응답 제어 필드: none", 1024, sources, claims)));
        return raw;
    }
}
