package com.slotq.events.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Recomputes the three crash-window decisions from their durable raw snapshots. */
@EnabledIfSystemProperty(named = "slotq.kafka.evidence.dir", matches = ".+")
class KafkaIntakeCrashEvidenceIntegrityTests {
    @Test
    void brokerOffsetNeverOutrunsDurableIntakeAndPendingTargetRunsWithoutRedelivery() throws Exception {
        Path folder = Path.of(System.getProperty("slotq.kafka.evidence.dir"));
        byte[] bytes = Files.readAllBytes(folder.resolve("intake-crash-raw.json"));
        JsonMapper json = new JsonMapper();
        Map<String, Object> raw = json.readValue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
            new TypeReference<>() { });
        assertThat(raw.get("schemaVersion")).isEqualTo("slotq-kafka-intake-crash/v2");
        assertThat(((Number) raw.get("seed")).intValue()).isEqualTo(109);
        List<Map<String, Object>> cases = list(raw.get("faultCases"));
        assertThat(cases.stream().map(value -> value.get("fault")))
            .containsExactly("BEFORE_INTAKE", "AFTER_INTAKE", "AFTER_OFFSET");
        for (int index = 0; index < cases.size(); index++) {
            Map<String, Object> fault = cases.get(index);
            Map<String, Object> before = map(fault.get("before"));
            Map<String, Object> during = map(fault.get("during"));
            Map<String, Object> entrypoint = map(fault.get("entrypoint"));
            Map<String, Object> end = map(fault.get("finalConvergence"));
            assertThat(entrypoint.get("stage")).isEqualTo(fault.get("fault"));
            assertThat(((Number) entrypoint.get("pid")).longValue()).isPositive();
            assertThat(list(before.get("original"))).hasSize(1);
            assertThat(list(during.get("original"))).hasSize(1);
            assertThat(list(during.get("publication"))).isEmpty(); // test coordinator sent canonical wire
            assertThat(list(during.get("targets"))).hasSize(index == 0 ? 0 : 1);
            assertThat(list(during.get("receipt"))).isEmpty();
            assertThat(((Number) during.get("brokerCommittedNext")).longValue())
                .isEqualTo(index == 2 ? ((Number) raw.get("offset")).longValue() + 1 : -1);
            if (index > 0) {
                assertThat(list(during.get("intake"))).hasSize(1);
                Map<String, Object> target = KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
                    during.get("targets")).getFirst();
                assertThat(target.get("state")).isEqualTo("PENDING");
                assertThat(((Number) target.get("cycleAttempts")).intValue()).isZero();
            }
            assertThat(KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
                end.get("targets")).getFirst().get("state")).isEqualTo("DONE");
            assertThat(list(end.get("receipt"))).hasSize(1);
        }
        Map<String, Object> postOffset = map(cases.get(2).get("during"));
        assertThat(KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
            postOffset.get("targets")).getFirst().get("state")).isEqualTo("PENDING");
        assertThat(((Number) map(raw.get("target")).get("cycle_attempts")).intValue()).isEqualTo(1);
        assertThat(map(raw.get("receipt")).get("outcome")).isEqualTo("NO_CANDIDATE");
        assertThat(list(raw.get("quarantine"))).hasSize(3);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "slotq-kafka-intake-recalculation/v1");
        result.put("rawSha256", java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("verifiedCrashWindows", cases.stream().map(value -> value.get("fault")).toList());
        result.put("postOffsetPendingAttempts", 0);
        result.put("finalTargetCount", 1);
        result.put("finalReceiptCount", 1);
        result.put("quarantineCount", list(raw.get("quarantine")).size());
        Files.writeString(folder.resolve("intake-crash-recalculated.json"), json.writeValueAsString(result));
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> list(Object value) { return (List<T>) value; }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
