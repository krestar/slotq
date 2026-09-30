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
        boolean initialFailStop = phases.containsKey("initial-consumer-fail-stop");
        if (initialFailStop) {
            assertThat(phases).containsKey("initial-consumer-restart");
            Map<String, Object> stalled = phases.get("initial-consumer-fail-stop");
            assertThat(list(stalled.get("event"))).hasSize(1);
            assertThat(list(stalled.get("publication"))).hasSize(1);
            int waitlistMembers = list(group(stalled, "waitlist.promotion").get("members")).size();
            int observerMembers = list(group(stalled, "operations.event-observation").get("members")).size();
            assertThat(waitlistMembers == 0 || observerMembers == 0).isTrue();
            assertThat(KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(
                phases.get("initial-consumer-restart").get("processes")))
                .anySatisfy(process -> assertThat(process.get("label").toString())
                    .contains("initial-recovered"));
        }
        boolean slowIntake = phases.containsKey("slow-intake-converged");
        if (slowIntake) {
            assertThat(phases.keySet()).containsAll(List.of("slow-intake-before-lock",
                "slow-intake-record-produced", "slow-intake-jdbc-entrypoint-waiting",
                "slow-intake-locked", "slow-intake-old-new-jdbc-entrypoints-waiting",
                "slow-intake-poll-timeout-rebalance", "slow-intake-first-db-opportunity",
                "slow-intake-converged"));
            assertThat(((Number) phases.get("slow-intake-jdbc-entrypoint-waiting")
                .get("waiters")).intValue()).isGreaterThanOrEqualTo(1);
            assertThat(((Number) phases.get("slow-intake-old-new-jdbc-entrypoints-waiting")
                .get("waiters")).intValue()).isGreaterThanOrEqualTo(2);
            Map<String, Object> locked = phases.get("slow-intake-locked");
            Map<String, Object> rebalanced = phases.get("slow-intake-poll-timeout-rebalance");
            assertThat(KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(locked.get("targetIntake"))
                .stream().filter(row -> "waitlist.promotion".equals(row.get("consumerId"))).toList()).isEmpty();
            assertThat(KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(rebalanced.get("targetIntake"))
                .stream().filter(row -> "waitlist.promotion".equals(row.get("consumerId"))).toList()).isEmpty();
            List<Map<String, Object>> oldMembers = list(group(locked, "waitlist.promotion").get("members"));
            List<Map<String, Object>> newMembers = list(group(rebalanced, "waitlist.promotion").get("members"));
            assertThat(oldMembers).hasSize(2);
            assertThat(newMembers).hasSize(1);
            int partition = ((Number) map(phases.get("slow-intake-record-produced")
                .get("coordinate")).get("partition")).intValue();
            String oldOwner = oldMembers.stream().filter(member -> KafkaFaultEvidenceIntegrityTests.<Number>list(
                member.get("assignedPartitions")).stream().anyMatch(value -> value.intValue() == partition))
                .map(member -> member.get("memberId").toString()).findFirst().orElseThrow();
            String newOwner = newMembers.stream().filter(member -> KafkaFaultEvidenceIntegrityTests.<Number>list(
                member.get("assignedPartitions")).stream().anyMatch(value -> value.intValue() == partition))
                .map(member -> member.get("memberId").toString()).findFirst().orElseThrow();
            assertThat(newOwner).isNotEqualTo(oldOwner);
            assertOffsetsDoNotExceedDurablePrefix(phases.get("slow-intake-converged"));
        }

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
        List<String> convergedPhases = new java.util.ArrayList<>(List.of("two-groups-converged",
            "observer-recovered", "rebalance-converged", "leader-down-converged",
            "all-brokers-recovered", "db-outage-converged"));
        if (slowIntake) convergedPhases.add("slow-intake-converged");
        for (String phase : convergedPhases) {
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
        boolean maintenance = phases.containsKey("maintenance-release-converged");
        if (slowIntake) assertThat(maintenance).isTrue();
        if (maintenance) {
            assertThat(phases.keySet()).containsAll(List.of("maintenance-before-admission-and-expiry",
                "maintenance-first-opportunity", "maintenance-admission-converged",
                "maintenance-release-converged", "product-http-with-maintenance"));
            assertThat(phases.get("product-http-with-maintenance").get("status")).isEqualTo(200);
            Map<String, Object> beforeMaintenance = map(
                phases.get("maintenance-before-admission-and-expiry").get("maintenance"));
            assertThat(list(beforeMaintenance.get("slotEvents"))).isEmpty();
            assertThat(list(beforeMaintenance.get("slotOffers"))).isEmpty();
            assertThat(list(beforeMaintenance.get("firstCandidate"))).hasSize(1);
            for (String phase : List.of("maintenance-admission-converged",
                "maintenance-release-converged")) {
                Map<String, Object> end = phases.get(phase);
                assertThat(list(end.get("event"))).hasSize(1);
                assertThat(KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(
                    end.get("publication")).getFirst().get("state")).isEqualTo("PUBLISHED");
                List<Map<String, Object>> targets = list(end.get("targetIntake"));
                assertThat(targets).hasSize(2);
                assertThat(targets.stream().map(row -> row.get("consumerId")).toList())
                    .containsExactlyInAnyOrder("waitlist.promotion", "operations.event-observation");
                duplicateTargets += targets.size() - (int) targets.stream().map(row -> row.get("consumerId"))
                    .distinct().count();
                assertThat(KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(end.get("delivery")))
                    .hasSize(2).allSatisfy(row -> assertThat(row.get("state")).isEqualTo("DONE"));
                assertThat(list(end.get("receipt"))).hasSize(1);
                assertOffsetsDoNotExceedDurablePrefix(end);
                explained++;
            }
            Map<String, Object> admitted = phases.get("maintenance-admission-converged");
            Map<String, Object> released = phases.get("maintenance-release-converged");
            assertThat(KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(
                admitted.get("receipt")).getFirst().get("outcome")).isEqualTo("PROMOTED");
            assertThat(KafkaFaultEvidenceIntegrityTests.<Map<String, Object>>list(
                released.get("receipt")).getFirst().get("outcome")).isEqualTo("NO_CANDIDATE");
            Map<String, Object> finalMaintenance = map(released.get("maintenance"));
            assertThat(list(finalMaintenance.get("slotEvents"))).hasSize(1);
            List<Map<String, Object>> slotOffers = list(finalMaintenance.get("slotOffers"));
            assertThat(slotOffers).hasSize(1);
            assertThat(slotOffers.getFirst().get("allocationActive")).isEqualTo(true);
            List<Map<String, Object>> firstCandidate = list(finalMaintenance.get("firstCandidate"));
            assertThat(firstCandidate).hasSize(1);
            assertThat(firstCandidate.getFirst().get("offerState")).isEqualTo("EXPIRED");
            assertThat(firstCandidate.getFirst().get("allocationActive")).isEqualTo(false);
            assertThat(((Number) finalMaintenance.get("allOriginalCount")).intValue()).isEqualTo(explained);
            List<Map<String, Object>> allOriginals = list(raw.get("allOriginals"));
            assertThat(allOriginals).hasSize(explained);
            assertThat(allOriginals.stream().map(row -> row.get("eventId")).toList())
                .containsExactlyInAnyOrderElementsOf(KafkaFaultEvidenceIntegrityTests.<String>list(
                    raw.get("originalEventIds")).stream().map(id -> id.replace("-", "")
                    .toUpperCase(java.util.Locale.ROOT)).toList());
            Map<String, Object> identities = map(raw.get("maintenance"));
            assertThat(admitted.get("eventId")).isEqualTo(identities.get("admissionEventId"));
            assertThat(released.get("eventId")).isEqualTo(identities.get("releaseEventId"));
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
        recalculated.put("realPollTimeoutRebalance", slowIntake);
        recalculated.put("initialFailStopRecovered", initialFailStop);
        recalculated.put("multiProcessMaintenance", maintenance);
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
