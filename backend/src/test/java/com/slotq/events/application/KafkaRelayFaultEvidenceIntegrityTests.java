package com.slotq.events.application;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Recalculates relay publication outcomes from raw MySQL and broker observations. */
@EnabledIfSystemProperty(named = "slotq.kafka.evidence.dir", matches = ".+")
class KafkaRelayFaultEvidenceIntegrityTests {
    @Test
    void recalculatesRollbackAckAmbiguityFencingOutageAndRetentionGap() throws Exception {
        Path folder = Path.of(System.getProperty("slotq.kafka.evidence.dir"));
        byte[] bytes = Files.readAllBytes(folder.resolve("fault-raw.json"));
        JsonMapper json = new JsonMapper();
        Map<String, Object> raw = json.readValue(new String(bytes, StandardCharsets.UTF_8),
            new TypeReference<>() { });
        assertThat(raw.get("schemaVersion")).isEqualTo("slotq-kafka-relay-fault/v1");
        Map<String, Map<String, Object>> phases = new HashMap<>();
        for (Map<String, Object> phase : KafkaRelayFaultEvidenceIntegrityTests.<Map<String, Object>>list(
            raw.get("faultTimeline"))) phases.put((String) phase.get("phase"), phase);
        assertThat(phases.keySet()).containsAll(List.of("business-rollback", "append-failure",
            "committed-before-relay",
            "two-relay-claim", "ack-before-mark", "stale-mark-rejected", "ack-mark-recovered",
            "retention-gap", "before-ack-response-loss", "ack-response-lost",
            "ack-response-recovered", "before-broker-pause", "broker-paused", "broker-recovered",
            "before-db-pause", "db-paused-entrypoint-failure", "db-recovered"));
        assertThat(list(phases.get("business-rollback").get("original"))).isEmpty();
        assertThat(list(phases.get("append-failure").get("original"))).isEmpty();
        assertThat(list(phases.get("append-failure").get("publication"))).isEmpty();
        assertThat(((Number) phases.get("append-failure").get("businessRows")).intValue()).isZero();
        assertThat(list(phases.get("committed-before-relay").get("original"))).hasSize(1);
        assertThat(list(phases.get("committed-before-relay").get("publication"))).isEmpty();
        assertPublication(phases, "two-relay-claim", "PROCESSING", 1, 1);
        assertPublication(phases, "ack-before-mark", "PROCESSING", 1, 1);
        assertPublication(phases, "stale-mark-rejected", "PUBLISHED", 2, 2);
        assertPublication(phases, "ack-mark-recovered", "PUBLISHED", 2, 2);
        assertPublication(phases, "ack-response-lost", "PENDING", 1, 1);
        assertPublication(phases, "ack-response-recovered", "PUBLISHED", 2, 2);
        assertPublication(phases, "broker-paused", "PENDING", 1, 1);
        assertPublication(phases, "broker-recovered", "PUBLISHED", 2, 2);
        assertPublication(phases, "db-recovered", "PUBLISHED", 1, 1);
        assertThat(phases.get("db-paused-entrypoint-failure").get("entrypoint"))
            .isEqualTo("JdbcKafkaPublicationLedger.discover");
        Map<String, Object> retention = phases.get("retention-gap");
        assertThat(((Number) retention.get("logStartAfterDelete")).longValue())
            .isGreaterThan(((Number) retention.get("ackOffset")).longValue());
        assertThat(list(retention.get("original"))).hasSize(1);
        assertThat(list(retention.get("publication"))).hasSize(1);
        assertThat(retention.get("incident")).isEqualTo("KafkaRetentionProbe: log-start gap");

        Map<String, Object> ids = map(raw.get("fixtureEventIds"));
        List<Map<String, Object>> brokerRecords = list(raw.get("brokerRecordsObservedBeforeAndAfterAckLoss"));
        long postMarkPhysical = brokerRecords.stream().filter(row -> row.get("value").toString()
            .contains(ids.get("markingCrash").toString())).count();
        long responseLostPhysical = brokerRecords.stream().filter(row -> row.get("value").toString()
            .contains(ids.get("ackLost").toString())).count();
        assertThat(brokerRecords.stream().filter(row -> row.get("value").toString()
            .contains(ids.get("appendFailed").toString()))).isEmpty();
        assertThat(postMarkPhysical).isEqualTo(2);
        assertThat(responseLostPhysical).isEqualTo(2);
        assertThat(list(raw.get("eventRecords"))).hasSize(4);
        assertThat(list(raw.get("publications"))).hasSize(4);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "slotq-kafka-relay-recalculation/v1");
        result.put("rawSha256", java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("committedOriginals", list(raw.get("eventRecords")).size());
        result.put("appendFailureRolledBack", true);
        result.put("publishedOriginals", list(raw.get("publications")).size());
        result.put("physicalRecordsAfterMarkCrash", postMarkPhysical);
        result.put("physicalRecordsAfterAckLoss", responseLostPhysical);
        result.put("retentionGapIncident", true);
        result.put("retentionGapOriginalDurable", true);
        Files.writeString(folder.resolve("fault-recalculated.json"), json.writeValueAsString(result));
    }

    private static void assertPublication(Map<String, Map<String, Object>> phases, String phase,
        String state, int attempts, int fencing) {
        Map<String, Object> publication = KafkaRelayFaultEvidenceIntegrityTests.<Map<String, Object>>list(
            phases.get(phase).get("publication")).getFirst();
        assertThat(publication.get("state")).as(phase).isEqualTo(state);
        assertThat(((Number) publication.get("lifetimeAttempts")).intValue()).as(phase).isEqualTo(attempts);
        assertThat(((Number) publication.get("fencingToken")).intValue()).as(phase).isEqualTo(fencing);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> list(Object value) { return (List<T>) value; }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
