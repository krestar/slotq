package com.slotq.events.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.slotq.SlotqApplication;
import com.slotq.events.application.KafkaConsumerCatalog;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Opt-in process-crash fixture; exit codes identify the exact durable window. */
public final class KafkaIntakeCrashChild {
    private KafkaIntakeCrashChild() { }

    public static void main(String[] args) {
        String mode = args[0];
        String topic = System.getenv("SLOTQ_INTAKE_TEST_TOPIC");
        String eventId = System.getenv("SLOTQ_INTAKE_TEST_EVENT");
        String bootstrap = System.getenv("SLOTQ_INTAKE_TEST_BROKER");
        try (var context = new SpringApplicationBuilder(SlotqApplication.class)
            .web(WebApplicationType.SERVLET)
            .properties(Map.of(
                "spring.datasource.url", System.getenv("SLOTQ_INTAKE_TEST_JDBC"),
                "spring.datasource.username", System.getenv("SLOTQ_INTAKE_TEST_USER"),
                "spring.datasource.password", System.getenv("SLOTQ_INTAKE_TEST_PASSWORD"),
                "slotq.events.delivery.scheduler-enabled", "false",
                "slotq.waitlist.promotion.enabled", "false",
                "slotq.operations.observation.enabled", "false",
                "server.port", "0"))
            .run()) {
            var catalog = context.getBean(KafkaConsumerCatalog.class);
            var definition = catalog.definition("waitlist.promotion");
            var store = context.getBean(JdbcKafkaIntakeStore.class);
            Properties settings = new Properties();
            settings.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            settings.put(ConsumerConfig.GROUP_ID_CONFIG, definition.groupId());
            settings.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            settings.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            settings.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 8_000);
            settings.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 2_000);
            settings.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
            settings.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
            try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(settings)) {
                consumer.subscribe(List.of(topic));
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (System.nanoTime() < deadline) {
                    for (var record : consumer.poll(Duration.ofMillis(250))) {
                        if (!new String(record.value(), java.nio.charset.StandardCharsets.UTF_8).contains(eventId)) continue;
                        if (mode.equals("BEFORE_INTAKE")) Runtime.getRuntime().halt(81);
                        store.intake(record, definition, 2);
                        if (mode.equals("AFTER_INTAKE")) Runtime.getRuntime().halt(82);
                        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                        consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
                        if (mode.equals("AFTER_OFFSET")) Runtime.getRuntime().halt(83);
                        throw new IllegalArgumentException("Unknown crash mode");
                    }
                }
                throw new IllegalStateException("Crash fixture did not observe the original Kafka event");
            }
        }
    }
}
