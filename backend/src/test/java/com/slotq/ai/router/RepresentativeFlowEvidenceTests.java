package com.slotq.ai.router;

import com.slotq.ai.flow.*;
import com.slotq.ai.runtime.RuntimeContract.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** Fabricated validator inputs only; never actual-provider execution evidence. */
class RepresentativeFlowEvidenceTests {
    static final JsonMapper JSON = JsonMapper.builder()
        .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
    @TempDir Path directory;

    @Test void decimalRoutingSurvivesTreeRoundTripAndPartialFailureStaysIncompleteWithUnavailableMetrics() throws Exception {
        fixture(directory);
        assertThatThrownBy(() -> RepresentativeFlowEvidence.recalculate(directory))
            .hasMessage("Integrated flow quality/evidence incomplete");
        var summary = JSON.readTree(Files.readAllBytes(directory.resolve("summary.json")));
        assertThat(summary.size()).isEqualTo(1);
        var management = summary.get(0);
        assertThat(management.path("actualAttempts").asInt()).isEqualTo(1);
        assertThat(management.path("unobservedCases").asInt()).isZero();
        assertThat(management.path("quality").asDouble()).isZero();
        assertThat(management.path("observedTokens").isNull()).isTrue();
        assertThat(management.path("estimatedObservedDollars").isNull()).isTrue();
        assertThat(management.path("costStatus").asString()).isEqualTo("unavailable");
        assertThat(management.path("complete").asBoolean()).isFalse();
    }

    @Test void changedRequestOracleDeadlineOrMissingReviewCannotProduceSummary() throws Exception {
        for (String change : List.of("request", "oracle", "deadline", "review", "source", "specification", "flow", "ledger", "bytes")) {
            Path area = directory.resolve(change);
            fixture(area);
            var row = (ObjectNode) JSON.readTree(Files.readString(area.resolve("attempts.jsonl")));
            if (change.equals("request")) ((ObjectNode) row.path("request")).put("prompt", "changed synthetic request");
            if (change.equals("oracle")) ((ObjectNode) row.path("oracle")).put("partySize", 2);
            if (change.equals("deadline")) ((ObjectNode) row.path("runtime").path("budget")).put("deadline", "2026-10-14T00:11:00Z");
            if (change.equals("bytes")) row.put("requestBodyBytes", 1);
            Files.writeString(area.resolve("attempts.jsonl"), JSON.writeValueAsString(row));
            if (change.equals("source")) Files.writeString(area.resolve("actual-source/backend/src/main/java/com/slotq/ai/flow/RepresentativeContext.java"), "changed source");
            if (change.equals("specification")) {
                var manifest = (ObjectNode) JSON.readTree(Files.readAllBytes(area.resolve("manifest.json")));
                ((ObjectNode) manifest.path("specification")).put("maximumCalls", 7);
                Files.write(area.resolve("manifest.json"), JSON.writeValueAsBytes(manifest));
            }
            if (change.equals("review")) {
                var review = (ObjectNode) JSON.readTree(Files.readAllBytes(area.resolve("content-review.json")));
                review.set("entries", JSON.createArrayNode());
                Files.write(area.resolve("content-review.json"), JSON.writeValueAsBytes(review));
            }
            if (change.equals("flow")) {
                var manifest = (ObjectNode) JSON.readTree(Files.readAllBytes(area.resolve("manifest.json")));
                manifest.put("flow", "ops");
                Files.write(area.resolve("manifest.json"), JSON.writeValueAsBytes(manifest));
            }
            if (change.equals("ledger")) Files.writeString(area.resolve("provider-completions.jsonl"), "");
            assertThatThrownBy(() -> RepresentativeFlowEvidence.recalculate(area)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("quality/evidence incomplete");
            assertThat(Files.exists(area.resolve("summary.json"))).isFalse();
        }
    }

    @Test void managementCompletesIndependentlyAndFrozenExperimentRejectsMixedSources() throws Exception {
        fixture(directory);
        var row = (ObjectNode) JSON.readTree(Files.readString(directory.resolve("attempts.jsonl")));
        var output = new ProviderProtocol.Output("ANSWER", "none", "", 0, "NOT_APPLICABLE",
            List.of("product:reservations", "knowledge:guide"), List.of("product:reservations", "knowledge:guide"),
            "Product 예약 사실과 knowledge:guide 문서 안내를 구분합니다.");
        var usage = new GeminiAdapter.Usage(10L, 10L, 0L, 20L, "measured");
        row.set("result", JSON.valueToTree(new GeminiAdapter.Result(output, null, 1, 10, usage, "estimated", "synthetic-version", 200, "terminal_success")));
        row.set("rawStructuredResponse", JSON.valueToTree(Map.of("usage", usage, "candidates",
            List.of(Map.of("finishReason", "STOP", "texts", List.of(JSON.writeValueAsString(output)))))));
        ((ObjectNode) row.path("runtime")).putNull("failure").put("answer", output.answer());
        Files.writeString(directory.resolve("attempts.jsonl"), JSON.writeValueAsString(row));
        writeLedgers(directory, row);
        var review = (ObjectNode) JSON.readTree(Files.readAllBytes(directory.resolve("content-review.json")));
        ((ObjectNode) review.path("entries").get(0)).put("answerSha256", ComparisonReview.answerDigest(row));
        Files.write(directory.resolve("content-review.json"), JSON.writeValueAsBytes(review));
        RepresentativeFlowEvidence.recalculate(directory);
        assertThat(JSON.readTree(Files.readAllBytes(directory.resolve("summary.json"))).get(0).path("complete").asBoolean()).isTrue();

        var manifest = (ObjectNode) JSON.readTree(Files.readAllBytes(directory.resolve("manifest.json")));
        RepresentativeFlowEvidence.freezeExperiment(directory, manifest);
        RepresentativeFlowEvidence.verifyExperiment(directory, manifest);
        for (String key : List.of("sourceRevision", "contextRevision", "specificationSha256", "profileSha256")) {
            var changed = manifest.deepCopy();
            changed.put(key, "different-revision");
            assertThatThrownBy(() -> RepresentativeFlowEvidence.verifyExperiment(directory, changed))
                .hasMessage("Integrated experiment revision/source identity mismatch");
        }
    }

    @Test void fixedFlowCasesAndRecordedEnvelopeMatchTheAdapterTransport() throws Exception {
        var spec = JSON.readTree(RepresentativeFlowEvidence.resource("/representative-flows/evidence-v6.json"));
        assertThat(RepresentativeFlowEvidence.cases(spec, "customer").stream().map(c -> c.path("id").asString()))
            .containsExactly("customer-hold-proposal", "customer-hold-answer");
        assertThat(RepresentativeFlowEvidence.cases(spec, "management")).hasSize(1);
        assertThat(RepresentativeFlowEvidence.cases(spec, "ops")).hasSize(1);
        assertThatThrownBy(() -> RepresentativeFlowEvidence.requireFlow("all")).isInstanceOf(IllegalArgumentException.class);
        var request = new ProviderProtocol.Request("합성 요청 크기 검증", 1024);
        var adapter = new GeminiAdapter((uri, body, credential, timeout) -> {
            assertThat(body.length).isEqualTo(RepresentativeFlowEvidence.requestBodyBytes(request));
            return new GeminiAdapter.Wire(503, new byte[0]);
        }, new GeminiAdapter.Secret("synthetic-key"));
        var result = adapter.generate("gemini-3.5-flash-lite",
            new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC, true, true, null, null, 50000, 1024), request,
            new ProviderBudget(Clock.systemUTC(), Instant.now().plusSeconds(45), 1, 150000));
        assertThat(result.failure()).isEqualTo(GeminiAdapter.Failure.TEMPORARY_OVERLOAD);
    }

    @Test void experimentRetainsFailedAndUnobservedFlowsAndCustomerCannotJoinDifferentRuns() throws Exception {
        Path management = directory.resolve("management");
        fixture(management);
        var manifest = JSON.readTree(Files.readAllBytes(management.resolve("manifest.json")));
        RepresentativeFlowEvidence.freezeExperiment(directory, manifest);
        assertThatThrownBy(() -> RepresentativeFlowEvidence.recalculateExperiment(directory))
            .hasMessage("Integrated flow quality/evidence incomplete");
        var summary = JSON.readTree(Files.readAllBytes(directory.resolve("summary.json")));
        assertThat(summary.size()).isEqualTo(3);
        assertThat(summary.get(1).path("actualAttempts").asInt()).isEqualTo(1);
        assertThat(summary.get(1).path("failures").get(0).asString()).isEqualTo("TEMPORARY_OVERLOAD");
        assertThat(summary.get(0).path("status").asString()).isEqualTo("unobserved");

        var first = (ObjectNode) JSON.readTree(Files.readString(management.resolve("attempts.jsonl")));
        first.put("workload", "customer");
        ((ObjectNode) first.path("runtime")).put("execution", "WAITING_CONFIRMATION").putNull("failure");
        var second = first.deepCopy();
        second.set("before", first.path("runtime").deepCopy());
        ((ObjectNode) second.path("before")).put("productEffect", "SUCCEEDED").put("knownTarget", UUID.randomUUID().toString());
        RepresentativeFlowEvidence.verifyCustomerContinuity(List.of(first, second));
        for (String change : List.of("runId", "providerAttempts", "deadline", "productEffect")) {
            var changed = second.deepCopy();
            var before = (ObjectNode) changed.path("before");
            if (change.equals("runId")) before.put("runId", UUID.randomUUID().toString());
            if (change.equals("providerAttempts")) ((ObjectNode) before.path("budget")).put("providerAttempts", 0);
            if (change.equals("deadline")) ((ObjectNode) before.path("budget")).put("deadline", "2026-10-14T00:11:00Z");
            if (change.equals("productEffect")) before.put("productEffect", "UNKNOWN");
            assertThatThrownBy(() -> RepresentativeFlowEvidence.verifyCustomerContinuity(List.of(first, changed)))
                .hasMessage("Integrated Customer proposal/HOLD/answer continuity invalid");
        }
    }

    static void fixture(Path area) throws Exception {
        Files.createDirectories(area);
        byte[] specBytes = RepresentativeFlowEvidence.resource("/representative-flows/evidence-v6.json");
        var spec = JSON.readTree(specBytes);
        String specDigest = RepresentativeFlowEvidence.digest(specBytes);
        String policyDigest = RepresentativeFlowEvidence.digest(RepresentativeFlowEvidence.resource("/model-router/content-review-policy-v2.json"));
        var sources = new TreeMap<String,String>();
        for (String file : List.of("ai/flow/RepresentativeContext", "ai/flow/RepresentativeFlows", "ai/flow/RepresentativeRouterProfile",
                "ai/runtime/AgentRuntime", "ai/runtime/RuntimeContract", "ai/router/GeminiAdapter", "ai/router/ModelRouter", "ai/router/ProviderProtocol", "ai/router/ProviderBudget")) {
            sources.put(file, RepresentativeFlowEvidence.archiveSource(area, "src/main/java/com/slotq/" + file + ".java"));
        }
        for (String file : List.of("ai/router/RepresentativeFlowEvidence", "ai/router/ComparisonReview", "ai/router/ModelComparisonRunner", "mcp/RepresentativeFlowIntegrationTests")) {
            sources.put(file, RepresentativeFlowEvidence.archiveSource(area, "src/test/java/com/slotq/" + file + ".java"));
        }
        var manifest = new LinkedHashMap<String,Object>(Map.of(
            "sourceDigests", sources,
            "specification", spec, "specificationSha256", specDigest, "reviewPolicySha256", policyDigest,
            "schemaSha256", RepresentativeFlowEvidence.digest(ProviderProtocol.SCHEMA_JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "profileSha256", RepresentativeFlowEvidence.digest(RepresentativeFlowEvidence.resource("/ai/representative-router-v1.json")),
            "contextRevision", RepresentativeContext.REVISION,
            "generationConfig", Map.of("temperature", 1, "candidateCount", 1, "maxOutputTokens", 1024,
                "responseMimeType", "application/json", "apiMode", GeminiAdapter.API_MODE),
            "httpControls", Map.of("version", "HTTP_1_1", "disableRetryConnect", true, "enableAllMethodRetry", false,
                "retryLimit", "1", "redirects", "NEVER")));
        manifest.put("flow", "management");
        manifest.put("revision", spec.path("revision").asString());
        manifest.put("sourceRevision", "deterministic-validator-fixture");
        manifest.put("tier", "user-authorized-free/no-paid-calls");
        Files.write(area.resolve("manifest.json"), JSON.writeValueAsBytes(manifest));
        Instant now = Instant.parse("2026-10-14T00:00:00Z");
        var request = new ProviderProtocol.Request("deterministic validator fixture; no provider call", 1024,
            Set.of("product:reservations", "knowledge:guide"), Set.of("product:reservations", "knowledge:guide"));
        var plan = new Plan(RepresentativeFlows.REVISION, "management", Limits.bounded(), null,
            Map.of("management.reservations.list", Map.of("date", "2026-10-15")));
        var routing = new ModelRouter().route(new ModelRouter.Snapshot(plan.revision(), plan.workload(),
            new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC, true, true, null, null, 50000, 1024),
            RepresentativeRouterProfile.candidates(), ModelRouter.Policy.bounded(),
            new ModelRouter.Remaining(3, 150000, BigDecimal.ZERO, now, now.plusSeconds(600))));
        UUID run = UUID.randomUUID();
        var before = new Result(run, Execution.ACTIVE, Effect.NOT_APPLICABLE, null, Map.of(), Map.of(), List.of(), List.of(),
            new Budget(0, 0, 0, 150000, 6144, BigDecimal.ZERO, false, 0, now, now.plusSeconds(600)), null, null);
        var after = new Result(run, Execution.TERMINAL, Effect.NOT_APPLICABLE, null, Map.of(), Map.of(),
            List.of(new ProviderObservation(UUID.randomUUID(), 1, "gemini-3.5-flash-lite", true, false, true, routing)), List.of(),
            new Budget(1, 1, 0, 140000, 5120, BigDecimal.ZERO, true, 0, now, now.plusSeconds(600)), null, "terminal_unavailable");
        String requestDigest = RepresentativeFlowEvidence.digest(request.prompt().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var testCase = RepresentativeFlowEvidence.testCase(spec, "management-owner");
        var row = new LinkedHashMap<String,Object>();
        row.put("fixtureKind", "deterministic-validator-only");
        row.put("evidenceKind", "actual-provider");
        row.put("caseId", "management-owner"); row.put("workload", "management");
        row.put("model", "gemini-3.5-flash-lite"); row.put("repeat", 1);
        row.put("request", request); row.put("requestSha256", requestDigest);
        row.put("requestBodyBytes", RepresentativeFlowEvidence.requestBodyBytes(request));
        row.put("requestSchemaSha256", RepresentativeFlowEvidence.digest(JSON.writeValueAsBytes(ProviderProtocol.schema(request))));
        row.put("plan", plan); row.put("before", before); row.put("runtime", after);
        row.put("result", new GeminiAdapter.Result(null, GeminiAdapter.Failure.TEMPORARY_OVERLOAD, 1, 10,
            new GeminiAdapter.Usage(null, null, null, null, "unavailable"), "unavailable", null, 503, "terminal_unavailable"));
        row.put("rawStructuredResponse", Map.of()); row.put("runDurationMillis", 10); row.put("approvalWaitMillis", 0);
        row.put("oracle", Map.of("disposition", "ANSWER", "target", "", "partySize", 0, "outcome", "NOT_APPLICABLE",
            "sources", testCase.path("requiredSources"), "required", testCase.path("requiredClaims"),
            "allowedSources", request.knownSources(), "allowed", request.knownClaims()));
        Files.writeString(area.resolve("attempts.jsonl"), JSON.writeValueAsString(row));
        writeLedgers(area, JSON.valueToTree(row));
        Files.write(area.resolve("content-review.json"), JSON.writeValueAsBytes(Map.of(
            "revision", "m7-content-review-v1", "specificationSha256", specDigest, "reviewPolicySha256", policyDigest,
            "entries", List.of(Map.of("caseId", "management-owner", "model", "gemini-3.5-flash-lite", "repeat", 1,
                "requestSha256", requestDigest, "answerSha256", RepresentativeFlowEvidence.digest(new byte[0]),
                "findings", List.of(), "reasonCode", "deterministic_validator_fixture")))));
    }

    static void writeLedgers(Path area, tools.jackson.databind.JsonNode row) throws Exception {
        Files.writeString(area.resolve("provider-starts.jsonl"), JSON.writeValueAsString(Map.of(
            "caseId", row.path("caseId"), "model", row.path("model"), "request", row.path("request"),
            "requestSha256", row.path("requestSha256"), "requestBodyBytes", row.path("requestBodyBytes"))));
        Files.writeString(area.resolve("provider-completions.jsonl"), JSON.writeValueAsString(Map.of(
            "caseId", row.path("caseId"), "requestSha256", row.path("requestSha256"),
            "result", row.path("result"), "rawStructuredResponse", row.path("rawStructuredResponse"))));
    }
}
