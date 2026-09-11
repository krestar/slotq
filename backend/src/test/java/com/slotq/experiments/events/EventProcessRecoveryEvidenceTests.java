package com.slotq.experiments.events;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EventProcessRecoveryEvidenceTests {
    @Test
    void cleanProcessRecoveryEvidenceCoversTheWp3FaultMatrixFromAuthoritativeDatabaseState() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("docs"))) root = root.getParent();
        Path events = root.resolve("docs/experiments/events");
        java.util.List<Path> reports;
        try (var paths = Files.walk(events)) {
            reports = paths.filter(path -> path.endsWith("raw/report.json"))
                .filter(path -> {
                    try {
                        return Files.readString(path).contains("slotq-event-process-recovery/v1");
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }).toList();
        }
        assertThat(reports).as("one checked-in WP3 process recovery report").hasSize(1);

        JsonNode report = new ObjectMapper().readTree(Files.readString(reports.getFirst()));
        assertThat(report.path("environment").path("dirty").asBoolean(true)).isFalse();
        assertThat(report.path("environment").path("applicationRevision").asText()).matches("[a-f0-9]{40}");
        assertThat(report.path("environment").path("javaVersion").asText()).startsWith("25.");
        assertThat(report.path("environment").path("mysqlVersion").asText()).startsWith("8.4.");
        assertThat(report.path("environment").path("transactionIsolation").asText()).isEqualTo("REPEATABLE-READ");
        assertThat(report.path("environment").path("testDeliveryPolicy").path("productionSlo").asBoolean()).isFalse();
        assertThat(report.path("summary").path("wp3ReliabilityGate").asText()).isEqualTo("PASS");
        assertThat(report.path("summary").path("failed").asInt()).isZero();

        Map<String, JsonNode> cases = new HashMap<>();
        for (JsonNode row : report.path("cases")) {
            assertThat(cases.put(row.path("name").asText(), row)).isNull();
            assertThat(row.path("outcome").asText()).isEqualTo("PASS");
            assertThat(row.path("firstAuthoritativeState").isObject()).isTrue();
            assertThat(row.path("recoveryAuthoritativeState").isObject()).isTrue();
        }
        assertThat(cases).containsOnlyKeys(
            "MATERIALIZATION_BEFORE_PROCESS_EXIT", "CLAIM_COMMIT_THEN_PROCESS_EXIT",
            "EFFECT_TRANSACTION_PROCESS_EXIT", "EFFECT_AND_DONE_COMMIT_OUTCOME_UNKNOWN",
            "STALE_OWNER_AFTER_NEW_OWNER_DONE", "CLAIM_CRASH_EXHAUSTION",
            "TRUSTED_INTERNAL_REPLAY_AFTER_CRASH_EXHAUSTION", "DATABASE_UNAVAILABLE_AND_RECOVERY",
            "BACKLOG_LARGER_THAN_BATCH_RESTART_DRAIN");

        assertAtomic(cases.get("EFFECT_TRANSACTION_PROCESS_EXIT").path("firstAuthoritativeState"),
            "PROCESSING", 0, 0);
        assertAtomic(cases.get("EFFECT_AND_DONE_COMMIT_OUTCOME_UNKNOWN").path("firstAuthoritativeState"),
            "DONE", 1, 1);
        JsonNode stale = cases.get("STALE_OWNER_AFTER_NEW_OWNER_DONE").path("recoveryAuthoritativeState");
        assertThat(delivery(stale).path("state").asText()).isEqualTo("DONE");
        assertThat(delivery(stale).path("fencingToken").asLong()).isEqualTo(2);
        assertThat(stale.path("effects")).hasSize(1);
        assertThat(stale.path("receipts")).hasSize(1);

        JsonNode dead = cases.get("CLAIM_CRASH_EXHAUSTION").path("firstAuthoritativeState");
        assertThat(delivery(dead).path("state").asText()).isEqualTo("DEAD");
        assertThat(delivery(dead).path("cycleAttempts").asInt()).isEqualTo(3);
        assertThat(delivery(dead).path("failureCode").asText()).isEqualTo("CRASH_EXHAUSTED");
        JsonNode replay = cases.get("TRUSTED_INTERNAL_REPLAY_AFTER_CRASH_EXHAUSTION")
            .path("recoveryAuthoritativeState");
        assertThat(delivery(replay).path("state").asText()).isEqualTo("DONE");
        assertThat(delivery(replay).path("lifetimeAttempts").asLong()).isEqualTo(4);
        assertThat(delivery(replay).path("fencingToken").asLong()).isEqualTo(5);
        assertThat(replay.path("replayAudit")).hasSize(1);

        JsonNode backlog = cases.get("BACKLOG_LARGER_THAN_BATCH_RESTART_DRAIN")
            .path("recoveryAuthoritativeState");
        assertThat(backlog.path("deliveries")).hasSize(8).allSatisfy(row ->
            assertThat(row.path("state").asText()).isEqualTo("DONE"));
        assertThat(backlog.path("effects")).hasSize(8);
        assertThat(backlog.path("receipts")).hasSize(8);
        assertThat(backlog.path("discoveryCursor").asLong()).isEqualTo(backlog.path("eventBoundary").asLong());
    }

    private static void assertAtomic(JsonNode snapshot, String state, int effects, int receipts) {
        assertThat(delivery(snapshot).path("state").asText()).isEqualTo(state);
        assertThat(snapshot.path("effects")).hasSize(effects);
        assertThat(snapshot.path("receipts")).hasSize(receipts);
    }

    private static JsonNode delivery(JsonNode snapshot) {
        assertThat(snapshot.path("deliveries")).hasSize(1);
        return snapshot.path("deliveries").get(0);
    }
}
