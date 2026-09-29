package com.slotq.events.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Recalculates the #109 oracle solely from committed raw snapshots, never from a handwritten summary. */
@EnabledIfSystemProperty(named = "slotq.fault.kafkaFaultEvidenceDir", matches = ".+")
class KafkaFaultEvidenceIntegrityTests {
    private final JsonMapper json = new JsonMapper();

    @Test
    void recalculatesProcessFaultOraclesFromRawSnapshots() throws Exception {
        String runId = System.getProperty("slotq.fault.kafkaFaultRunId");
        Path folder = Path.of(System.getProperty("slotq.fault.kafkaFaultEvidenceDir"), runId);
        byte[] bytes = Files.readAllBytes(folder.resolve("process-raw.json"));
        Map<String, Object> raw = json.readValue(new String(bytes, StandardCharsets.UTF_8),
            new TypeReference<>() { });
        assertThat(raw.get("schemaVersion")).isEqualTo("slotq-kafka-fault-process/v1");
        assertThat(raw.get("runId")).isEqualTo(runId);
        assertThat(raw.get("seed")).isEqualTo(109);
        List<Map<String, Object>> timeline = list(raw.get("timeline"));
        Map<String, Map<String, Object>> phases = new HashMap<>();
        for (Map<String, Object> observation : timeline) {
            String phase = (String) observation.get("phase");
            phases.putIfAbsent(phase, observation);
        }
        List<String> required = List.of("before-relay", "two-groups-converged",
            "product-http-with-both-groups", "before-observer-outage", "observer-down-waitlist-done",
            "product-http-during-observer-outage", "observer-recovered", "one-to-four-idle-member",
            "before-rebalance", "rebalance-converged", "before-leader-stop", "leader-down-converged",
            "persistent-broker-restarted", "all-brokers-down", "relay-entrypoint-failure",
            "product-http-during-broker-outage", "relay-failed-with-brokers-down",
            "first-broker-recovery", "all-brokers-recovered",
            "before-db-outage", "broker-record-during-db-outage", "consumer-jdbc-entrypoint-failure",
            "db-unavailable-after-intake-entrypoint",
            "first-db-recovery", "db-outage-converged");
        assertThat(phases.keySet()).containsAll(required);

        Map<String, Object> before = phases.get("before-relay");
        assertThat(list(before.get("event"))).hasSize(1);
        assertThat(list(before.get("publication"))).isEmpty();
        assertThat(list(before.get("delivery"))).isEmpty();
        for (String phase : List.of("product-http-with-both-groups",
            "product-http-during-observer-outage", "product-http-during-broker-outage"))
            assertThat(phases.get(phase).get("status")).isEqualTo(200);
        Map<String, Object> observerOutage = phases.get("observer-down-waitlist-done");
        assertThat(KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(observerOutage.get("delivery")))
            .anySatisfy(row -> {
                assertThat(row.get("consumerId")).isEqualTo("waitlist.promotion");
                assertThat(row.get("state")).isEqualTo("DONE");
            }).noneSatisfy(row -> {
                assertThat(row.get("consumerId")).isEqualTo("operations.event-observation");
                assertThat(row.get("state")).isEqualTo("DONE");
            });
        Map<String, Object> scaled = phases.get("one-to-four-idle-member");
        List<Integer> assignments = list(group(scaled, "waitlist.promotion").get("memberPartitionCounts"));
        assertThat(assignments).hasSize(4).contains(0);
        assertThat(assignments.stream().mapToInt(Number::intValue).sum()).isEqualTo(3);
        assertThat(list(group(scaled, "operations.event-observation").get("memberPartitionCounts")))
            .hasSize(1);

        Map<String, Object> leaderBefore = phases.get("before-leader-stop");
        Map<String, Object> leaderDuring = phases.get("leader-down-converged");
        int changedLeaders = 0;
        for (int index = 0; index < 3; index++) {
            Map<String, Object> old = KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(
                broker(leaderBefore).get("partitions")).get(index);
            Map<String, Object> next = KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(
                broker(leaderDuring).get("partitions")).get(index);
            if (!old.get("leader").equals(next.get("leader"))) changedLeaders++;
            assertThat(list(next.get("isr")).size()).isGreaterThanOrEqualTo(2);
        }
        assertThat(changedLeaders).isGreaterThan(0);
        assertThat(broker(phases.get("persistent-broker-restarted")).get("topicId"))
            .isEqualTo(broker(leaderBefore).get("topicId"));
        assertThat(phases.get("persistent-broker-restarted").get("kraftQuorum").toString())
            .contains("CurrentVoters:");
        assertThat(list(phases.get("all-brokers-down").get("event"))).hasSize(1);
        assertThat(broker(phases.get("all-brokers-down")).get("available")).isEqualTo(false);
        assertThat(phases.get("relay-entrypoint-failure").get("observation"))
            .isEqualTo("operation=kafka_relay outcome=degraded");
        assertThat(phases.get("db-unavailable-after-intake-entrypoint")).containsKey("dbUnavailable");
        assertThat(broker(phases.get("db-unavailable-after-intake-entrypoint")).get("available"))
            .isEqualTo(true);
        assertThat(list(phases.get("first-db-recovery").get("event"))).hasSize(1);

        int explained = 0;
        int duplicateTargets = 0;
        int partialReceiptDone = 0;
        int unexplainedDead = 0;
        int unexpectedPromotionalReservations = 0;
        int capacityViolations = 0;
        for (String phase : List.of("two-groups-converged", "observer-recovered", "rebalance-converged",
            "leader-down-converged", "all-brokers-recovered", "db-outage-converged")) {
            Map<String, Object> end = phases.get(phase);
            assertThat(list(end.get("event"))).hasSize(1);
            assertThat(list(end.get("publication"))).hasSize(1);
            assertThat(KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(
                end.get("publication")).getFirst().get("state")).isEqualTo("PUBLISHED");
            List<Map<String, Object>> targets = list(end.get("targetIntake"));
            List<Map<String, Object>> deliveries = list(end.get("delivery"));
            List<Map<String, Object>> receipts = list(end.get("receipt"));
            Set<Object> consumers = new HashSet<>();
            for (Map<String, Object> target : targets) consumers.add(target.get("consumerId"));
            duplicateTargets += targets.size() - consumers.size();
            assertThat(consumers).containsExactlyInAnyOrder("waitlist.promotion",
                "operations.event-observation");
            assertThat(deliveries).hasSize(2).allSatisfy(row ->
                assertThat(row.get("state")).isEqualTo("DONE"));
            assertThat(receipts).hasSize(1);
            boolean promotion = raw.containsKey("candidate") && phase.equals("db-outage-converged");
            assertThat(receipts.getFirst().get("outcome"))
                .isEqualTo(promotion ? "PROMOTED" : "NO_CANDIDATE");
            assertThat(((Number) end.get("projectionCount")).longValue()).isEqualTo(1);
            Map<String, Object> business = map(end.get("business"));
            int promotionalReservations = ((Number) business.get("promotionalReservations")).intValue();
            int offers = ((Number) business.get("offers")).intValue();
            if (promotion) {
                assertThat(end.get("eventId")).isEqualTo(map(raw.get("candidate")).get("eventId"));
                assertThat(promotionalReservations).isEqualTo(1);
                assertThat(offers).isEqualTo(1);
                int active = ((Number) business.get("activeAllocations")).intValue();
                if (active > ((Number) map(raw.get("candidate")).get("slotCapacity")).intValue())
                    capacityViolations++;
                assertThat(active).isEqualTo(1);
            } else {
                unexpectedPromotionalReservations += promotionalReservations;
                assertThat(offers).isZero();
                assertThat(((Number) business.get("notifications")).longValue()).isZero();
            }
            for (Map<String, Object> delivery : deliveries) {
                if ("DEAD".equals(delivery.get("state"))) unexplainedDead++;
                if ("waitlist.promotion".equals(delivery.get("consumerId")) && receipts.size() != 1)
                    partialReceiptDone++;
            }
            assertOffsetsDoNotExceedDurablePrefix(end);
            explained++;
        }
        assertThat(list(raw.get("originalEventIds"))).hasSize(explained);
        assertThat(duplicateTargets).isZero();
        assertThat(partialReceiptDone).isZero();
        assertThat(unexplainedDead).isZero();
        assertThat(unexpectedPromotionalReservations).isZero();
        assertThat(capacityViolations).isZero();
        assertThat(map(raw.get("brokerVolumes")).values()).allSatisfy(value ->
            assertThat(value.toString()).contains("slotq-kafka-fault_kafka-"));
        assertThat(raw.get("mysqlVolume").toString()).contains("slotq-fault-109-mysql-" + runId);

        Map<String, Object> recalculated = new LinkedHashMap<>();
        recalculated.put("schemaVersion", "slotq-kafka-fault-recalculation/v1");
        recalculated.put("rawSha256", java.util.HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)));
        recalculated.put("explainedOriginalEvents", explained);
        recalculated.put("duplicateLogicalTargets", duplicateTargets);
        recalculated.put("partialReceiptDone", partialReceiptDone);
        recalculated.put("unexplainedDead", unexplainedDead);
        recalculated.put("unexpectedPromotionalReservations", unexpectedPromotionalReservations);
        recalculated.put("capacityViolations", capacityViolations);
        recalculated.put("observedPhases", required);
        Files.writeString(folder.resolve("recalculated.json"), json.writeValueAsString(recalculated));
    }

    private void assertOffsetsDoNotExceedDurablePrefix(Map<String, Object> snapshot) {
        Map<String, Long> durable = new HashMap<>();
        for (Map<String, Object> position : KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(
            snapshot.get("durablePositions"))) {
            durable.put(position.get("consumerId") + ":" + position.get("partitionId"),
                ((Number) position.get("lastDurableOffset")).longValue() + 1);
        }
        for (String consumer : List.of("waitlist.promotion", "operations.event-observation")) {
            Map<String, Object> group = group(snapshot, consumer);
            if (!group.containsKey("offsets")) continue;
            Map<String, Object> brokerOffsets = map(group.get("offsets"));
            for (var offset : brokerOffsets.entrySet()) {
                String partition = offset.getKey().substring(offset.getKey().lastIndexOf(':') + 1);
                Long prefix = durable.get(consumer + ":" + partition);
                assertThat(prefix).as("durable prefix for %s %s", consumer, partition).isNotNull();
                assertThat(((Number) offset.getValue()).longValue()).isLessThanOrEqualTo(prefix);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> list(Object value) { return (List<T>) value; }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static Map<String, Object> broker(Map<String, Object> snapshot) { return map(snapshot.get("broker")); }
    private static Map<String, Object> group(Map<String, Object> snapshot, String consumer) {
        return map(map(broker(snapshot).get("groups")).get(consumer));
    }
}
