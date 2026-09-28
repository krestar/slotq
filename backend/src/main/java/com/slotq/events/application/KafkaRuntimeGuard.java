package com.slotq.events.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** #107 permits DB execution and an independent relay, but no Kafka business intake yet. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class KafkaRuntimeGuard implements ApplicationRunner {
    private final JdbcTemplate db;
    private final KafkaPublicationFamily family;
    private final String role;
    private final boolean relayEnabled;
    private final boolean deliveryEnabled;
    private final boolean kafkaBusinessEnabled;
    private final String webType;
    private volatile boolean relayReady;

    @org.springframework.beans.factory.annotation.Autowired
    public KafkaRuntimeGuard(JdbcTemplate db, KafkaPublicationFamily family,
        @Value("${slotq.events.runtime-role:product}") String role,
        @Value("${slotq.events.kafka.relay-enabled:false}") boolean relayEnabled,
        @Value("${slotq.events.delivery.scheduler-enabled:false}") boolean deliveryEnabled,
        @Value("${slotq.events.kafka.business-enabled:false}") boolean kafkaBusinessEnabled,
        @Value("${spring.main.web-application-type:servlet}") String webType) {
        this.db = db; this.family = family; this.role = role; this.relayEnabled = relayEnabled;
        this.deliveryEnabled = deliveryEnabled;
        this.kafkaBusinessEnabled = kafkaBusinessEnabled; this.webType = webType;
    }

    @Override public void run(ApplicationArguments args) {
        relayReady = false;
        if (!role.equals("product") && !role.equals("relay")) throw new IllegalStateException("Unknown event runtime role");
        if (kafkaBusinessEnabled) throw new IllegalStateException("Kafka business activation requires #108 durable intake");
        if (role.equals("relay")) {
            if (!relayEnabled || family.localConsumerEnabled() || deliveryEnabled
                || family.localMaintenanceEnabled() || !webType.equals("none"))
                throw new IllegalStateException("Relay role cannot execute Product or DB delivery work");
            requireApprovedRoutes();
        } else if (relayEnabled) {
            throw new IllegalStateException("Kafka relay requires the isolated relay role");
        }
        if (family.localConsumerEnabled()) {
            // No cutover API exists in #107. An externally changed assignment must fail closed.
            if (activeAssignments().stream().anyMatch(row -> !dbAuthority(row)))
                throw new IllegalStateException("Incompatible publication transport authority");
        }
        relayReady = role.equals("relay");
    }

    public boolean relayReady() { return relayReady; }

    private void requireApprovedRoutes() {
        List<Map<String, Object>> active = activeAssignments();
        List<ConsumerRoute> routes = family.routes();
        Set<ConsumerRoute> expected = Set.copyOf(routes);
        Set<ConsumerRoute> observed = active.stream().map(row -> new ConsumerRoute(family.consumerId(),
            (String) row.get("event_type"), ((Number) row.get("schema_version")).intValue()))
            .collect(java.util.stream.Collectors.toSet());
        if (routes.isEmpty() || expected.size() != routes.size() || active.size() != routes.size()
            || observed.size() != active.size() || !observed.equals(expected)
            || active.stream().anyMatch(row -> !dbAuthority(row))) {
            throw new IllegalStateException("Relay requires exact durable publication routes and DB authority");
        }
    }

    private List<Map<String, Object>> activeAssignments() {
        return db.queryForList("""
            SELECT r.event_type,r.schema_version,a.transport,a.authority_epoch
              FROM event_registrations r
              LEFT JOIN event_transport_assignments a ON a.registration_id=r.registration_id
             WHERE r.consumer_id=? AND r.deactivation_boundary IS NULL
            """, family.consumerId());
    }

    private boolean dbAuthority(Map<String, Object> row) {
        return "DB_DIRECT".equals(row.get("transport"))
            && row.get("authority_epoch") instanceof Number epoch && epoch.longValue() == 1;
    }
}
