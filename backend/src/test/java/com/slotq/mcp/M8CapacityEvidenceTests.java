package com.slotq.mcp;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.slotq.mcp.M8CapacityEvidence.*;
import static org.mockito.Mockito.*;

class M8CapacityEvidenceTests {
    @TempDir Path root;

    @ParameterizedTest @ValueSource(strings = {"", "{}", "{\"error\":{\"code\":-32603}}", "{\"result\":{\"isError\":false}}"})
    void emptyOrIncompleteMcpEnvelopeNeverCountsAsSuccess(String body) {
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        assertThat(M8CapacityRunner.classify(response, true).outcome()).isEqualTo("UNKNOWN");
    }

    @Test void scheduledDenominatorIncludesDropsUnknownsAndLateCompletions() {
        var raw = raw(List.of(row(1, "SUCCESS", 2), row(2, "UNKNOWN", 2_000_000_002L), row(3, "GENERATOR_REJECTED", 4)));
        var report = JSON.valueToTree(summarize(JSON.valueToTree(raw)));
        var phase = report.path("phases").get(0);
        assertThat(phase.path("recorded").asInt()).isEqualTo(3);
        assertThat(phase.path("generatorDispatched").asInt()).isEqualTo(2);
        assertThat(phase.path("outcomes").path("UNKNOWN").asInt()).isEqualTo(1);
        assertThat(phase.path("cohortSuccessfulPerScheduledSecond").asDouble()).isEqualTo(1);
        assertThat(phase.path("successfulCompletionsInWindowPerSecond").asDouble()).isEqualTo(1);
        assertThat(report.path("verdict").asString()).isEqualTo("INCOMPLETE");
    }

    @Test void completionAfterWindowDoesNotInflateThroughput() {
        var report = JSON.valueToTree(summarize(JSON.valueToTree(raw(List.of(row(1, "SUCCESS", 2_000_000_002L))))));
        assertThat(report.path("phases").get(0).path("successfulCompletionsInWindowPerSecond").asDouble()).isZero();
    }

    @Test void missingOrDuplicateArrivalsAreIncomplete() {
        for (var rows : List.of(List.of(row(1, "SUCCESS", 2)), List.of(row(1, "SUCCESS", 2), row(1, "SUCCESS", 2), row(3, "SUCCESS", 4)))) {
            var report = JSON.valueToTree(summarize(JSON.valueToTree(raw(rows))));
            assertThat(report.path("phases").get(0).path("arrivalCoverage").asString()).isEqualTo("INCOMPLETE");
            assertThat(report.path("verdict").asString()).isEqualTo("INCOMPLETE");
        }
    }

    @Test void aggregateOracleViolationCannotBeHiddenByMissingObservations() {
        var raw = raw(List.of()); raw.put("oracle", Map.of("violations", 1));
        assertThat(summarize(JSON.valueToTree(raw)).get("verdict")).isEqualTo("FAIL");
    }

    @Test void frozenDiagnosticThresholdFailureRemainsFailWithoutPromotingSupport() {
        var raw = raw(List.of(row(1, "UNKNOWN", 2), row(2, "SUCCESS", 3), row(3, "SUCCESS", 4)));
        raw.put("manifest", Map.of("stage", "verification", "criteria", Map.of("values", Map.of("state", "FROZEN", "metrics", metrics()))));
        var report = summarize(JSON.valueToTree(raw));
        assertThat(report.get("diagnosticVerdict")).isEqualTo("FAIL");
        assertThat(report.get("supportQualification")).isEqualTo("INCOMPLETE");
        assertThat(report.get("verdict")).isEqualTo("FAIL");
    }

    @Test void rawAndSavedReportMustBothMatchOnOfflineRecalculation() throws Exception {
        var raw = raw(List.of(row(1, "SUCCESS", 2)));
        write(root.resolve("raw.json"), raw);
        write(root.resolve("summary.json"), summarize(JSON.valueToTree(raw)));
        System.setProperty("slotq.capacity.input", root.toString());
        System.setProperty("slotq.capacity.digest", digest(root.resolve("raw.json")));
        try {
            M8CapacityEvidence.main(new String[0]);
            Files.writeString(root.resolve("raw.json"), "{}");
            assertThatThrownBy(() -> M8CapacityEvidence.main(new String[0])).hasMessageContaining("digest");
            System.setProperty("slotq.capacity.digest", digest(root.resolve("raw.json")));
            assertThatThrownBy(() -> M8CapacityEvidence.main(new String[0])).hasMessageContaining("Summary differs");
        } finally {
            System.clearProperty("slotq.capacity.input"); System.clearProperty("slotq.capacity.digest");
        }
    }

    @Test void frozenCriteriaRequireActualPriorPilotAndMatchingSupport() throws Exception {
        var frozen = frozen();
        write(root.resolve("criteria.json"), frozen);
        assertThat(validateCriteria(root.resolve("criteria.json"), 4, 16, Instant.parse("2026-01-03T00:00:00Z")).path("state").asString()).isEqualTo("FROZEN");
        assertThatThrownBy(() -> validateCriteria(root.resolve("criteria.json"), 3, 16, Instant.parse("2026-01-03T00:00:00Z"))).hasMessageContaining("support mismatch");
        assertThatThrownBy(() -> validateCriteria(root.resolve("criteria.json"), 4, 16, Instant.parse("2026-01-01T00:00:00Z"))).hasMessageContaining("Future");
        Files.writeString(root.resolve("pilot.json"), "{}");
        assertThatThrownBy(() -> validateCriteria(root.resolve("criteria.json"), 4, 16, Instant.parse("2026-01-03T00:00:00Z"))).hasMessageContaining("digest");
    }

    @ParameterizedTest @ValueSource(strings = {"metrics", "reason", "repeats", "state"})
    void incompleteCriteriaBlockVerification(String field) throws Exception {
        var frozen = frozen(); frozen.remove(field);
        write(root.resolve("criteria.json"), frozen);
        assertThatThrownBy(() -> validateCriteria(root.resolve("criteria.json"), 4, 16, Instant.parse("2026-01-03T00:00:00Z"))).isInstanceOf(IllegalArgumentException.class);
    }

    private Map<String, Object> frozen() throws Exception {
        write(root.resolve("pilot.json"), Map.of("endedAt", "2026-01-01T00:00:00Z", "manifest", Map.of("stage", "pilot",
            "settings", Map.of("spring.datasource.hikari.maximum-pool-size", 4), "data", Map.of("documents", 16))));
        return new LinkedHashMap<>(Map.of("schemaVersion", "slotq-m8-capacity-criteria/v1", "state", "FROZEN",
            "frozenAt", "2026-01-02T00:00:00Z", "pilotPath", root.resolve("pilot.json").toString(),
            "pilotDigest", digest(root.resolve("pilot.json")), "pool", 4, "size", 16, "repeats", 3, "reason", "pilot rationale", "metrics", metrics()));
    }

    private static Map<String, Object> metrics() {
        return Map.of("distributedP95Ms", 2000, "distributedDispatchP99Ms", 100, "unexpectedFraction", .02, "recoveryP95Ms", 1000, "recoveryBoundMs", 10000);
    }

    private static Map<String, Object> raw(List<Map<String, Object>> rows) {
        var raw = new LinkedHashMap<String, Object>();
        raw.put("manifest", Map.of("stage", "pilot"));
        raw.put("phases", List.of(Map.of("name", "distributed", "planned", 3, "rate", 3, "seconds", 1, "startNanos", 1)));
        raw.put("requests", rows); raw.put("oracle", Map.of("violations", 0)); return raw;
    }

    private static Map<String, Object> row(long scheduled, String outcome, long end) {
        return Map.of("phase", "distributed", "operation", "product-hold", "scheduledNanos", scheduled, "endedNanos", end,
            "responded", !Set.of("UNKNOWN", "GENERATOR_REJECTED").contains(outcome), "outcome", outcome,
            "scheduledLatencyMs", (end - scheduled) / 1e6, "dispatchLagMs", 1);
    }
}
