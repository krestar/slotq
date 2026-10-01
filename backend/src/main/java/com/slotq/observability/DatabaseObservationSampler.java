package com.slotq.observability;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dedicated connection and thread: scrape only reads volatile snapshots, never JDBC. */
@Component
@ConditionalOnProperty(name = "slotq.observability.database.enabled", havingValue = "true")
public final class DatabaseObservationSampler {
    private final HikariDataSource source;
    private final MeterRegistry registry;
    private final ScheduledExecutorService scheduler;
    private final DatabaseObservation observation = new DatabaseObservation();
    private volatile DatabaseObservation.Snapshot events;
    private volatile DatabaseObservation.Snapshot locks;
    private volatile boolean eventsHealthy;
    private volatile boolean locksHealthy;
    private final Duration staleAfter;
    private final String kafkaConsumerId;
    private final String consumerTransport;

    public DatabaseObservationSampler(MeterRegistry registry,
            @Value("${slotq.observability.database.jdbc-url}") String url,
            @Value("${slotq.observability.database.username}") String username,
            @Value("${slotq.observability.database.password}") String password,
            @Value("${slotq.observability.database.interval:PT15S}") Duration interval,
            @Value("${slotq.observability.database.stale-after:PT45S}") Duration staleAfter,
            @Value("${slotq.events.runtime-role:product}") String runtimeRole,
            @Value("${slotq.events.delivery.consumer-id:}") String consumerId,
            @Value("${slotq.events.delivery.transport:DB_DIRECT}") String transport) {
        if (interval.compareTo(Duration.ofSeconds(1)) < 0 || staleAfter.compareTo(interval) <= 0) {
            throw new IllegalArgumentException("Observation interval/staleness bounds are invalid");
        }
        this.registry = registry;
        this.staleAfter = staleAfter;
        this.kafkaConsumerId = runtimeRole.equals("consumer") ? consumerId : null;
        if (!java.util.Set.of("DB_DIRECT", "KAFKA").contains(transport))
            throw new IllegalArgumentException("Unknown observation transport");
        this.consumerTransport = transport.equals("KAFKA") ? "kafka" : "db";
        if (kafkaConsumerId != null && !java.util.Set.of("waitlist.promotion",
                "operations.event-observation").contains(kafkaConsumerId))
            throw new IllegalArgumentException("Kafka observation requires an approved logical consumer");
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setPoolName("telemetry");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(1000);
        config.setValidationTimeout(1000);
        config.setInitializationFailTimeout(-1);
        config.setReadOnly(true);
        config.addDataSourceProperty("connectTimeout", "1000");
        config.addDataSourceProperty("socketTimeout", "1500");
        config.addDataSourceProperty("connectionTimeZone", "UTC");
        config.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        this.source = new HikariDataSource(config);
        if (kafkaConsumerId == null) {
            for (String sample : new String[] {"events", "locks"}) {
                Gauge.builder("slotq.observation.sample.healthy", this, sampler -> sampler.healthy(sample) ? 1 : 0)
                        .tag("sample", sample).register(registry);
                Gauge.builder("slotq.observation.sample.age.seconds", this, sampler -> sampler.age(sample))
                        .tag("sample", sample).register(registry);
            }
        } else {
            var tags = Tags.of("transport", consumerTransport, "runtime_role", "observer",
                "logical_consumer", kafkaConsumerId);
            Gauge.builder("slotq." + consumerTransport + ".delivery.sample.healthy", this, sampler -> sampler.healthy("events") ? 1 : 0)
                .tags(tags).register(registry);
            Gauge.builder("slotq." + consumerTransport + ".delivery.sample.age.seconds", this, sampler -> sampler.age("events"))
                .tags(tags).register(registry);
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "slotq-database-observation");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::sample, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void sample() {
        if (kafkaConsumerId != null) {
            try (Connection connection = source.getConnection()) {
                var next = observation.readKafkaDeliveries(connection, kafkaConsumerId);
                registerKafka(next);
                events = next;
                eventsHealthy = true;
            } catch (Exception ignored) {
                eventsHealthy = false;
            }
            return;
        }
        try (Connection connection = source.getConnection()) {
            var next = observation.read(connection);
            register(next, "events");
            events = next;
            eventsHealthy = true;
        } catch (Exception ignored) {
            // Credential/SQL/driver messages must not leak into logs or label values.
            eventsHealthy = false;
        }
        try (Connection connection = source.getConnection()) {
            var next = observation.readLocks(connection);
            register(next, "locks");
            locks = next;
            locksHealthy = true;
        } catch (Exception ignored) {
            locksHealthy = false;
        }
    }

    private void registerKafka(DatabaseObservation.Snapshot snapshot) {
        for (var key : snapshot.values().keySet()) {
            var tags = Tags.of("transport", consumerTransport, "runtime_role", "observer",
                "logical_consumer", kafkaConsumerId);
            if (!key.dimension().isEmpty()) tags = tags.and(key.dimension(), key.value());
            Gauge.builder("slotq." + consumerTransport + "." + key.metric(), this, sampler -> sampler.value("events", key))
                .tags(tags).register(registry);
        }
    }

    private void register(DatabaseObservation.Snapshot snapshot, String sample) {
        for (var key : snapshot.values().keySet()) {
            var tags = Tags.of("transport", "db", "runtime_role", "observer");
            if (key.metric().startsWith("delivery.") || key.metric().startsWith("business.") || key.metric().startsWith("promotion.")) {
                tags = tags.and("logical_consumer", "waitlist.promotion");
            }
            if (!key.dimension().isEmpty()) tags = tags.and(key.dimension(), key.value());
            Gauge.builder("slotq.db." + key.metric(), this, sampler -> sampler.value(sample, key))
                    .tags(tags).register(registry);
        }
    }

    private double value(String sample, DatabaseObservation.Key key) {
        if (!healthy(sample)) return Double.NaN;
        var snapshot = snapshot(sample);
        return snapshot == null ? Double.NaN : snapshot.values().getOrDefault(key, Double.NaN);
    }
    private boolean healthy(String sample) {
        return (sample.equals("events") ? eventsHealthy : locksHealthy) && age(sample) <= staleAfter.toMillis() / 1000.0;
    }
    private double age(String sample) {
        var snapshot = snapshot(sample);
        return snapshot == null ? Double.NaN : Math.max(0, Duration.between(snapshot.observedAt(), Instant.now()).toMillis() / 1000.0);
    }
    private DatabaseObservation.Snapshot snapshot(String sample) { return sample.equals("events") ? events : locks; }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
        source.close();
    }
}
