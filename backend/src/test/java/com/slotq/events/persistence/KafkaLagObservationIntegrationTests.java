package com.slotq.events.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.slotq.events.application.KafkaConsumerCatalog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers
class KafkaLagObservationIntegrationTests {
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    @Test void realEmptyBrokerPartitionHasHealthyKnownZeroWithoutACommittedOffset() throws Exception {
        String topic = "slotq.empty126." + UUID.randomUUID();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        var config = Map.<String, Object>of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "slotq.empty126", ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        var meters = new SimpleMeterRegistry();
        try (var broker = new KafkaConsumer<byte[], byte[]>(config)) {
            var partition = new TopicPartition(topic, 0);
            broker.assign(Set.of(partition));
            assertThat(broker.endOffsets(Set.of(partition), Duration.ofSeconds(5))).containsEntry(partition, 0L);
            assertThat(broker.committed(Set.of(partition), Duration.ofSeconds(5)).get(partition)).isNull();
            var definition = new KafkaConsumerCatalog.ConsumerDefinition("waitlist.promotion", "slotq.empty126", List.of());
            var observation = new KafkaIntakeObservability(mock(JdbcKafkaIntakeStore.class), meters, definition, topic, Set.of(0));
            observation.state("ready");
            observation.sample(broker);
            assertThat(meters.get("slotq.kafka.lag.sample.healthy").gauge().value()).isEqualTo(1);
            assertThat(meters.get("slotq.kafka.consumer.lag.records").gauge().value()).isZero();
            observation.state("degraded");
            assertThat(meters.get("slotq.kafka.lag.sample.healthy").gauge().value()).isZero();
            assertThat(meters.get("slotq.kafka.consumer.lag.records").gauge().value()).isNaN();
        } finally { meters.close(); }
    }
}
