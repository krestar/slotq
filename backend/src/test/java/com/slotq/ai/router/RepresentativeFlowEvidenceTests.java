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
        assertThat(summary.get(0).path("quality").isNull()).isTrue();
        assertThat(summary.get(0).path("p95ProviderMillis").isNull()).isTrue();
        assertThat(summary.get(0).path("complete").asBoolean()).isFalse();
        var management = summary.get(1);
        assertThat(management.path("actualAttempts").asInt()).isEqualTo(1);
        assertThat(management.path("unobservedCases").asInt()).isZero();
        assertThat(management.path("quality").asDouble()).isZero();
        assertThat(management.path("observedTokens").isNull()).isTrue();
        assertThat(management.path("estimatedObservedDollars").isNull()).isTrue();
        assertThat(management.path("costStatus").asString()).isEqualTo("unavailable");
        assertThat(management.path("complete").asBoolean()).isFalse();
    }

    @Test void changedRequestOracleDeadlineOrMissingReviewCannotProduceSummary() throws Exception {
        for (String change : List.of("request", "oracle", "deadline", "review", "source", "specification")) {
            Path area = directory.resolve(change);
            fixture(area);
            var row = (ObjectNode) JSON.readTree(Files.readString(area.resolve("attempts.jsonl")));
            if (change.equals("request")) ((ObjectNode) row.path("request")).put("prompt", "changed synthetic request");
            if (change.equals("oracle")) ((ObjectNode) row.path("oracle")).put("partySize", 2);
            if (change.equals("deadline")) ((ObjectNode) row.path("runtime").path("budget")).put("deadline", "2026-10-14T00:11:00Z");
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
            assertThatThrownBy(() -> RepresentativeFlowEvidence.recalculate(area)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("quality/evidence incomplete");
            assertThat(Files.exists(area.resolve("summary.json"))).isFalse();
        }
    }

    static void fixture(Path area) throws Exception {
        Files.createDirectories(area);
        byte[] specBytes = RepresentativeFlowEvidence.resource("/representative-flows/evidence-v5.json");
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
        Files.write(area.resolve("manifest.json"), JSON.writeValueAsBytes(Map.of(
            "sourceDigests", sources,
            "specification", spec, "specificationSha256", specDigest, "reviewPolicySha256", policyDigest,
            "schemaSha256", RepresentativeFlowEvidence.digest(ProviderProtocol.SCHEMA_JSON.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "profileSha256", RepresentativeFlowEvidence.digest(RepresentativeFlowEvidence.resource("/ai/representative-router-v1.json")),
            "contextRevision", RepresentativeContext.REVISION,
            "generationConfig", Map.of("temperature", 1, "candidateCount", 1, "maxOutputTokens", 1024,
                "responseMimeType", "application/json", "apiMode", GeminiAdapter.API_MODE),
            "httpControls", Map.of("version", "HTTP_1_1", "disableRetryConnect", true, "enableAllMethodRetry", false,
                "retryLimit", "1", "redirects", "NEVER"))));
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
        row.put("requestSchemaSha256", RepresentativeFlowEvidence.digest(JSON.writeValueAsBytes(ProviderProtocol.schema(request))));
        row.put("plan", plan); row.put("before", before); row.put("runtime", after);
        row.put("result", new GeminiAdapter.Result(null, GeminiAdapter.Failure.TEMPORARY_OVERLOAD, 1, 10,
            new GeminiAdapter.Usage(null, null, null, null, "unavailable"), "unavailable", null, 503, "terminal_unavailable"));
        row.put("rawStructuredResponse", Map.of()); row.put("runDurationMillis", 10); row.put("approvalWaitMillis", 0);
        row.put("oracle", Map.of("disposition", "ANSWER", "target", "", "partySize", 0, "outcome", "NOT_APPLICABLE",
            "sources", testCase.path("requiredSources"), "required", testCase.path("requiredClaims"),
            "allowedSources", request.knownSources(), "allowed", request.knownClaims()));
        Files.writeString(area.resolve("attempts.jsonl"), JSON.writeValueAsString(row));
        Files.write(area.resolve("content-review.json"), JSON.writeValueAsBytes(Map.of(
            "revision", "m7-content-review-v1", "specificationSha256", specDigest, "reviewPolicySha256", policyDigest,
            "entries", List.of(Map.of("caseId", "management-owner", "model", "gemini-3.5-flash-lite", "repeat", 1,
                "requestSha256", requestDigest, "answerSha256", RepresentativeFlowEvidence.digest(new byte[0]),
                "findings", List.of(), "reasonCode", "deterministic_validator_fixture")))));
    }
}
