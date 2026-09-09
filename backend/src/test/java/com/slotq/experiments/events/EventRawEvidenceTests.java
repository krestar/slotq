package com.slotq.experiments.events;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EventRawEvidenceTests {
    @Test
    void cleanEvidenceHasAllFaultsAndSummaryRecalculatesFromDatabaseObservations() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("docs"))) root = root.getParent();
        var json = new ObjectMapper();
        JsonNode report = json.readTree(Files.readString(root.resolve("docs/experiments/events/clean/report.json")));
        assertThat(report.path("schemaVersion").asText()).isEqualTo("slotq-events/v1");
        var environment = report.path("environment");
        assertThat(environment.path("dirty").asBoolean(true)).isFalse();
        assertThat(environment.path("applicationRevision").asText()).matches("[a-f0-9]{40}");
        assertThat(environment.path("javaVersion").asText()).startsWith("25.");
        assertThat(environment.path("mysqlVersion").asText()).startsWith("8.4.");
        assertThat(environment.path("isolation").asText()).isEqualTo("REPEATABLE-READ");
        assertThat(environment.path("poolSize").asInt()).isEqualTo(10);
        assertThat(report.path("rows").size()).isEqualTo(45);
        Map<String, JsonNode> fixtures = new HashMap<>();
        for (var row : report.path("rows")) {
            String boundary = row.path("boundary").asText();
            String fault = row.path("fault").asText();
            String key = fault + row.path("repetition").asText();
            if (fixtures.containsKey(key)) assertThat(row.path("event")).isEqualTo(fixtures.get(key));
            else fixtures.put(key, row.path("event"));
            int business = fault.equals("BEFORE_COMMIT")
                || (boundary.equals("SYNCHRONOUS") && fault.equals("HANDLER_FAILURE")) ? 0 : 1;
            int effects = business == 0 || (boundary.equals("DIRECT")
                && (fault.equals("AFTER_COMMIT") || fault.equals("HANDLER_FAILURE"))) ? 0 : 1;
            assertThat(row.path("afterRecovery").path("business").asInt()).isEqualTo(business);
            assertThat(row.path("afterRecovery").path("durable").asInt())
                .isEqualTo(boundary.equals("DURABLE") ? business : 0);
            assertThat(row.path("afterRecovery").path("effects").asInt()).isEqualTo(effects);
            if (fault.equals("AFTER_COMMIT")) assertThat(row.path("exitCode").asInt()).isEqualTo(80);
        }
        assertThat(report.path("summary")).isEqualTo(json.valueToTree(EventEvidence.summarize(report.path("rows"))));
        Map<String, JsonNode> probes = new HashMap<>();
        for (var probe : report.path("probes")) {
            assertThat(probes.put(probe.path("scenario").asText(), probe)).isNull();
            assertThat(probe.path("outcome").asText()).isNotEqualTo("UNEXPECTED_SUCCESS").isNotEqualTo("ERROR");
            assertThat(probe.path("snapshot").path("effects").asInt()).isBetween(0, 1);
        }
        for (var boundary : EventFixture.Boundary.values()) {
            for (String suffix : new String[]{"SEQUENTIAL_DUPLICATE", "CONCURRENT_DUPLICATE", "DELAYED_DUPLICATE", "REVERSED"}) {
                var probe = probes.get(boundary + "_" + suffix);
                assertThat(probe).isNotNull();
                assertThat(probe.path("snapshot").path("effects").asInt()).isEqualTo(1);
                assertThat(probe.path("effects").size()).isEqualTo(1);
                if (suffix.equals("REVERSED")) assertThat(probe.path("projection").get(0).path("sequence_no").asInt()).isEqualTo(2);
            }
            var forged = probes.get(boundary + "_FORGED_TENANT");
            assertThat(forged.path("outcome").asText()).isEqualTo("TENANT_MISMATCH");
            assertThat(forged.path("effects").get(0).path("tenant_id").asText())
                .isNotEqualTo(forged.path("event").path("tenantId").asText());
            assertThat(probes.get(boundary + "_IDENTITY_CORRUPTION").path("outcome").asText()).isEqualTo("IDENTITY_CORRUPTION");
        }
        for (String name : new String[]{"OUTER_ROLLBACK", "INVALID_JSON", "APPEND_WITHOUT_TRANSACTION"}) {
            var snapshot = probes.get(name).path("snapshot");
            assertThat(snapshot.path("business").asInt()).isZero();
            assertThat(snapshot.path("durable").asInt()).isZero();
        }
        for (String name : new String[]{"UNKNOWN_TYPE", "UNKNOWN_VERSION", "PAYLOAD_INVARIANT"}) {
            var probe = probes.get(name + "_ATTEMPT_1");
            assertThat(probe.path("outcome").asText()).isEqualTo(name);
            assertThat(probe.path("snapshot").path("effects").asInt()).isZero();
            assertThat(probe.path("delivery").get(0).path("state").asText()).isEqualTo("DEAD");
        }
        assertState(probes, "TRANSIENT_ATTEMPT_1", "PENDING", 1, 0);
        assertState(probes, "TRANSIENT_ATTEMPT_2", "DONE", 2, 1);
        assertState(probes, "EXHAUSTED_NO_AUTO_RETRY", "DEAD", 3, 0);
        assertState(probes, "AFTER_CLAIM_EXHAUSTED", "DEAD", 3, 0);
        assertState(probes, "REPLAY_RECOVER", "DONE", 1, 1);
        assertThat(probes.get("REPLAY_RECOVER").path("delivery").get(0).path("lifetime_attempts").asInt()).isEqualTo(4);
        assertThat(probes.get("REPLAY_AUDIT").path("audit").size()).isEqualTo(1);
        assertThat(probes.get("REPLAY_UNTRUSTED").path("audit").size()).isZero();
        assertThat(probes.get("REPLAY_CROSS_TENANT").path("audit").size()).isZero();
        for (String fault : new String[]{"AFTER_CLAIM", "AFTER_EFFECT"}) {
            assertThat(probes.get(fault + "_STALE_OWNER").path("outcome").asText()).isEqualTo("STALE");
            assertState(probes, fault + "_RECOVER", "DONE", 2, 1);
        }
    }

    private static void assertState(Map<String, JsonNode> probes, String name, String state, int attempts, int effects) {
        var row = probes.get(name);
        assertThat(row).isNotNull();
        assertThat(row.path("delivery").get(0).path("state").asText()).isEqualTo(state);
        assertThat(row.path("delivery").get(0).path("attempts").asInt()).isEqualTo(attempts);
        assertThat(row.path("snapshot").path("effects").asInt()).isEqualTo(effects);
    }
}
