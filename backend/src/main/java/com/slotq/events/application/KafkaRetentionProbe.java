package com.slotq.events.application;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.slotq.events.persistence.JdbcKafkaPublicationLedger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Broker metadata is observed before a short MySQL comparison transaction. */
@Component
@ConditionalOnProperty(name = "slotq.events.kafka.relay-enabled", havingValue = "true")
public final class KafkaRetentionProbe {
    private final AdminClient admin;
    private final JdbcKafkaPublicationLedger ledger;

    public KafkaRetentionProbe(AdminClient admin, JdbcKafkaPublicationLedger ledger) {
        this.admin = admin; this.ledger = ledger;
    }

    public void verify(String destination) {
        try {
            var topic = admin.describeTopics(java.util.List.of(destination)).allTopicNames()
                .get(5, TimeUnit.SECONDS).get(destination);
            Map<TopicPartition, OffsetSpec> requests = new HashMap<>();
            topic.partitions().forEach(partition -> requests.put(
                new TopicPartition(destination, partition.partition()), OffsetSpec.earliest()));
            var offsets = admin.listOffsets(requests, new ListOffsetsOptions()).all().get(5, TimeUnit.SECONDS);
            Map<Integer, Long> starts = new HashMap<>();
            offsets.forEach((partition, info) -> starts.put(partition.partition(), info.offset()));
            ledger.verifyTopic(destination, topic.topicId().toString(), starts);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka retention probe interrupted", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Kafka retention probe unavailable", failure);
        }
    }
}
