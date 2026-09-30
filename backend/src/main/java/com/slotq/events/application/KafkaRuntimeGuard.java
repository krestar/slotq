package com.slotq.events.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Runtime configuration narrows authority; only the durable assignment grants it. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class KafkaRuntimeGuard implements ApplicationRunner {
    private final JdbcTemplate db;
    private final KafkaPublicationFamily family;
    private final KafkaConsumerCatalog catalog;
    private final DeliveryExecutionScope scope;
    private final String role;
    private final boolean relayEnabled;
    private final boolean deliveryEnabled;
    private final boolean consumerEnabled;
    private final boolean kafkaBusinessEnabled;
    private final boolean observerEnabled;
    private final String webType;
    private final boolean managementOnly;
    private volatile boolean relayReady;
    private volatile boolean consumerReady;

    @org.springframework.beans.factory.annotation.Autowired
    public KafkaRuntimeGuard(JdbcTemplate db, KafkaPublicationFamily family,
        KafkaConsumerCatalog catalog, DeliveryExecutionScope scope,
        @Value("${slotq.events.runtime-role:product}") String role,
        @Value("${slotq.events.kafka.relay-enabled:false}") boolean relayEnabled,
        @Value("${slotq.events.delivery.scheduler-enabled:false}") boolean deliveryEnabled,
        @Value("${slotq.events.kafka.consumer-enabled:false}") boolean consumerEnabled,
        @Value("${slotq.events.kafka.business-enabled:false}") boolean kafkaBusinessEnabled,
        @Value("${slotq.operations.observation.enabled:false}") boolean observerEnabled,
        @Value("${spring.main.web-application-type:servlet}") String webType,
        @Value("${slotq.events.management-only:false}") boolean managementOnly) {
        this.db = db; this.family = family; this.catalog = catalog; this.scope = scope;
        this.role = role; this.relayEnabled = relayEnabled;
        this.deliveryEnabled = deliveryEnabled;
        this.consumerEnabled = consumerEnabled; this.kafkaBusinessEnabled = kafkaBusinessEnabled;
        this.observerEnabled = observerEnabled;
        this.webType = webType;
        this.managementOnly = managementOnly;
    }

    public KafkaRuntimeGuard(JdbcTemplate db, KafkaPublicationFamily family,
        KafkaConsumerCatalog catalog, DeliveryExecutionScope scope, String role, boolean relayEnabled,
        boolean deliveryEnabled, boolean consumerEnabled, boolean kafkaBusinessEnabled,
        boolean observerEnabled, String webType) {
        this(db,family,catalog,scope,role,relayEnabled,deliveryEnabled,consumerEnabled,
            kafkaBusinessEnabled,observerEnabled,webType,false);
    }

    KafkaRuntimeGuard(JdbcTemplate db, KafkaPublicationFamily family, String role,
        boolean relayEnabled, boolean deliveryEnabled, boolean kafkaBusinessEnabled, String webType) {
        this(db, family, null, null, role, relayEnabled, deliveryEnabled,
            false, kafkaBusinessEnabled, false, webType);
    }

    @Override public void run(ApplicationArguments args) {
        relayReady = false;
        consumerReady = false;
        if (!Set.of("product", "relay", "consumer", "cutover").contains(role))
            throw new IllegalStateException("Unknown event runtime role");
        if (managementOnly && !Set.of("relay", "consumer").contains(role))
            throw new IllegalStateException("Metrics-only HTTP is limited to isolated relay/consumer roles");
        Authority authority = authority();
        if (!role.equals("cutover") && !authority.ready())
            throw new IllegalStateException("Transport cutover scan is incomplete");
        switch (role) {
            case "relay" -> {
                if (!relayEnabled || consumerEnabled || family.localConsumerEnabled() || observerEnabled || deliveryEnabled
                    || family.localMaintenanceEnabled() || !isolatedWeb())
                    throw new IllegalStateException("Relay role cannot execute Product or DB delivery work");
                requireApprovedRoutes(family.consumerId(), family.routes(), authority);
                relayReady = true;
            }
            case "product" -> {
                if (relayEnabled || consumerEnabled || (observerEnabled && kafkaBusinessEnabled))
                    throw new IllegalStateException("Product cannot run Kafka relay or intake");
                if (scope != null && (!scope.transport().equals("DB_DIRECT")
                    || !scope.consumerId().equals(family.consumerId())))
                    throw new IllegalStateException("Product cannot execute another consumer or Kafka target");
                if (kafkaBusinessEnabled && deliveryEnabled)
                    throw new IllegalStateException("Kafka Product cannot run DB direct executor");
                if (deliveryEnabled && scope != null && scope.authorityEpoch() != authority.epoch())
                    throw new IllegalStateException("Product scope has stale transport authority");
                if (family.localConsumerEnabled()) {
                    if (kafkaBusinessEnabled != authority.transport().equals("KAFKA"))
                        throw new IllegalStateException("Incompatible publication transport authority");
                    if (kafkaBusinessEnabled) {
                        requireApprovedRoutes(family.consumerId(), family.routes(), authority);
                    } else if (activeAssignments(family.consumerId()).stream().anyMatch(row ->
                        !authority.transport().equals(row.get("transport"))
                        || !(row.get("authority_epoch") instanceof Number epoch)
                        || epoch.longValue() != authority.epoch())) {
                        throw new IllegalStateException("Incompatible publication transport authority");
                    }
                } else if (kafkaBusinessEnabled) {
                    throw new IllegalStateException("Kafka Product mode requires approved M4 routes");
                }
            }
            case "consumer" -> {
                if (relayEnabled || !deliveryEnabled || !isolatedWeb() || scope == null
                    || catalog == null)
                    throw new IllegalStateException("Consumer requires isolated scoped DB executor");
                if (consumerEnabled != scope.transport().equals("KAFKA"))
                    throw new IllegalStateException("Consumer intake and execution transport disagree");
                var definition = catalog.definition(scope.consumerId());
                if (family.localMaintenanceEnabled()
                    || (definition.consumerId().equals(family.consumerId())
                        ? !family.localConsumerEnabled() || observerEnabled
                        : family.localConsumerEnabled() || !observerEnabled))
                    throw new IllegalStateException("Consumer configuration crosses logical ownership");
                if (!scope.transport().equals(authority.transport())
                    || scope.authorityEpoch() != authority.epoch())
                    throw new IllegalStateException("Consumer scope has stale transport authority");
                requireApprovedRoutes(definition.consumerId(), definition.routes(), authority);
                consumerReady = true;
            }
            case "cutover" -> {
                if (relayEnabled || consumerEnabled || deliveryEnabled || family.localConsumerEnabled()
                    || family.localMaintenanceEnabled() || observerEnabled || !webType.equals("none"))
                    throw new IllegalStateException("Cutover requires a quiesced maintenance role");
            }
            default -> throw new IllegalStateException("Unknown event runtime role");
        }
    }

    public boolean relayReady() { return relayReady; }
    public boolean consumerReady() { return consumerReady; }

    private boolean isolatedWeb() {
        return webType.equals("none") || (webType.equals("servlet") && managementOnly);
    }

    private void requireApprovedRoutes(String consumerId, List<ConsumerRoute> routes, Authority authority) {
        List<Map<String, Object>> active = activeAssignments(consumerId);
        Set<ConsumerRoute> expected = Set.copyOf(routes);
        Set<ConsumerRoute> observed = active.stream().map(row -> new ConsumerRoute(consumerId,
            (String) row.get("event_type"), ((Number) row.get("schema_version")).intValue()))
            .collect(Collectors.toSet());
        if (routes.isEmpty() || expected.size() != routes.size() || active.size() != routes.size()
            || observed.size() != active.size() || !observed.equals(expected)
            || active.stream().anyMatch(row -> !authority.transport().equals(row.get("transport"))
                || !(row.get("authority_epoch") instanceof Number epoch)
                || epoch.longValue() != authority.epoch())) {
            throw new IllegalStateException("Runtime requires exact durable publication routes and authority");
        }
    }

    private List<Map<String, Object>> activeAssignments(String consumerId) {
        return db.queryForList("""
            SELECT r.event_type,r.schema_version,a.transport,a.authority_epoch
              FROM event_registrations r
              LEFT JOIN event_transport_assignments a ON a.registration_id=r.registration_id
             WHERE r.consumer_id=? AND r.deactivation_boundary IS NULL
            """, consumerId);
    }

    private Authority authority() {
        List<Map<String, Object>> rows = db.queryForList("""
            SELECT to_transport,authority_epoch,phase FROM event_transport_cutover WHERE singleton_id=1
            """);
        if (rows.isEmpty()) return new Authority("DB_DIRECT", 1, true);
        var row = rows.getFirst();
        return new Authority((String) row.get("to_transport"),
            ((Number) row.get("authority_epoch")).longValue(), "READY".equals(row.get("phase")));
    }

    private record Authority(String transport, long epoch, boolean ready) { }
}
