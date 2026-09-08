package com.slotq.experiments.concurrency;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ConcurrencyBaselineRawEvidenceTests {

    private static final String REVISION = "e9faf83e091ab63d8f833bedd990355288054662";
    private static final String OPTIMISTIC_RUN_ID = "3a9e8a98-a105-4734-a91d-1617c26ead2c";
    private static final String PESSIMISTIC_RUN_ID = "4c571add-7867-4326-8cd5-60a899fda418";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void committedRunsAreComparableAndEverySummaryMetricCanBeRecalculated() throws IOException {
        JsonNode optimistic = readEvidence(OPTIMISTIC_RUN_ID);
        JsonNode pessimistic = readEvidence(PESSIMISTIC_RUN_ID);

        assertRun(
            optimistic,
            OPTIMISTIC_RUN_ID,
            "OPTIMISTIC_SLOT_VERSION_BOUNDED_RETRY"
        );
        assertRun(pessimistic, PESSIMISTIC_RUN_ID, "PESSIMISTIC_WRITE_SLOT");
        assertComparableEnvironment(optimistic, pessimistic);
    }

    private JsonNode readEvidence(String runId) throws IOException {
        Path path = repositoryRoot().resolve(Path.of(
            "docs", "experiments", "concurrency", runId, "raw", "report.json"
        ));
        assertThat(path).isRegularFile();
        return objectMapper.readTree(Files.readString(path));
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("README.md"))
                && Files.isRegularFile(candidate.resolve("backend/build.gradle"))
                && Files.isDirectory(candidate.resolve("docs/experiments"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("SlotQ repository root was not found");
    }

    private static void assertRun(JsonNode report, String runId, String strategy) {
        assertThat(report.path("schemaVersion").asText())
            .isEqualTo("slotq-concurrency-baseline/v3");
        assertThat(report.path("runId").asText()).isEqualTo(runId);
        assertThat(report.path("recordedAt").asText()).isNotBlank();
        assertThat(report.path("verificationNow").asText()).isNotBlank();

        JsonNode environment = report.path("environment");
        assertThat(environment.path("applicationRevision").asText()).isEqualTo(REVISION);
        assertThat(environment.path("branch").asText()).isEqualTo("fix/m2-closure-audit");
        assertThat(environment.path("dirty").asBoolean()).isFalse();
        assertThat(environment.path("databaseImage").asText()).isEqualTo("mysql:8.4");
        assertThat(environment.path("databaseVersion").asText()).isEqualTo("8.4.11");
        assertThat(environment.path("transactionIsolation").asText())
            .isEqualTo("REPEATABLE-READ");
        assertThat(environment.path("schemaVersion").asText()).isEqualTo("8");
        assertThat(environment.path("connectionPool").path("maximumPoolSize").asInt())
            .isEqualTo(10);
        assertThat(environment.path("connectionPool").path("connectionTimeoutMs").asLong())
            .isEqualTo(30_000L);
        assertThat(environment.path("cpuModel").asText()).isNotBlank();
        assertThat(environment.path("availableProcessors").asInt()).isPositive();
        assertThat(environment.path("totalPhysicalMemoryBytes").asLong()).isPositive();
        assertThat(environment.path("storageType").asText())
            .contains("NVMe", "WD PC SN810");
        assertThat(environment.path("jvmOptions").isArray()).isTrue();
        assertThat(environment.path("toolingVersion").asText())
            .isEqualTo("ConcurrencyBaselineRunner slotq-concurrency-baseline/v3");
        assertThat(environment.path("containerLimits").asText()).isNotBlank();
        assertThat(environment.path("networkCondition").asText()).isNotBlank();

        JsonNode workload = report.path("workload");
        assertThat(workload.path("clients").asInt()).isEqualTo(10);
        assertThat(workload.path("iterations").asInt()).isEqualTo(5);
        assertThat(workload.path("seed").asLong()).isEqualTo(15_001L);
        assertThat(workload.path("partySize").asInt()).isEqualTo(2);
        assertThat(workload.path("holdDuration").asText()).isEqualTo("PT5M");
        assertThat(workload.path("timeout").asText()).isEqualTo("PT10S");

        JsonNode product = report.path("productModel");
        assertThat(product.path("resourceModel").asText()).isEqualTo("TABLE_X_SLOT");
        assertThat(product.path("slotCapacity").asInt()).isEqualTo(1);
        assertThat(product.path("allocationUnit").asInt()).isEqualTo(1);
        assertThat(product.path("concurrencyStrategy").asText()).isEqualTo(strategy);

        assertMetricsDeriveFromRawEvidence(report);
    }

    private static void assertComparableEnvironment(JsonNode first, JsonNode second) {
        JsonNode firstEnvironment = first.path("environment");
        JsonNode secondEnvironment = second.path("environment");
        for (String field : List.of(
            "applicationRevision", "branch", "dirty", "databaseImage", "databaseVersion",
            "transactionIsolation", "schemaVersion", "connectionPool", "javaVersion",
            "springBootVersion", "gradleVersion", "operatingSystem", "architecture",
            "cpuModel", "availableProcessors", "totalPhysicalMemoryBytes", "storageType",
            "toolingVersion", "containerLimits", "networkCondition"
        )) {
            assertThat(secondEnvironment.path(field))
                .as("environment.%s", field)
                .isEqualTo(firstEnvironment.path(field));
        }
        assertThat(second.path("workload")).isEqualTo(first.path("workload"));
        for (String field : List.of(
            "resourceModel", "slotCapacity", "allocationUnit", "partySizeRole"
        )) {
            assertThat(second.path("productModel").path(field))
                .as("productModel.%s", field)
                .isEqualTo(first.path("productModel").path(field));
        }
        assertThat(second.path("productModel").path("concurrencyStrategy").asText())
            .isNotEqualTo(first.path("productModel").path("concurrencyStrategy").asText());
    }

    private static void assertMetricsDeriveFromRawEvidence(JsonNode report) {
        List<JsonNode> requests = elements(report.path("requests"));
        List<JsonNode> slots = elements(report.path("slotObservations"));
        JsonNode metrics = report.path("metrics");

        assertThat(metrics.path("totalRequests").asInt()).isEqualTo(requests.size());
        assertThat(metrics.path("successfulHoldResultCount").asLong())
            .isEqualTo(countOutcome(requests, "SUCCESS"));
        assertThat(metrics.path("businessConflictCount").asLong())
            .isEqualTo(countOutcome(requests, "BUSINESS_CONFLICT"));
        assertThat(metrics.path("systemFailureCount").asLong())
            .isEqualTo(countOutcome(requests, "SYSTEM_FAILURE"));
        assertThat(metrics.path("timeoutCount").asLong())
            .isEqualTo(countOutcome(requests, "TIMEOUT"));
        assertThat(requests.stream()
            .filter(request -> "SUCCESS".equals(request.path("outcome").asText())))
            .allSatisfy(request -> assertThat(request.path("httpStatus").asInt()).isEqualTo(201));
        assertThat(requests.stream()
            .filter(request -> "BUSINESS_CONFLICT".equals(request.path("outcome").asText())))
            .allSatisfy(request -> {
                assertThat(request.path("httpStatus").asInt()).isEqualTo(409);
                assertThat(request.path("businessCode").asText())
                    .isEqualTo("CAPACITY_UNAVAILABLE");
            });

        List<Double> latencies = requests.stream()
            .map(request -> request.path("latencyMs").asDouble())
            .sorted(Comparator.naturalOrder())
            .toList();
        assertClose(metrics.path("p50LatencyMs").asDouble(), percentile(latencies, 0.50));
        assertClose(metrics.path("p95LatencyMs").asDouble(), percentile(latencies, 0.95));
        assertClose(metrics.path("p99LatencyMs").asDouble(), percentile(latencies, 0.99));

        Map<Integer, IterationBounds> bounds = new HashMap<>();
        for (JsonNode request : requests) {
            bounds.computeIfAbsent(
                request.path("iteration").asInt(), ignored -> new IterationBounds()
            ).include(
                request.path("startedNanos").asLong(),
                request.path("endedNanos").asLong()
            );
        }
        long elapsedNanos = bounds.values().stream()
            .mapToLong(IterationBounds::elapsedNanos)
            .sum();
        double elapsedMs = elapsedNanos / 1_000_000.0;
        assertClose(metrics.path("elapsedMs").asDouble(), elapsedMs);
        assertClose(
            metrics.path("throughputRequestsPerSecond").asDouble(),
            requests.size() / (elapsedNanos / 1_000_000_000.0)
        );
        assertClose(
            metrics.path("maxBarrierReleaseStartSpreadMs").asDouble(),
            bounds.values().stream().mapToDouble(IterationBounds::startSpreadMs).max().orElse(0.0)
        );

        assertThat(metrics.path("effectiveOccupancy").asInt())
            .isEqualTo(slots.stream()
                .mapToInt(slot -> slot.path("effectiveOccupancy").asInt()).sum());
        assertThat(metrics.path("rawActiveAllocationRows").asInt())
            .isEqualTo(slots.stream()
                .mapToInt(slot -> slot.path("rawActiveAllocationRows").asInt()).sum());
        assertThat(metrics.path("invariantViolationCount").asInt())
            .isEqualTo((int) slots.stream()
                .filter(slot -> slot.path("invariantViolation").asBoolean()).count());
        assertThat(metrics.path("partialCommitCount").asInt())
            .isEqualTo((int) slots.stream()
                .filter(slot -> slot.path("partialCommit").asBoolean()).count());
        assertThat(slots).allSatisfy(slot -> assertThat(slot.path("verificationNow").asText())
            .isEqualTo(report.path("verificationNow").asText()));

        assertThat(metrics.path("lockWaitCount").asLong())
            .isEqualTo(counterDelta(report, "databaseCounters", "lockWaits"));
        assertThat(metrics.path("lockWaitTimeMs").asLong())
            .isEqualTo(counterDelta(report, "databaseCounters", "lockWaitTimeMs"));
        assertThat(metrics.path("deadlockCount").asLong())
            .isEqualTo(counterDelta(report, "databaseCounters", "deadlocks"));
        assertThat(metrics.path("staleRetryCount").asLong())
            .isEqualTo(counterDelta(report, "strategyCounters", "staleRetries"));
        assertThat(metrics.path("staleRetryExhaustionCount").asLong())
            .isEqualTo(counterDelta(report, "strategyCounters", "staleRetryExhaustions"));
        assertThat(metrics.path("systemRetryCount").asLong())
            .isEqualTo(counterDelta(report, "strategyCounters", "systemRetries"));
        assertThat(metrics.path("systemRetryExhaustionCount").asLong())
            .isEqualTo(counterDelta(report, "strategyCounters", "systemRetryExhaustions"));
    }

    private static List<JsonNode> elements(JsonNode array) {
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return List.copyOf(elements);
    }

    private static long countOutcome(List<JsonNode> requests, String outcome) {
        return requests.stream()
            .filter(request -> outcome.equals(request.path("outcome").asText()))
            .count();
    }

    private static long counterDelta(JsonNode report, String prefix, String field) {
        long before = report.path(prefix + "Before").path(field).asLong();
        long after = report.path(prefix + "After").path(field).asLong();
        return Math.max(0L, after - before);
    }

    private static double percentile(List<Double> sortedValues, double percentile) {
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, index));
    }

    private static void assertClose(double actual, double expected) {
        assertThat(actual).isCloseTo(expected, within(0.000_001));
    }

    private static final class IterationBounds {

        private long firstStart = Long.MAX_VALUE;
        private long lastStart = Long.MIN_VALUE;
        private long lastEnd = Long.MIN_VALUE;

        void include(long startedNanos, long endedNanos) {
            firstStart = Math.min(firstStart, startedNanos);
            lastStart = Math.max(lastStart, startedNanos);
            lastEnd = Math.max(lastEnd, endedNanos);
        }

        long elapsedNanos() {
            return lastEnd - firstStart;
        }

        double startSpreadMs() {
            return (lastStart - firstStart) / 1_000_000.0;
        }
    }
}
