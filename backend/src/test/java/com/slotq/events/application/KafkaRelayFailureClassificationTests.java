package com.slotq.events.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.slotq.observability.ProductTelemetry;
import com.slotq.tenancy.domain.TenantId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaRelayFailureClassificationTests {
    @Test void springKafkaWrappedAuthorizationErrorBecomesTerminalStableCode() {
        var ledger = mock(KafkaPublicationLedger.class);
        @SuppressWarnings("unchecked") KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        var mapper = mock(KafkaPublicationFamily.class);
        var policy = new KafkaPublicationPolicy(3, Duration.ofSeconds(30), Duration.ofSeconds(2), 10,
            List.of(Duration.ZERO, Duration.ZERO));
        var eventId = EventId.newId();
        var tenant = new TenantId(UUID.randomUUID());
        var stored = new StoredEvent(new EventEnvelope(eventId, tenant, "SlotInventory", UUID.randomUUID(),
            "waitlist.promotion-requested", 1, Instant.now(), "{}"), 1, Instant.now());
        var claim = new KafkaPublicationLedger.Claim(
            new KafkaPublicationLedger.Key(tenant.value(), eventId.value(), "slotq.waitlist.events.v1"), 1, 1);
        when(ledger.load(claim)).thenReturn(new KafkaPublicationLedger.Publication(stored, ProductTelemetry.Origin.EMPTY));
        when(mapper.encode(stored, ProductTelemetry.Origin.EMPTY))
            .thenReturn(new KafkaPublicationFamily.Message("key", "body"));
        var denied = new CompletableFuture<org.springframework.kafka.support.SendResult<String, String>>();
        denied.completeExceptionally(new RuntimeException(new TopicAuthorizationException("denied")));
        when(template.send(anyString(), anyString(), anyString())).thenReturn(denied);
        new KafkaRelayWorker(ledger, template, mapper, policy, new SimpleMeterRegistry(),
            "slotq.waitlist.events.v1").publish(claim);
        verify(ledger).failed(eq(claim), eq("AUTHORIZATION"), eq(false), eq(policy));
    }
}
