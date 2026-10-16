package com.slotq.ai.router;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

class CorrectiveComparisonTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant OBSERVED = Instant.parse("2026-10-13T00:00:00Z");
    @TempDir Path directory;

    @Test void primaryFixturesAndOracleRemainUnchangedAndCustomerRequestIsIdentical() throws Exception {
        var baseline = JSON.readTree(ModelComparisonRunner.fixture("m7-comparison-v2"));
        var corrective = specification();
        assertThat(corrective.path("oracleRevision")).isEqualTo(baseline.path("oracleRevision"));
        assertThat(corrective.path("maximumCalls").asInt()).isEqualTo(96);
        assertThat(corrective.path("models")).isEqualTo(baseline.path("models"));
        for (int index = 0; index < 12; index++) {
            assertThat(corrective.path("cases").get(index)).isEqualTo(baseline.path("cases").get(index));
            if (index < 4) {
                assertThat(ModelComparisonRunner.request(corrective, corrective.path("cases").get(index)))
                    .isEqualTo(ModelComparisonRunner.request(baseline, baseline.path("cases").get(index)));
            }
        }
        for (String workload : List.of("management", "ops")) {
            long holdouts = values(corrective.path("cases")).stream()
                .filter(c -> workload.equals(c.path("workload").asString()) && c.path("holdout").asBoolean(false)).count();
            assertThat(holdouts).isEqualTo(2);
        }
    }

    @Test void promptContainsOnlyWorkloadContractAndEvidenceNeverOracleOrCaseIdentity() throws Exception {
        var spec = specification();
        for (JsonNode testCase : spec.path("cases")) {
            var request = ModelComparisonRunner.request(spec, testCase);
            assertThat(request.prompt()).doesNotContain(testCase.path("id").asString(), "\"oracle\"", "\"allowedSources\"");
            var mutated = JSON.readTree(JSON.writeValueAsBytes(testCase));
            ((tools.jackson.databind.node.ObjectNode) mutated).put("oracle", "substituted-hidden-answer");
            assertThat(ModelComparisonRunner.request(spec, mutated)).isEqualTo(request);
        }
        assertThat(ModelComparisonRunner.request(spec, spec.path("cases").get(9)).prompt())
            .contains("WORKLOAD: ops", "time-window boundaries", "Missing query bounds do not establish an empty result");
    }

    @Test void originalReadUnknownAndPartialObservationFailuresStillFailTheFrozenOracle() throws Exception {
        var spec = specification();
        var read = spec.path("cases").get(6);
        var unknown = new ProviderProtocol.Output("INSUFFICIENT", "none", "", 0, "UNKNOWN", List.of(),
            List.of("product:list-unavailable"), "예약 조회 결과가 없어 현재 건수를 확인할 수 없습니다.");
        assertThat(ModelComparisonRunner.grade(read, unknown).get("productOutcome")).isFalse();
        var corrected = new ProviderProtocol.Output("INSUFFICIENT", "none", "", 0, "NOT_APPLICABLE", List.of(),
            List.of("product:list-unavailable"), unknown.answer());
        assertThat(ModelComparisonRunner.grade(read, corrected).values()).containsOnly(true);
        var partial = spec.path("cases").get(8);
        var insufficient = new ProviderProtocol.Output("INSUFFICIENT", "none", "", 0, "NOT_APPLICABLE", List.of(),
            List.of("ops:projection-delayed", "ops:delivery-pending", "ops:receipt-absent", "limitation:not-health-proof"),
            "관측은 지연·대기 상태이며 수신 기록이 없습니다. 전체 상태는 증명할 수 없습니다.");
        assertThat(ModelComparisonRunner.grade(partial, insufficient).get("clarification")).isFalse();
        var answer = new ProviderProtocol.Output("ANSWER", "none", "", 0, "NOT_APPLICABLE", List.of(),
            insufficient.claimRefs(), insufficient.answer());
        assertThat(ModelComparisonRunner.grade(partial, answer).values()).containsOnly(true);
    }

    @Test void completeFakeMeasurementsRouteEachWorkloadAndReplayWithoutProviderNetwork() throws Exception {
        writeEvidence((c, model, repeat) -> oracleOutput(c), false);
        ModelComparisonRunner.recalculate(directory);
        var routing = JSON.readValue(Files.readAllBytes(directory.resolve("routing.json")), ModelRouter.Decision[].class);
        for (var decision : routing) {
            assertThat(decision.selected()).isEqualTo("gemini-3.1-flash-lite");
            assertThat(new ModelRouter().replay(decision)).isTrue();
            assertThat(decision.snapshot().candidates()).allSatisfy(c ->
                assertThat(c.measurement().workload()).isEqualTo(decision.snapshot().workload()));
        }
        byte[] first = Files.readAllBytes(directory.resolve("routing.json"));
        ModelComparisonRunner.recalculate(directory);
        assertThat(Files.readAllBytes(directory.resolve("routing.json"))).isEqualTo(first);
    }

    @Test void holdoutCannotDiluteFailureInOriginalPrimaryMatrix() throws Exception {
        writeEvidence((c, model, repeat) -> {
            var output = oracleOutput(c);
            if (c.path("id").asString().equals("ops-normal") && repeat < 3) {
                return new ProviderProtocol.Output("INSUFFICIENT", "none", "", 0, "NOT_APPLICABLE",
                    output.sourceRefs(), output.claimRefs(), output.answer());
            }
            return output;
        }, false);
        ModelComparisonRunner.recalculate(directory);
        var ops = decision("ops");
        assertThat(ops.selected()).isNull();
        assertThat(ops.scores()).isEmpty();
    }

    @Test void fullContentFindingExcludesCandidateEvenWhenStructuredOraclePasses() throws Exception {
        writeEvidence((c, model, repeat) -> oracleOutput(c), true);
        ModelComparisonRunner.recalculate(directory);
        var ops = decision("ops");
        assertThat(ops.selected()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(ops.excluded().get("gemini-3.1-flash-lite")).contains("measurement_missing_invalid_or_unavailable");
        var summary = JSON.readTree(Files.readAllBytes(directory.resolve("summary.json")));
        var rejected = values(summary).stream().filter(s -> "ops".equals(s.path("workload").asString())
            && "gemini-3.1-flash-lite".equals(s.path("model").asString())).findFirst().orElseThrow();
        assertThat(rejected.path("allOraclePass").asInt()).isEqualTo(18);
        assertThat(rejected.path("safetyViolations").asInt()).isEqualTo(1);
        assertThat(rejected.path("holdoutGate").asBoolean()).isFalse();
    }

    @Test void missingReviewDoesNotCreateEligibleMeasurements() throws Exception {
        writeEvidence((c, model, repeat) -> oracleOutput(c), false);
        Files.delete(directory.resolve("content-review.json"));
        ModelComparisonRunner.recalculate(directory);
        assertThat(decision("customer").selected()).isNull();
        assertThat(decision("management").selected()).isNull();
        assertThat(decision("ops").selected()).isNull();
    }

    @Test void substitutedFixtureAnnotationOrReviewPolicyIsRejected() throws Exception {
        writeEvidence((c, model, repeat) -> oracleOutput(c), false);
        var review = JSON.readTree(Files.readAllBytes(directory.resolve("content-review.json")));
        ((tools.jackson.databind.node.ObjectNode) review).put("specificationSha256", "substituted-oracle");
        Files.write(directory.resolve("content-review.json"), JSON.writeValueAsBytes(review));
        assertThatThrownBy(() -> ModelComparisonRunner.recalculate(directory))
            .isInstanceOf(IllegalStateException.class).hasMessage("Content review fixture/policy identity mismatch");
        writeEvidence((c, model, repeat) -> oracleOutput(c), false);
        var manifest = JSON.readTree(Files.readAllBytes(directory.resolve("manifest.json")));
        ((tools.jackson.databind.node.ObjectNode) manifest).put("reviewPolicySha256", "relaxed-policy");
        Files.write(directory.resolve("manifest.json"), JSON.writeValueAsBytes(manifest));
        assertThatThrownBy(() -> ModelComparisonRunner.recalculate(directory))
            .isInstanceOf(IllegalStateException.class).hasMessage("Comparison prompt/parser/review policy identity mismatch");
    }

    private JsonNode specification() throws Exception {
        return JSON.readTree(ModelComparisonRunner.fixture("m7-comparison-v3"));
    }

    private ModelRouter.Decision decision(String workload) throws Exception {
        for (JsonNode entry : JSON.readTree(Files.readAllBytes(directory.resolve("routing.json")))) {
            if (workload.equals(entry.path("snapshot").path("workload").asString())) {
                return JSON.treeToValue(entry, ModelRouter.Decision.class);
            }
        }
        throw new AssertionError("Missing workload decision");
    }

    // This writes injected measurements, not actual-provider or semantic content-review evidence.
    @FunctionalInterface
    private interface OutputFactory {
        ProviderProtocol.Output create(JsonNode testCase, String model, int repeat);
    }

    private void writeEvidence(OutputFactory outputFactory, boolean unsafeHoldout) throws Exception {
        var spec = specification();
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("evidenceKind", "deterministic-fake/synthetic-tool-fixtures");
        manifest.put("specification", spec);
        manifest.put("specificationSha256", digest(ModelComparisonRunner.fixture("m7-comparison-v3")));
        manifest.put("schemaSha256", digest(ProviderProtocol.SCHEMA_JSON.getBytes(StandardCharsets.UTF_8)));
        manifest.put("reviewPolicySha256", ModelComparisonRunner.reviewPolicyDigest());
        manifest.put("promptRevision", "m7-workload-prompt-v3");
        manifest.put("requestAssemblyRevision", "m7-workload-context-v3");
        manifest.put("parserRevision", ProviderProtocol.REVISION);
        manifest.put("training", true);
        manifest.put("generationConfig", Map.of("temperature",1,"candidateCount",1,"maxOutputTokens",1024,
            "responseMimeType","application/json","apiMode",GeminiAdapter.API_MODE));
        manifest.put("transportRevision", "m7-http-v2");
        manifest.put("startedAt", OBSERVED.toString());
        manifest.put("httpControls", Map.of("version", "HTTP_1_1", "redirects", "NEVER", "retryLimit", "1",
            "disableRetryConnect", true, "enableAllMethodRetry", false));
        // Use a time-consistent injected free price, independent of the test's wall clock.
        manifest.put("price", new ModelRouter.Price("fake-free-v1", "free-attested", BigDecimal.ZERO,
            BigDecimal.ZERO, "injected-price", OBSERVED));
        Files.write(directory.resolve("manifest.json"), JSON.writeValueAsBytes(manifest));
        var rows = new ArrayList<String>();
        var entries = new ArrayList<Map<String, Object>>();
        for (JsonNode testCase : spec.path("cases")) {
            for (int repeat = 1; repeat <= 3; repeat++) {
                for (String model : List.of("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")) {
                    var request = ModelComparisonRunner.request(spec, testCase);
                    var output = outputFactory.create(testCase, model, repeat);
                    var row = new LinkedHashMap<String, Object>();
                    row.put("caseId", testCase.path("id").asString());
                    row.put("workload", testCase.path("workload").asString());
                    row.put("model", model);
                    row.put("repeat", repeat);
                    row.put("observedAt", OBSERVED.toString());
                    row.put("requestSha256", digest(request.prompt().getBytes(StandardCharsets.UTF_8)));
                    row.put("requestSchemaSha256", digest(JSON.writeValueAsBytes(ProviderProtocol.schema(request))));
                    row.put("result", new GeminiAdapter.Result(output, null, 1, 1000,
                        new GeminiAdapter.Usage(100L, 50L, null, 150L, "measured"), "estimated", model, 200, "terminal_success"));
                    row.put("rawStructuredResponse", Map.of("usage", new GeminiAdapter.Usage(100L,50L,null,150L,"measured"),
                        "candidates", List.of(Map.of("texts", List.of(JSON.writeValueAsString(output))))));
                    rows.add(JSON.writeValueAsString(row));
                    var node = JSON.readTree(JSON.writeValueAsBytes(row));
                    boolean finding = unsafeHoldout && "ops-holdout-normal".equals(testCase.path("id").asString())
                        && model.equals("gemini-3.1-flash-lite") && repeat == 1;
                    entries.add(Map.of("caseId", testCase.path("id").asString(), "model", model, "repeat", repeat,
                        "requestSha256", row.get("requestSha256"), "answerSha256", ComparisonReview.answerDigest(node),
                        "findings", finding ? List.of("unsupported_claim") : List.of(),
                        "reasonCode", finding ? "injected-unobserved-health-claim" : "injected-no-finding"));
                }
            }
        }
        Files.write(directory.resolve("attempts.jsonl"), rows, StandardCharsets.UTF_8);
        Files.write(directory.resolve("content-review.json"), JSON.writeValueAsBytes(Map.of("revision", ComparisonReview.REVISION,
            "specificationSha256", manifest.get("specificationSha256"), "reviewPolicySha256", ModelComparisonRunner.reviewPolicyDigest(), "entries", entries)));
    }

    private static ProviderProtocol.Output oracleOutput(JsonNode testCase) {
        var oracle = testCase.path("oracle");
        boolean hold = "PROPOSE_HOLD".equals(oracle.path("disposition").asString());
        return new ProviderProtocol.Output(oracle.path("disposition").asString(), hold ? "reservation.hold" : "none",
            oracle.path("target").asString(), oracle.path("partySize").asInt(), oracle.path("outcome").asString(),
            values(oracle.path("sources")).stream().map(JsonNode::asString).toList(),
            values(oracle.path("required")).stream().map(JsonNode::asString).toList(), "제공된 사실과 관측 한계만 설명합니다.");
    }

    private static List<JsonNode> values(JsonNode array) {
        var values = new ArrayList<JsonNode>();
        array.forEach(values::add);
        return values;
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
