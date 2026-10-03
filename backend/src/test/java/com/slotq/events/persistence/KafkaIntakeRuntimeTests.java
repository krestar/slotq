package com.slotq.events.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.slotq.events.application.KafkaConsumerCatalog;
import com.slotq.events.application.KafkaRuntimeGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaIntakeRuntimeTests {
    enum Failure { POLL, INTAKE, OFFSET_COMMIT, SAMPLE_ASSIGNMENT }

    @ParameterizedTest @EnumSource(Failure.class)
    void healthySampleThenRuntimeFailureHaltsAndInvalidatesLag(Failure failure) {
        var store = mock(JdbcKafkaIntakeStore.class);
        var guard = mock(KafkaRuntimeGuard.class);
        when(guard.consumerReady()).thenReturn(true);
        var definition = new KafkaConsumerCatalog.ConsumerDefinition("waitlist.promotion",
            "slotq.waitlist.promotion.v1", List.of());
        KafkaConsumerCatalog catalog = () -> List.of(definition);
        var registry = new SimpleMeterRegistry();
        var intake = new KafkaIntakeRuntime(store, guard, catalog, registry, "consumer", definition.consumerId(),
            "KAFKA", 2, "slotq.waitlist.events.v1", "localhost:9092", "PLAINTEXT", "", "", "", "", "0,1,2");
        @SuppressWarnings("unchecked") var broker = (KafkaConsumer<byte[], byte[]>) mock(KafkaConsumer.class);
        var partition = new TopicPartition("slotq.waitlist.events.v1", 0);
        ReflectionTestUtils.setField(intake, "consumer", broker);
        when(broker.assignment()).thenReturn(Set.of(partition));
        when(broker.endOffsets(Set.of(partition))).thenReturn(Map.of(partition, 0L));
        when(broker.committed(Set.of(partition))).thenReturn(Map.of());
        when(broker.poll(any(Duration.class))).thenReturn(ConsumerRecords.empty());
        try {
            intake.runCycle();
            assertThat(registry.get("slotq.kafka.lag.sample.healthy").gauge().value()).isEqualTo(1);
            doAnswer(call -> {
                assertThat(registry.get("slotq.kafka.lag.sample.healthy").gauge().value()).isZero();
                assertThat(registry.get("slotq.kafka.runtime.state").tag("state", "degraded").gauge().value()).isEqualTo(1);
                return null;
            }).when(broker).close(any(Duration.class));
            var record = new ConsumerRecord<byte[], byte[]>(partition.topic(), 0, 0, new byte[0], new byte[0]);
            if (failure == Failure.POLL) when(broker.poll(any(Duration.class))).thenThrow(new IllegalStateException("poll"));
            else if (failure == Failure.SAMPLE_ASSIGNMENT) when(broker.assignment()).thenThrow(new IllegalStateException("sample"));
            else {
                when(broker.poll(any(Duration.class))).thenReturn(new ConsumerRecords<>(Map.of(partition, List.of(record)), Map.of()));
                if (failure == Failure.INTAKE) when(store.intake(record, definition, 2)).thenThrow(new IllegalStateException("intake"));
                else {
                    when(store.intake(record, definition, 2)).thenReturn(JdbcKafkaIntakeStore.Outcome.TARGET);
                    doThrow(new IllegalStateException("commit")).when(broker).commitSync(anyMap(), any(Duration.class));
                }
            }
            assertThatThrownBy(intake::runCycle).isInstanceOf(IllegalStateException.class);
            assertThat(registry.get("slotq.kafka.runtime.state").tag("state", "degraded").gauge().value()).isEqualTo(1);
            assertThat(registry.get("slotq.kafka.lag.sample.healthy").gauge().value()).isZero();
            assertThat(registry.get("slotq.kafka.consumer.lag.records").gauge().value()).isNaN();
            clearInvocations(store, broker);
            intake.runCycle();
            verifyNoInteractions(store, broker); // Metric availability never restarts a halted runtime.
        } finally { intake.close(); registry.close(); }
    }
}
