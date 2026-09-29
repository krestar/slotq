package com.slotq.events;

import java.nio.charset.StandardCharsets;
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

@EnabledIfSystemProperty(named = "slotq.kafka.evidence.dir", matches = ".+")
class KafkaCutoverFaultEvidenceIntegrityTests {
    @Test
    void oldDbOwnerCannotConsumeKafkaOrLaterRollbackEpoch() throws Exception {
        Path folder = Path.of(System.getProperty("slotq.kafka.evidence.dir"));
        byte[] bytes = Files.readAllBytes(folder.resolve("cutover-raw.json"));
        JsonMapper json = new JsonMapper();
        Map<String, Object> raw = json.readValue(new String(bytes, StandardCharsets.UTF_8),
            new TypeReference<>() { });
        assertThat(raw.get("schemaVersion")).isEqualTo("slotq-kafka-cutover-fault/v1");
        List<Map<String, Object>> timeline = list(raw.get("timeline"));
        assertThat(timeline.stream().map(row -> row.get("phase"))).containsExactly(
            "before-kafka-cutover", "kafka-authority-old-db-owner-rejected",
            "kafka-original-without-db-direct-target", "rollback-epoch-three-old-owner-rejected");
        assertState(timeline.get(0), "DB_DIRECT", 1, 1, 2);
        assertState(timeline.get(1), "KAFKA", 2, 1, 2);
        assertState(timeline.get(2), "KAFKA", 2, 2, 2);
        assertState(timeline.get(3), "DB_DIRECT", 3, 2, 4);
        for (Map<String, Object> phase : timeline) {
            assertThat(list(phase.get("receipts"))).isEmpty();
            for (Map<String, Object> delivery : KafkaCutoverFaultEvidenceIntegrityTests.<Map<String, Object>>list(
                phase.get("deliveries"))) {
                assertThat(((Number) delivery.get("cycleAttempts")).intValue()).isZero();
                assertThat(((Number) delivery.get("lifetimeAttempts")).intValue()).isZero();
                assertThat(((Number) delivery.get("fencingToken")).intValue()).isZero();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "slotq-kafka-cutover-recalculation/v1");
        result.put("rawSha256", java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("committedOriginals", list(raw.get("originals")).size());
        result.put("finalTargets", list(timeline.getLast().get("deliveries")).size());
        result.put("unauthorizedAttempts", 0);
        Files.writeString(folder.resolve("cutover-recalculated.json"), json.writeValueAsString(result));
    }

    private static void assertState(Map<String, Object> phase, String transport, int epoch,
        int originals, int deliveries) {
        List<Map<String, Object>> cutover = list(phase.get("cutover"));
        if (cutover.isEmpty()) {
            assertThat(transport).isEqualTo("DB_DIRECT");
            assertThat(epoch).isEqualTo(1);
        } else {
            assertThat(cutover.getFirst().get("transport")).isEqualTo(transport);
            assertThat(((Number) cutover.getFirst().get("authorityEpoch")).intValue()).isEqualTo(epoch);
        }
        assertThat(list(phase.get("originals"))).hasSize(originals);
        assertThat(list(phase.get("deliveries"))).hasSize(deliveries);
        assertThat(KafkaCutoverFaultEvidenceIntegrityTests.<Map<String, Object>>list(
            phase.get("assignments"))).allSatisfy(row -> {
            assertThat(row.get("transport")).isEqualTo(transport);
            assertThat(((Number) row.get("authorityEpoch")).intValue()).isEqualTo(epoch);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> list(Object value) { return (List<T>) value; }
}
