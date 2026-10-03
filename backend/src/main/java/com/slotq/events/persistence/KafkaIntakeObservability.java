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
    private static final Duration LAG_STALE_AFTER = Duration.ofSeconds(45);
    private final JdbcKafkaIntakeStore store;
    private final MeterRegistry registry;
    private final KafkaConsumerCatalog.ConsumerDefinition consumer;
    private final String topic;
    private final Set<Integer> allowedPartitions;
    private final Map<TopicPartition, AtomicLong> lag = new HashMap<>();
    private final AtomicLong lagHealthy = new AtomicLong();
    private final AtomicLong lagTruncated = new AtomicLong();
    private final Map<String, AtomicLong> states = new HashMap<>();
    private final Timer delay;
    private volatile long lastLagSampleNanos;

    KafkaIntakeObservability(JdbcKafkaIntakeStore store, MeterRegistry registry,
                             KafkaConsumerCatalog.ConsumerDefinition consumer, String topic,
                             Set<Integer> allowedPartitions) {
        this.store = store; this.registry = registry; this.consumer = consumer; this.topic = topic;
        this.allowedPartitions = Set.copyOf(allowedPartitions);
        String[] tags = tags();
        this.delay = Timer.builder("slotq.kafka.durable.intake.observed.delay")
            .publishPercentileHistogram().tags(tags).register(registry);
        Gauge.builder("slotq.kafka.lag.sample.healthy", this, signal -> signal.lagCurrent() ? 1 : 0)
            .tags(tags).register(registry);
        Gauge.builder("slotq.kafka.lag.sample.truncated", lagTruncated, AtomicLong::get).tags(tags).register(registry);
        for (String state : Set.of("ready", "degraded", "stopped")) {
            AtomicLong value = new AtomicLong(); states.put(state, value);
            Gauge.builder("slotq.kafka.runtime.state", value, AtomicLong::get)
                .tags(tags).tag("state", state).register(registry);
        }
        state("stopped");
    }

    void state(String active) {
        if (!active.equals("ready")) lagHealthy.set(0);
        states.forEach((name, value) -> value.set(name.equals(active) ? 1 : 0));
    }

    void rebalance(String outcome) {
        lagHealthy.set(0);
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
        // Invalidate before broker I/O, including a blocked or failed observation.
        lagHealthy.set(0);
        lag.values().forEach(value -> value.set(-1));
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
                    AtomicLong created = new AtomicLong(-1);
                    Gauge.builder("slotq.kafka.consumer.lag.records", created,
                            value -> lagCurrent() && value.get() >= 0 ? value.get() : Double.NaN)
                        .tags(tags()).tag("consumer_group", consumer.groupId()).tag("topic", topic)
                        .tag("partition", Integer.toString(key.partition())).register(registry);
                    return created;
                });
                holder.set(position == null ? 0 : end - position.offset());
            }
            if (complete) {
                lastLagSampleNanos = System.nanoTime();
                lagHealthy.set(1);
            }
        } catch (RuntimeException unavailable) {
            lagHealthy.set(0);
        }
    }

    private boolean lagCurrent() {
        return lagHealthy.get() == 1 && System.nanoTime() - lastLagSampleNanos <= LAG_STALE_AFTER.toNanos();
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
