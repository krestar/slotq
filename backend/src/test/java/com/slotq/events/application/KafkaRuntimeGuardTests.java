package com.slotq.events.application;

import com.slotq.events.persistence.JdbcKafkaPublicationLedger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaRuntimeGuardTests {
    private final JdbcTemplate db = mock(JdbcTemplate.class);

    @Test void kafkaBusinessActivationIsClosedUntilDurableIntakeExists() {
        var guard = new KafkaRuntimeGuard(db, "product", false, true, true, true, true);
        assertThatThrownBy(() -> guard.run(null)).hasMessageContaining("#108 durable intake");
    }

    @Test void relayRejectsLocalExecutionAndUnknownRole() {
        assertThatThrownBy(() -> new KafkaRuntimeGuard(db, "relay", true, true, false, false, false).run(null))
            .hasMessageContaining("cannot execute Product");
        assertThatThrownBy(() -> new KafkaRuntimeGuard(db, "unexpected", false, false, false, false, false).run(null))
            .hasMessageContaining("Unknown event runtime role");
    }

    @Test void productRejectsKafkaAssignmentWithNoIntake() {
        when(db.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        assertThatThrownBy(() -> new KafkaRuntimeGuard(db, "product", false, true, true, true, false).run(null))
            .hasMessageContaining("Incompatible Waitlist transport authority");
    }

    @Test void relayRequiresExactWaitlistRoutesButDoesNotInspectOtherConsumers() {
        when(db.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 2);
        assertThatThrownBy(() -> new KafkaRuntimeGuard(db, "relay", true, false, false, false, false).run(null))
            .hasMessageContaining("two exact durable Waitlist routes");
    }

    @Test void nonLoopbackAnonymousAndIncompleteTlsCredentialsAreRejected() {
        assertThatThrownBy(() -> new KafkaRelayConfiguration.ClientSettings(
            "broker.example:9092", "PLAINTEXT", "", "", "", ""))
            .hasMessageContaining("loopback");
        assertThatThrownBy(() -> new KafkaRelayConfiguration.ClientSettings(
            "broker.example:9093", "SASL_SSL", "PLAIN", "", "", ""))
            .hasMessageContaining("credentials and truststore");
    }
}
