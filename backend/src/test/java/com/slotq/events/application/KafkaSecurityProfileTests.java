package com.slotq.events.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in against the external secure Compose fixture; secrets remain in a temporary directory. */
@EnabledIfSystemProperty(named = "slotq.kafka.secure.fixture", matches = ".+")
class KafkaSecurityProfileTests {
    private static final String TOPIC = "slotq.waitlist.events.v1";

    @Test void tlsAuthenticationAndRoleAclAreEnforced() throws Exception {
        Path fixture = Path.of(System.getProperty("slotq.kafka.secure.fixture"));
        String payload = "secure-probe-" + UUID.randomUUID();
        var relay = settings(fixture, "relay");
        try (AdminClient admin = AdminClient.create(relay.properties())) {
            var topic = admin.describeTopics(java.util.List.of(TOPIC)).allTopicNames()
                .get(10, TimeUnit.SECONDS).get(TOPIC);
            assertThat(topic.topicId()).isNotNull();
            assertThat(admin.listOffsets(Map.of(new org.apache.kafka.common.TopicPartition(TOPIC, 0),
                org.apache.kafka.clients.admin.OffsetSpec.earliest())).all().get(10, TimeUnit.SECONDS))
                .hasSize(1);
        }
        var template = new KafkaRelayConfiguration().publicationTemplate(relay);
        try {
            template.send(TOPIC, "tenant:slot", payload).get(10, TimeUnit.SECONDS);
        } finally {
            ((org.springframework.kafka.core.DefaultKafkaProducerFactory<?, ?>)
                template.getProducerFactory()).destroy();
        }

        Properties reader = consumer(fixture, "waitlist", "slotq.waitlist.promotion");
        try (KafkaConsumer<String, String> allowed = new KafkaConsumer<>(reader)) {
            allowed.subscribe(java.util.List.of(TOPIC));
            boolean seen = false;
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!seen && System.nanoTime() < deadline) {
                for (var record : allowed.poll(Duration.ofMillis(250))) {
                    if (record.value().equals(payload)) seen = true;
                }
            }
            assertThat(seen).isTrue();
        }
        Properties deniedReader = consumer(fixture, "relay", "slotq.waitlist.promotion");
        try (KafkaConsumer<String, String> denied = new KafkaConsumer<>(deniedReader)) {
            denied.subscribe(java.util.List.of(TOPIC));
            assertThatThrownBy(() -> denied.poll(Duration.ofSeconds(5)))
                .isInstanceOf(org.apache.kafka.common.errors.AuthorizationException.class);
        }
        var forbiddenWriter = new KafkaRelayConfiguration().publicationTemplate(settings(fixture, "waitlist"));
        try {
            assertThatThrownBy(() -> forbiddenWriter.send(TOPIC, "tenant:slot", "forbidden")
                .get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(org.apache.kafka.common.errors.AuthorizationException.class);
        } finally {
            ((org.springframework.kafka.core.DefaultKafkaProducerFactory<?, ?>)
                forbiddenWriter.getProducerFactory()).destroy();
        }
        try (AdminClient monitor = AdminClient.create(settings(fixture, "monitor").properties())) {
            assertThat(monitor.describeTopics(java.util.List.of(TOPIC)).allTopicNames()
                .get(10, TimeUnit.SECONDS)).containsKey(TOPIC);
        }
        try (AdminClient anonymous = AdminClient.create(Map.of(
            "bootstrap.servers", "localhost:59094", "security.protocol", "SSL",
            "ssl.truststore.location", fixture.resolve("truststore.p12").toString(),
            "ssl.truststore.password", Files.readString(fixture.resolve("broker.credentials")),
            "ssl.truststore.type", "PKCS12"))) {
            assertThatThrownBy(() -> anonymous.describeTopics(java.util.List.of(TOPIC)).allTopicNames()
                .get(5, TimeUnit.SECONDS)).isInstanceOf(Exception.class);
        }
        String evidenceDirectory = System.getProperty("slotq.kafka.evidence.dir");
        if (evidenceDirectory != null) {
            Path output = Path.of(evidenceDirectory).resolve("security-raw.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, new tools.jackson.databind.json.JsonMapper().writeValueAsString(Map.of(
                "schemaVersion", "slotq-kafka-security/v1",
                "brokerImage", "apache/kafka:4.1.1",
                "listener", "SASL_SSL on loopback; TLS SAN localhost; ephemeral PKCS12 outside repository",
                "topic", TOPIC,
                "observed", java.util.List.of("relay topic describe allowed", "relay log start allowed",
                    "relay produce allowed",
                    "waitlist consumer read allowed", "relay consumer read denied",
                    "waitlist producer write denied", "monitor topic describe allowed", "anonymous SSL denied"))));
        }
    }

    private KafkaRelayConfiguration.ClientSettings settings(Path fixture, String role) throws Exception {
        Properties values = new Properties();
        try (var input = Files.newInputStream(fixture.resolve("client-" + role + ".properties"))) {
            values.load(input);
        }
        return new KafkaRelayConfiguration.ClientSettings("localhost:59094", "SASL_SSL", "PLAIN",
            values.getProperty("sasl.jaas.config"), fixture.resolve("truststore.p12").toString(),
            Files.readString(fixture.resolve("broker.credentials")));
    }

    private Properties consumer(Path fixture, String role, String group) throws Exception {
        Properties values = new Properties();
        values.putAll(settings(fixture, role).properties());
        values.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        values.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        values.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        values.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        values.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return values;
    }
}
