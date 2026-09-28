package com.slotq.events.application;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
@ConditionalOnProperty(name = "slotq.events.kafka.relay-enabled", havingValue = "true")
public class KafkaRelayConfiguration {
    @Bean
    public KafkaTemplate<String, String> publicationTemplate(ClientSettings settings) {
        return template(settings.properties());
    }

    @Bean(destroyMethod = "close")
    AdminClient publicationAdmin(ClientSettings settings) { return AdminClient.create(settings.properties()); }

    static KafkaTemplate<String, String> template(Map<String, Object> connection) {
        Map<String, Object> config = new HashMap<>();
        config.putAll(connection);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        config.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 1_048_576);
        // No broker transaction is claimed to encompass the MySQL publication mark.
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    @org.springframework.stereotype.Component
    @ConditionalOnProperty(name = "slotq.events.kafka.relay-enabled", havingValue = "true")
    public record ClientSettings(Map<String, Object> properties) {
        @org.springframework.beans.factory.annotation.Autowired
        public ClientSettings(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrap,
            @Value("${slotq.events.kafka.security-protocol:PLAINTEXT}") String protocol,
            @Value("${slotq.events.kafka.sasl-mechanism:}") String mechanism,
            @Value("${slotq.events.kafka.sasl-jaas-config:}") String jaas,
            @Value("${slotq.events.kafka.ssl-truststore-location:}") String truststore,
            @Value("${slotq.events.kafka.ssl-truststore-password:}") String truststorePassword) {
            this(properties(bootstrap, protocol, mechanism, jaas, truststore, truststorePassword));
        }

        private static Map<String, Object> properties(String bootstrap, String protocol, String mechanism,
                                                      String jaas, String truststore, String truststorePassword) {
            if (bootstrap == null || bootstrap.isBlank()) throw new IllegalArgumentException("Kafka bootstrap required");
            Map<String, Object> config = new HashMap<>();
            config.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            config.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol);
            if (protocol.equals("PLAINTEXT")) {
                for (String endpoint : bootstrap.split(",")) {
                    if (!endpoint.trim().matches("(?:localhost|127\\.0\\.0\\.1):[0-9]{1,5}"))
                        throw new IllegalArgumentException("Anonymous Kafka is limited to loopback development");
                }
            } else if (protocol.equals("SASL_SSL")) {
                if (mechanism.isBlank() || jaas.isBlank() || truststore.isBlank() || truststorePassword.isBlank())
                    throw new IllegalArgumentException("SASL_SSL Kafka credentials and truststore required");
                config.put(SaslConfigs.SASL_MECHANISM, mechanism);
                config.put(SaslConfigs.SASL_JAAS_CONFIG, jaas);
                config.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, truststore);
                config.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, truststorePassword);
                config.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PKCS12");
            } else {
                throw new IllegalArgumentException("Unsupported Kafka relay security protocol");
            }
            return Map.copyOf(config);
        }
    }
}
