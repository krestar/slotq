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

    @Test void kafkaBusinessActivationRequiresApprovedRoutes() {
        var guard = guard("product", false, false, true);
        assertThatThrownBy(() -> guard.run(null)).hasMessageContaining("approved M4 routes");
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
            .hasMessageContaining("exact durable publication routes and authority");
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

    @Test void productCannotRunAnotherLogicalConsumerOrKafkaDbScheduler() {
        when(family.consumerId()).thenReturn("waitlist.promotion");
        var foreignScope = new DeliveryExecutionScope("operations.event-observation", "DB_DIRECT", 1);
        var wrongConsumer = new KafkaRuntimeGuard(db, family, null, foreignScope,
            "product", false, true, false, false, false, "none");
        assertThatThrownBy(() -> wrongConsumer.run(null)).hasMessageContaining("another consumer");

        when(db.queryForList(anyString())).thenReturn(List.of(Map.of(
            "to_transport", "KAFKA", "authority_epoch", 2L, "phase", "READY")));
        var kafkaProductWithExecutor = new KafkaRuntimeGuard(db, family, null,
            new DeliveryExecutionScope("waitlist.promotion", "DB_DIRECT", 2),
            "product", false, true, false, true, false, "none");
        assertThatThrownBy(() -> kafkaProductWithExecutor.run(null))
            .hasMessageContaining("cannot run DB direct executor");
    }

    @Test void isolatedServletRequiresExplicitMetricsOnlyModeAndKeepsRoleGuards() {
        when(family.consumerId()).thenReturn("test.consumer");
        when(family.routes()).thenReturn(List.of(new ConsumerRoute("test.consumer","first.changed",1)));
        when(db.queryForList(anyString(),eq("test.consumer"))).thenReturn(List.of(Map.of(
            "event_type","first.changed","schema_version",1,"transport","DB_DIRECT","authority_epoch",1)));
        var unsafe=new KafkaRuntimeGuard(db,family,null,null,"relay",true,false,false,false,false,"servlet");
        assertThatThrownBy(()->unsafe.run(null)).hasMessageContaining("cannot execute Product");
        var observed=new KafkaRuntimeGuard(db,family,null,null,"relay",true,false,false,false,false,"servlet",true);
        observed.run(null);assertThat(observed.relayReady()).isTrue();
        var mixed=new KafkaRuntimeGuard(db,family,null,null,"relay",true,true,false,false,false,"servlet",true);
        assertThatThrownBy(()->mixed.run(null)).hasMessageContaining("cannot execute Product");
        var product=new KafkaRuntimeGuard(db,family,null,null,"product",false,false,false,false,false,"servlet",true);
        assertThatThrownBy(()->product.run(null)).hasMessageContaining("limited to isolated");
    }

    @Test void consumerCannotStartWithStaleEpochOrIncompleteCutover() {
        when(family.consumerId()).thenReturn("waitlist.promotion");
        KafkaConsumerCatalog catalog = () -> List.of(new KafkaConsumerCatalog.ConsumerDefinition(
            "operations.event-observation", "slotq.operations.event-observation.v1",
            List.of(new ConsumerRoute("operations.event-observation", "test.changed", 1))));
        when(db.queryForList(anyString())).thenReturn(List.of(Map.of(
            "to_transport", "KAFKA", "authority_epoch", 2L, "phase", "READY")));
        var stale = new KafkaRuntimeGuard(db, family, catalog,
            new DeliveryExecutionScope("operations.event-observation", "KAFKA", 1),
            "consumer", false, true, true, false, true, "none");
        assertThatThrownBy(() -> stale.run(null)).hasMessageContaining("stale transport authority");
        assertThat(stale.consumerReady()).isFalse();

        when(db.queryForList(anyString())).thenReturn(List.of(Map.of(
            "to_transport", "KAFKA", "authority_epoch", 2L, "phase", "SCANNING")));
        var notReady = new KafkaRuntimeGuard(db, family, catalog,
            new DeliveryExecutionScope("operations.event-observation", "KAFKA", 2),
            "consumer", false, true, true, false, true, "none");
        assertThatThrownBy(() -> notReady.run(null)).hasMessageContaining("scan is incomplete");
        assertThat(notReady.consumerReady()).isFalse();
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
