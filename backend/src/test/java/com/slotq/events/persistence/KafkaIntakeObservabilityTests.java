package com.slotq.events.persistence;

import java.util.*;
import com.slotq.events.application.KafkaConsumerCatalog.ConsumerDefinition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaIntakeObservabilityTests {
    private final JdbcKafkaIntakeStore store=mock(JdbcKafkaIntakeStore.class);
    @SuppressWarnings("unchecked") private final KafkaConsumer<byte[],byte[]> runtime=mock(KafkaConsumer.class);
    private final TopicPartition empty=new TopicPartition("slotq.waitlist.events.v1",0);
    private final TopicPartition active=new TopicPartition("slotq.waitlist.events.v1",1);
    private final Set<TopicPartition> assignment=Set.of(empty,active);
    @Test void realEmptyPartitionNeedsNoCommittedOffsetToBeKnownHealthyZeroLag() {
        var registry=new SimpleMeterRegistry();try {
            when(runtime.assignment()).thenReturn(assignment);
            when(runtime.endOffsets(assignment)).thenReturn(Map.of(empty,0L,active,7L));
            when(runtime.committed(assignment)).thenReturn(Map.of(active,new OffsetAndMetadata(5)));
            observation(registry).sample(runtime);
            assertThat(registry.get("slotq.kafka.lag.sample.healthy").gauge().value()).isEqualTo(1);
            assertThat(registry.get("slotq.kafka.consumer.lag.records").tag("partition","0").gauge().value()).isZero();
            assertThat(registry.get("slotq.kafka.consumer.lag.records").tag("partition","1").gauge().value()).isEqualTo(2);
            verify(runtime,never()).position(any(TopicPartition.class));
            verify(runtime,never()).seek(any(TopicPartition.class),anyLong());
        } finally {registry.close();}
    }
    @Test void uncommittedNonemptyPartitionAndMissingBrokerSampleStayUnknown() {
        for(Map<TopicPartition,Long> ends:List.of(Map.of(empty,1L,active,7L),Map.of(active,7L))){var registry=new SimpleMeterRegistry();try {
            when(runtime.assignment()).thenReturn(assignment);when(runtime.endOffsets(assignment)).thenReturn(ends);
            when(runtime.committed(assignment)).thenReturn(Map.of(active,new OffsetAndMetadata(5)));
            observation(registry).sample(runtime);
            assertThat(registry.get("slotq.kafka.lag.sample.healthy").gauge().value()).isZero();
            assertThat(registry.find("slotq.kafka.consumer.lag.records").tag("partition","0").gauge()).isNull();
        } finally {registry.close();}}
    }
    @Test void brokerFailureAndLostAssignmentCannotPublishHealthyZero() {
        var registry=new SimpleMeterRegistry();try {
            when(runtime.assignment()).thenReturn(assignment);when(runtime.endOffsets(assignment)).thenThrow(new IllegalStateException("unavailable"));
            var observation=observation(registry);observation.sample(runtime);
            assertThat(registry.get("slotq.kafka.lag.sample.healthy").gauge().value()).isZero();
            when(runtime.assignment()).thenReturn(Set.of());observation.sample(runtime);
            assertThat(registry.get("slotq.kafka.lag.sample.healthy").gauge().value()).isZero();
        } finally {registry.close();}
    }
    private KafkaIntakeObservability observation(SimpleMeterRegistry registry){return new KafkaIntakeObservability(store,registry,new ConsumerDefinition("waitlist.promotion","slotq.waitlist.promotion.v1",List.of()),empty.topic(),Set.of(0,1,2));}
}
