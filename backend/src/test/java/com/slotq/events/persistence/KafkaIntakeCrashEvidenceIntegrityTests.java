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
        assertThat(list(raw.get("quarantine"))).hasSize(raw.containsKey("quarantinePersistenceFailure") ? 4 : 3);

        if (raw.containsKey("relayAckCrash")) {
            Map<String, Object> relay = map(raw.get("relayAckCrash"));
            Map<String, Object> relayBefore = map(relay.get("before"));
            Map<String, Object> relayDuring = map(relay.get("during"));
            Map<String, Object> relayEnd = map(relay.get("finalConvergence"));
            Map<String, Object> ack = map(relay.get("entrypoint"));
            assertThat(ack.get("stage")).isEqualTo("ACK_AFTER_SEND_BEFORE_MARK");
            assertThat(((Number) relay.get("exit")).intValue()).isEqualTo(91);
            assertThat(KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
                relayBefore.get("publication")).getFirst().get("state")).isEqualTo("PENDING");
            assertThat(KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
                relayDuring.get("publication")).getFirst().get("state")).isEqualTo("PROCESSING");
            Map<String, Object> published = KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
                relayEnd.get("publication")).getFirst();
            assertThat(published.get("state")).isEqualTo("PUBLISHED");
            assertThat(((Number) ack.get("offset")).longValue())
                .isLessThan(((Number) published.get("ackOffset")).longValue());
            assertThat(list(relayEnd.get("intake"))).hasSize(3);
            assertThat(list(relayEnd.get("targetIntake"))).hasSize(1);
            assertThat(list(relayEnd.get("targets"))).hasSize(1);
            assertThat(list(relayEnd.get("receipt"))).hasSize(1);
            assertThat(((Number) KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
                relayEnd.get("targets")).getFirst().get("cycleAttempts")).intValue()).isEqualTo(1);
        }
        if (raw.containsKey("quarantinePersistenceFailure")) {
            Map<String, Object> fault = map(raw.get("quarantinePersistenceFailure"));
            assertThat(fault.get("entrypoint")).isEqualTo("JdbcKafkaIntakeStore.intake");
            assertThat(fault.get("mysqlPaused")).isEqualTo(true);
            assertThat(KafkaIntakeCrashEvidenceIntegrityTests.<String>list(fault.get("exceptionChain")))
                .anySatisfy(type -> assertThat(type).containsAnyOf("SQLException", "CommunicationsException"));
            long failedOffset = ((Number) fault.get("failedOffset")).longValue();
            Map<String, Object> beforeFailure = map(fault.get("before"));
            Map<String, Object> afterRecovery = map(fault.get("finalConvergence"));
            assertThat(((Number) beforeFailure.get("brokerCommittedNext")).longValue())
                .isEqualTo(failedOffset);
            assertThat(((Number) afterRecovery.get("brokerCommittedNext")).longValue())
                .isEqualTo(failedOffset + 1);
            assertThat(list(afterRecovery.get("targets"))).hasSize(1);
            assertThat(list(afterRecovery.get("receipt"))).hasSize(1);
        }
        if (raw.containsKey("deliveryFaultCases")) {
            List<Map<String, Object>> delivery = list(raw.get("deliveryFaultCases"));
            assertThat(delivery.stream().map(row -> row.get("fault"))).containsExactly(
                "WRONG_SCOPE", "CLAIM_HALT", "EFFECT_HALT", "COMMIT_UNKNOWN");
            int[] attempts = {0, 1, 2, 3};
            String[] states = {"PENDING", "PROCESSING", "PROCESSING", "DONE"};
            int[] exits = {0, 91, 92, 93};
            for (int index = 0; index < delivery.size(); index++) {
                Map<String, Object> caseEvidence = delivery.get(index);
                assertThat(map(caseEvidence.get("entrypoint")).get("stage"))
                    .isEqualTo(caseEvidence.get("fault"));
                assertThat(((Number) caseEvidence.get("exit")).intValue()).isEqualTo(exits[index]);
                Map<String, Object> during = map(caseEvidence.get("during"));
                assertThat(map(caseEvidence.get("entrypoint")).get("eventId"))
                    .isEqualTo(raw.get("deliveryEventId"));
                Map<String, Object> target = KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
                    during.get("targets")).getFirst();
                assertThat(target.get("state")).isEqualTo(states[index]);
                assertThat(((Number) target.get("cycleAttempts")).intValue()).isEqualTo(attempts[index]);
                assertThat(list(during.get("receipt"))).hasSize(index == 3 ? 1 : 0);
                assertThat(list(during.get("targetIntake"))).hasSize(1);
            }
            Map<String, Object> finalState = map(delivery.getLast().get("finalConvergence"));
            assertThat(KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
                finalState.get("receipt")).getFirst().get("outcome")).isEqualTo("NO_CANDIDATE");
        }
        if (raw.containsKey("poisonCrash")) {
            Map<String, Object> poison = map(raw.get("poisonCrash"));
            assertThat(KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
                map(poison.get("before")).get("targets")).getFirst().get("state")).isEqualTo("PENDING");
            List<Map<String, Object>> attempts = list(poison.get("attempts"));
            assertThat(attempts).hasSize(3);
            for (int index = 0; index < attempts.size(); index++) {
                Map<String, Object> claim = attempts.get(index);
                assertThat(map(claim.get("entrypoint")).get("stage")).isEqualTo("CLAIM_HALT");
                assertThat(((Number) claim.get("exit")).intValue()).isEqualTo(91);
                Map<String, Object> during = map(claim.get("durable"));
                Map<String, Object> target = KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
                    during.get("targets")).getFirst();
                assertThat(target.get("state")).isEqualTo("PROCESSING");
                assertThat(((Number) target.get("cycleAttempts")).intValue()).isEqualTo(index + 1);
                assertThat(list(during.get("receipt"))).isEmpty();
            }
            Map<String, Object> end = map(poison.get("finalConvergence"));
            Map<String, Object> target = KafkaIntakeCrashEvidenceIntegrityTests.<Map<String, Object>>list(
                end.get("targets")).getFirst();
            assertThat(target.get("state")).isEqualTo("DEAD");
            assertThat(target.get("failureCode")).isEqualTo("CRASH_EXHAUSTED");
            assertThat(((Number) target.get("cycleAttempts")).intValue()).isEqualTo(3);
            assertThat(list(end.get("receipt"))).isEmpty();
            assertThat(list(end.get("targetIntake"))).hasSize(1);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "slotq-kafka-intake-recalculation/v1");
        result.put("rawSha256", java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("verifiedCrashWindows", cases.stream().map(value -> value.get("fault")).toList());
        result.put("postOffsetPendingAttempts", 0);
        result.put("finalTargetCount", 1);
        result.put("finalReceiptCount", 1);
        result.put("quarantineCount", list(raw.get("quarantine")).size());
        result.put("relayAckCrashDeduplicated", raw.containsKey("relayAckCrash"));
        result.put("quarantinePersistenceRecovered", raw.containsKey("quarantinePersistenceFailure"));
        result.put("deliveryCrashWindows", raw.containsKey("deliveryFaultCases") ? 3 : 0);
        result.put("intentionalCrashExhaustedDead", raw.containsKey("poisonCrash") ? 1 : 0);
        Files.writeString(folder.resolve("intake-crash-recalculated.json"), json.writeValueAsString(result));
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> list(Object value) { return (List<T>) value; }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
