package com.slotq.events.application;

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
    private final String role;
    private final boolean relayEnabled;
    private final boolean promotionEnabled;
    private final boolean deliveryEnabled;
    private final boolean maintenanceEnabled;
    private final boolean kafkaBusinessEnabled;
    private final String webType;
    private volatile boolean relayReady;

    @org.springframework.beans.factory.annotation.Autowired
    public KafkaRuntimeGuard(JdbcTemplate db,
        @Value("${slotq.events.runtime-role:product}") String role,
        @Value("${slotq.events.kafka.relay-enabled:false}") boolean relayEnabled,
        @Value("${slotq.waitlist.promotion.enabled:false}") boolean promotionEnabled,
        @Value("${slotq.events.delivery.scheduler-enabled:false}") boolean deliveryEnabled,
        @Value("${slotq.waitlist.promotion.maintenance-enabled:false}") boolean maintenanceEnabled,
        @Value("${slotq.events.kafka.business-enabled:false}") boolean kafkaBusinessEnabled,
        @Value("${spring.main.web-application-type:servlet}") String webType) {
        this.db = db; this.role = role; this.relayEnabled = relayEnabled; this.promotionEnabled = promotionEnabled;
        this.deliveryEnabled = deliveryEnabled; this.maintenanceEnabled = maintenanceEnabled;
        this.kafkaBusinessEnabled = kafkaBusinessEnabled; this.webType = webType;
    }

    public KafkaRuntimeGuard(JdbcTemplate db, String role, boolean relayEnabled, boolean promotionEnabled,
        boolean deliveryEnabled, boolean maintenanceEnabled, boolean kafkaBusinessEnabled) {
        this(db, role, relayEnabled, promotionEnabled, deliveryEnabled, maintenanceEnabled, kafkaBusinessEnabled, "none");
    }

    @Override public void run(ApplicationArguments args) {
        relayReady = false;
        if (!role.equals("product") && !role.equals("relay")) throw new IllegalStateException("Unknown event runtime role");
        if (kafkaBusinessEnabled) throw new IllegalStateException("Kafka business activation requires #108 durable intake");
        if (role.equals("relay")) {
            if (!relayEnabled || promotionEnabled || deliveryEnabled || maintenanceEnabled || !webType.equals("none"))
                throw new IllegalStateException("Relay role cannot execute Product or DB delivery work");
            requireWaitlistRoutes();
        } else if (relayEnabled) {
            throw new IllegalStateException("Kafka relay requires the isolated relay role");
        }
        if (promotionEnabled) {
            // No cutover API exists in #107. An externally changed assignment must fail closed.
            Integer incompatible = db.queryForObject("""
                SELECT COUNT(*) FROM event_registrations r
                  LEFT JOIN event_transport_assignments a ON a.registration_id=r.registration_id
                 WHERE r.consumer_id='waitlist.promotion' AND r.deactivation_boundary IS NULL
                   AND (a.registration_id IS NULL OR a.transport<>'DB_DIRECT' OR a.authority_epoch<>1)
                """, Integer.class);
            if (incompatible != null && incompatible > 0) throw new IllegalStateException("Incompatible Waitlist transport authority");
        }
        relayReady = role.equals("relay");
    }

    public boolean relayReady() { return relayReady; }

    private void requireWaitlistRoutes() {
        Integer count = db.queryForObject("""
            SELECT COUNT(*) FROM event_registrations r
              JOIN event_transport_assignments a ON a.registration_id=r.registration_id
             WHERE r.consumer_id='waitlist.promotion' AND r.deactivation_boundary IS NULL
               AND a.transport='DB_DIRECT' AND a.authority_epoch=1
               AND ((r.event_type='booking.capacity-released' AND r.schema_version=1)
                 OR (r.event_type='waitlist.promotion-requested' AND r.schema_version=1))
            """, Integer.class);
        Integer total = db.queryForObject("""
            SELECT COUNT(*) FROM event_registrations
             WHERE consumer_id='waitlist.promotion' AND deactivation_boundary IS NULL
            """, Integer.class);
        if (count == null || count != 2 || total == null || total != 2)
            throw new IllegalStateException("Relay requires the two exact durable Waitlist routes and DB authority");
    }
}
