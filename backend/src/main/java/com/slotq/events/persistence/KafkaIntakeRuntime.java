package com.slotq.events.persistence;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.slotq.events.application.EventCanonicalizer;
import com.slotq.events.application.KafkaConsumerCatalog;
import com.slotq.events.application.KafkaRuntimeGuard;
import com.slotq.events.persistence.JdbcKafkaIntakeStore.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Synchronous bounded intake. MySQL commits before each explicit partition offset commit. */
@Component
@ConditionalOnProperty(name = "slotq.events.kafka.consumer-enabled", havingValue = "true")
public final class KafkaIntakeRuntime {
    private static final Duration POLL = Duration.ofMillis(250);
    private static final Duration COMMIT = Duration.ofSeconds(5);
    private final JdbcKafkaIntakeStore store;
    private final KafkaRuntimeGuard guard;
    private final KafkaConsumerCatalog.ConsumerDefinition definition;
    private final KafkaIntakeObservability observation;
    private final String topic;
    private final long epoch;
    private final Map<String, Object> connection;
    private KafkaConsumer<byte[], byte[]> consumer;
    private boolean halted;

    public KafkaIntakeRuntime(JdbcKafkaIntakeStore store, KafkaRuntimeGuard guard,
                             KafkaConsumerCatalog catalog, MeterRegistry meters,
        @Value("${slotq.events.runtime-role:product}") String role,
        @Value("${slotq.events.delivery.consumer-id:}") String consumerId,
        @Value("${slotq.events.delivery.transport:DB_DIRECT}") String transport,
        @Value("${slotq.events.delivery.authority-epoch:1}") long epoch,
        @Value("${slotq.events.kafka.destination:slotq.waitlist.events.v1}") String topic,
        @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrap,
        @Value("${slotq.events.kafka.security-protocol:PLAINTEXT}") String protocol,
        @Value("${slotq.events.kafka.sasl-mechanism:}") String mechanism,
        @Value("${slotq.events.kafka.sasl-jaas-config:}") String jaas,
        @Value("${slotq.events.kafka.ssl-truststore-location:}") String truststore,
        @Value("${slotq.events.kafka.ssl-truststore-password:}") String truststorePassword,
        @Value("${slotq.events.kafka.consumer.partitions:0,1,2}") String partitions) {
        if (!role.equals("consumer") || !transport.equals("KAFKA") || epoch <= 0)
            throw new IllegalStateException("Kafka intake requires the Kafka consumer execution scope");
        this.store = store;
        this.guard = guard;
        this.definition = catalog.definition(consumerId);
        EventCanonicalizer.requireIdentifier(topic, "Kafka destination");
        this.topic = topic;
        this.epoch = epoch;
        this.connection = connection(bootstrap, protocol, mechanism, jaas, truststore, truststorePassword);
        Set<Integer> allowed = java.util.Arrays.stream(partitions.split(","))
            .map(String::trim).map(Integer::parseInt).collect(java.util.stream.Collectors.toSet());
        if (allowed.isEmpty() || allowed.size() > 64 || allowed.stream().anyMatch(value -> value < 0))
            throw new IllegalArgumentException("Invalid configured Kafka partition allowlist");
        this.observation = new KafkaIntakeObservability(store, meters, definition, topic, allowed);
    }

    @Scheduled(fixedDelayString = "${slotq.events.kafka.consumer.poll-interval:PT1S}")
    public synchronized void runCycle() {
        if (halted || !guard.consumerReady()) return;
        try {
            if (consumer == null) open();
            var records = consumer.poll(POLL);
            for (var record : records) {
                TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                if (!consumer.assignment().contains(partition)) break;
                Outcome outcome;
                try {
                    outcome = store.intake(record, definition, epoch);
                } catch (RuntimeException failure) {
                    observation.intakeFailure();
                    throw failure;
                }
                // An offset commits durable DB responsibility, including explained skip/quarantine.
                consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)), COMMIT);
                observation.afterIntake(record, outcome);
            }
            observation.state("ready");
            observation.sample(consumer);
        } catch (RuntimeException failure) {
            halted = true;
            close();
            observation.state("degraded");
            // The broker coordinate remains at or behind the last durable DB prefix. Restart is explicit.
            throw new IllegalStateException("Kafka intake halted; inspect durable provenance before restart");
        }
    }

    private void open() {
        try (AdminClient admin = AdminClient.create(connection)) {
            var description = admin.describeTopics(List.of(topic)).allTopicNames().get(5, TimeUnit.SECONDS).get(topic);
            store.verifyTopicIdentity(topic, description.topicId().toString());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka topic check interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("Kafka topic identity unavailable");
        }
        Map<String, Object> config = new HashMap<>(connection);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, definition.groupId());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 8);
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 15_000);
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 5_000);
        config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        KafkaConsumer<byte[], byte[]> opened = new KafkaConsumer<>(config);
        try {
            opened.subscribe(List.of(topic), new ConsumerRebalanceListener() {
                @Override public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                    // No async handoff exists; every processed record was durably intaken and committed already.
                    observation.rebalance("revoked");
                }

                @Override public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                    observation.rebalance("assigned");
                    Map<TopicPartition, Long> beginnings = opened.beginningOffsets(partitions);
                    Map<TopicPartition, OffsetAndMetadata> committed = opened.committed(new java.util.HashSet<>(partitions));
                    for (TopicPartition partition : partitions) {
                        OffsetAndMetadata broker = committed.get(partition);
                        long start = store.startOffset(definition, partition.topic(), partition.partition(),
                            beginnings.get(partition), broker == null ? null : broker.offset());
                        opened.seek(partition, start);
                    }
                }
            });
            consumer = opened;
        } catch (RuntimeException failure) {
            opened.close(COMMIT);
            throw failure;
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (consumer != null) {
            consumer.close(COMMIT);
            consumer = null;
        }
        observation.state("stopped");
    }

    private static Map<String, Object> connection(String bootstrap, String protocol, String mechanism,
                                                   String jaas, String truststore, String truststorePassword) {
        if (bootstrap == null || bootstrap.isBlank()) throw new IllegalArgumentException("Kafka bootstrap required");
        Map<String, Object> settings = new HashMap<>();
        settings.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        settings.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol);
        if (protocol.equals("PLAINTEXT")) {
            for (String endpoint : bootstrap.split(","))
                if (!endpoint.trim().matches("(?:localhost|127\\.0\\.0\\.1):[0-9]{1,5}"))
                    throw new IllegalArgumentException("Anonymous Kafka is limited to loopback development");
        } else if (protocol.equals("SASL_SSL")) {
            if (mechanism.isBlank() || jaas.isBlank() || truststore.isBlank() || truststorePassword.isBlank())
                throw new IllegalArgumentException("SASL_SSL Kafka credentials and truststore required");
            settings.put(SaslConfigs.SASL_MECHANISM, mechanism);
            settings.put(SaslConfigs.SASL_JAAS_CONFIG, jaas);
            settings.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, truststore);
            settings.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, truststorePassword);
            settings.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PKCS12");
        } else throw new IllegalArgumentException("Unsupported Kafka security protocol");
        return Map.copyOf(settings);
    }
}
