package com.slotq.events.application;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaRuntimeGuardTests {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final KafkaPublicationFamily family = mock(KafkaPublicationFamily.class);

    @Test void kafkaBusinessActivationIsClosedUntilDurableIntakeExists() {
        var guard = guard("product", false, true, true);
        assertThatThrownBy(() -> guard.run(null)).hasMessageContaining("#108 durable intake");
    }

    @Test void relayRejectsLocalExecutionAndUnknownRole() {
        when(family.localConsumerEnabled()).thenReturn(true);
        assertThatThrownBy(() -> guard("relay", true, false, false).run(null))
            .hasMessageContaining("cannot execute Product");
        assertThatThrownBy(() -> guard("unexpected", false, false, false).run(null))
            .hasMessageContaining("Unknown event runtime role");
    }

    @Test void productRejectsKafkaAssignmentWithNoIntake() {
        when(family.localConsumerEnabled()).thenReturn(true);
        when(family.consumerId()).thenReturn("test.consumer");
        when(db.queryForList(anyString(), eq("test.consumer"))).thenReturn(List.of(
            Map.of("event_type", "test.changed", "schema_version", 1,
                "transport", "KAFKA", "authority_epoch", 2)));
        assertThatThrownBy(() -> guard("product", false, true, false).run(null))
            .hasMessageContaining("Incompatible publication transport authority");
    }

    @Test void relayRequiresExactWaitlistRoutesButDoesNotInspectOtherConsumers() {
        when(family.consumerId()).thenReturn("test.consumer");
        when(family.routes()).thenReturn(List.of(new ConsumerRoute("test.consumer", "first.changed", 1),
            new ConsumerRoute("test.consumer", "second.changed", 1)));
        when(db.queryForList(anyString(), eq("test.consumer"))).thenReturn(List.of(
            Map.of("event_type", "first.changed", "schema_version", 1,
                "transport", "DB_DIRECT", "authority_epoch", 1)));
        assertThatThrownBy(() -> guard("relay", true, false, false).run(null))
            .hasMessageContaining("exact durable publication routes");
    }

    @Test void relayAcceptsOnlyTheConfiguredFamilyRoutesWithDbAuthority() {
        when(family.consumerId()).thenReturn("test.consumer");
        when(family.routes()).thenReturn(List.of(new ConsumerRoute("test.consumer", "first.changed", 1),
            new ConsumerRoute("test.consumer", "second.changed", 1)));
        when(db.queryForList(anyString(), eq("test.consumer"))).thenReturn(List.of(
            Map.of("event_type", "first.changed", "schema_version", 1,
                "transport", "DB_DIRECT", "authority_epoch", 1),
            Map.of("event_type", "second.changed", "schema_version", 1,
                "transport", "DB_DIRECT", "authority_epoch", 1)));
        var guard = guard("relay", true, false, false);
        guard.run(null);
        assertThat(guard.relayReady()).isTrue();
    }

    @Test void nonLoopbackAnonymousAndIncompleteTlsCredentialsAreRejected() {
        assertThatThrownBy(() -> new KafkaRelayConfiguration.ClientSettings(
            "broker.example:9092", "PLAINTEXT", "", "", "", ""))
            .hasMessageContaining("loopback");
        assertThatThrownBy(() -> new KafkaRelayConfiguration.ClientSettings(
            "broker.example:9093", "SASL_SSL", "PLAIN", "", "", ""))
            .hasMessageContaining("credentials and truststore");
    }

    private KafkaRuntimeGuard guard(String role, boolean relayEnabled, boolean deliveryEnabled,
                                    boolean kafkaBusinessEnabled) {
        return new KafkaRuntimeGuard(db, family, role, relayEnabled, deliveryEnabled,
            kafkaBusinessEnabled, "none");
    }
}
