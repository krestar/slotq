package com.slotq.events.persistence;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.slotq.events.application.KafkaConsumerCatalog;
import com.slotq.events.persistence.JdbcKafkaIntakeStore.Outcome;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;

/** Bounded, advisory consumer signals. Metrics never grant offset or business authority. */
final class KafkaIntakeObservability {
    private static final Set<String> FAILURE_CODES = Set.of("MALFORMED_WIRE", "UNKNOWN_ORIGINAL",
        "IDENTITY_CORRUPTION", "CANONICAL_CORRUPTION", "REGISTRATION_CORRUPTION");
    private final JdbcKafkaIntakeStore store;
    private final MeterRegistry registry;
    private final KafkaConsumerCatalog.ConsumerDefinition consumer;
    private final String topic;
    private final Set<Integer> allowedPartitions;
    private final Map<TopicPartition, AtomicLong> lag = new HashMap<>();
    private final Map<String, AtomicLong> quarantine = new HashMap<>();
    private final AtomicLong quarantineOldest = new AtomicLong();
    private final AtomicLong lagHealthy = new AtomicLong();
    private final AtomicLong lagTruncated = new AtomicLong();
    private final Map<String, AtomicLong> states = new HashMap<>();
    private final Timer delay;
    private long lastQuarantineSampleMillis;

    KafkaIntakeObservability(JdbcKafkaIntakeStore store, MeterRegistry registry,
                             KafkaConsumerCatalog.ConsumerDefinition consumer, String topic,
                             Set<Integer> allowedPartitions) {
        this.store = store; this.registry = registry; this.consumer = consumer; this.topic = topic;
        this.allowedPartitions = Set.copyOf(allowedPartitions);
        String[] tags = tags();
        this.delay = Timer.builder("slotq.kafka.durable.intake.observed.delay")
            .publishPercentileHistogram().tags(tags).register(registry);
        Gauge.builder("slotq.kafka.lag.sample.healthy", lagHealthy, AtomicLong::get).tags(tags).register(registry);
        Gauge.builder("slotq.kafka.lag.sample.truncated", lagTruncated, AtomicLong::get).tags(tags).register(registry);
        Gauge.builder("slotq.kafka.quarantine.oldest.age.seconds", quarantineOldest, AtomicLong::get)
            .tags(tags).register(registry);
        for (String failure : FAILURE_CODES) {
            AtomicLong count = new AtomicLong(); quarantine.put(failure, count);
            Gauge.builder("slotq.kafka.quarantine.records", count, AtomicLong::get)
                .tags(tags).tag("failure_code", failure).register(registry);
        }
        for (String state : Set.of("ready", "degraded", "stopped")) {
            AtomicLong value = new AtomicLong(); states.put(state, value);
            Gauge.builder("slotq.kafka.runtime.state", value, AtomicLong::get)
                .tags(tags).tag("state", state).register(registry);
        }
        state("stopped");
    }

    void state(String active) {
        states.forEach((name, value) -> value.set(name.equals(active) ? 1 : 0));
    }

    void rebalance(String outcome) {
        registry.counter("slotq.kafka.consumer.rebalance", append(tags(), "outcome", outcome)).increment();
    }

    void intakeFailure() {
        registry.counter("slotq.kafka.intake", append(tags(), "outcome", "failure")).increment();
    }

    void afterIntake(ConsumerRecord<byte[], byte[]> record, Outcome outcome) {
        if (outcome == Outcome.TARGET || outcome == Outcome.REUSED_TARGET) {
            registry.counter("slotq.kafka.intake", append(tags(), "outcome", "success")).increment();
            if (outcome == Outcome.REUSED_TARGET) return;
            try {
                store.firstTargetDelay(record, consumer).ifPresent(observation -> {
                    Duration duration = observation.duration();
                    if (duration.isNegative()) invalidDelay("clock_order");
                    else delay.record(duration);
                });
            } catch (RuntimeException observationUnavailable) {
                // Fresh DB observation is best effort; the target and offset handoff are already durable.
            }
        } else if (outcome == Outcome.QUARANTINED) intakeFailure();
    }

    private void invalidDelay(String reason) {
        registry.counter("slotq.kafka.intake.delay.invalid", append(tags(), "reason", reason)).increment();
    }

    void sample(KafkaConsumer<byte[], byte[]> runtime) {
        var partitions = runtime.assignment();
        if (partitions.isEmpty()) { lagHealthy.set(0); return; }
        if (partitions.size() > 64 || partitions.stream().anyMatch(p -> !p.topic().equals(topic)
            || !allowedPartitions.contains(p.partition()))) {
            lagHealthy.set(0); lagTruncated.set(1); return;
        }
        lagTruncated.set(0);
        try {
            var ends = runtime.endOffsets(partitions);
            var committed = runtime.committed(partitions);
            boolean complete = true;
            for (TopicPartition partition : partitions) {
                var position = committed.get(partition);
                Long end = ends.get(partition);
                // An observed absolute log end 0 proves this partition has never contained a
                // record. Its known lag is zero even before a first offset commit. Nonempty
                // partitions without durable committed provenance remain unknown.
                if (end == null || end < 0 || (position == null && end != 0)
                    || (position != null && position.offset() > end)) { complete = false; continue; }
                AtomicLong holder = lag.computeIfAbsent(partition, key -> {
                    AtomicLong created = new AtomicLong();
                    Gauge.builder("slotq.kafka.consumer.lag.records", created, AtomicLong::get)
                        .tags(tags()).tag("consumer_group", consumer.groupId()).tag("topic", topic)
                        .tag("partition", Integer.toString(key.partition())).register(registry);
                    return created;
                });
                holder.set(position == null ? 0 : end - position.offset());
            }
            lagHealthy.set(complete ? 1 : 0);
        } catch (RuntimeException unavailable) {
            lagHealthy.set(0);
        }
        if (System.currentTimeMillis() - lastQuarantineSampleMillis >= 30_000) {
            lastQuarantineSampleMillis = System.currentTimeMillis();
            try {
                quarantine.values().forEach(value -> value.set(0));
                long oldest = 0;
                for (var item : store.quarantineCounts(consumer.consumerId())) {
                    AtomicLong count = quarantine.get(item.failureCode());
                    if (count != null) count.set(item.count());
                    oldest = Math.max(oldest, item.oldestAgeSeconds());
                }
                quarantineOldest.set(oldest);
            } catch (RuntimeException unavailable) {
                // Keep the last sample; lag health already signals DB/Kafka visibility separately.
            }
        }
    }

    private String[] tags() {
        return new String[] {"transport", "kafka", "runtime_role", "consumer",
            "logical_consumer", consumer.consumerId()};
    }

    private static String[] append(String[] original, String name, String value) {
        String[] result = java.util.Arrays.copyOf(original, original.length + 2);
        result[original.length] = name; result[original.length + 1] = value;
        return result;
    }
}
