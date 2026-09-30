package com.slotq;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.ReservationCommand;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.events.application.EventDeliveryWorker;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.events.application.KafkaPublicationPolicy;
import com.slotq.events.application.KafkaRelayConfiguration;
import com.slotq.events.application.KafkaRelayWorker;
import com.slotq.events.persistence.JdbcKafkaPublicationLedger;
import com.slotq.integration.waitlist.WaitlistKafkaMessage;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.WeeklyOperatingHours;
import com.slotq.waitlist.application.WaitlistPromotionRequestUseCase;
import com.slotq.waitlist.application.WaitlistRegistrationKey;
import com.slotq.waitlist.application.WaitlistUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = "slotq.waitlist.promotion.enabled=true")
@ActiveProfiles("test")
@Import(BookingCapacityReleaseIntegrationTests.FixtureConfiguration.class)
class KafkaBookingPublicationIntegrationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.slotq.integration.waitlist.WaitlistPromotionBootstrap bootstrap;

    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1").withDatabaseName("slotq_real_booking_kafka");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");
    private static final String TOPIC = "slotq.waitlist.events.v1";

    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired ReservationUseCase booking;
    @Autowired WaitlistUseCase waitlist;
    @Autowired WaitlistPromotionRequestUseCase requests;
    @Autowired AccessControlProvisioning access;
    @Autowired EventRegistrationService registrations;
    @Autowired EventDeliveryWorker directWorker;
    @Autowired JdbcKafkaPublicationLedger ledger;
    @Autowired WaitlistKafkaMessage mapping;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate db;
    @Autowired BookingCapacityReleaseIntegrationTests.MutableClock clock;
    @Autowired BookingCapacityReleaseIntegrationTests.TestReadiness readiness;

    @Test void realBookingReleaseAndPromotionRequestPublishWithoutChangingDbEffects() throws Exception {
        clock.set(Instant.parse("2026-08-30T09:00:00Z"));
        readiness.ready.set(true);
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        registrations.activate(new com.slotq.events.application.ConsumerRoute("waitlist.promotion", "booking.capacity-released", 1));
        registrations.activate(new com.slotq.events.application.ConsumerRoute("waitlist.promotion", "waitlist.promotion-requested", 1));
        var customer = new AuthenticatedPrincipal(PrincipalId.newId());
        access.registerPrincipal(customer.principalId());
        var tenant = tenants.createTenant();
        var venue = venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(), "Kafka evidence", "UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,
                new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(13, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10)));
        var resource = resources.createResource(new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Table", 2));
        var slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(), venue.id(), resource.id(),
            "2026-08-30T11:00:00Z"));
        var reservation = booking.createHold(new ReservationUseCase.CreateHold(venue.id(), slot.id(), customer, 2))
            .reservation();
        booking.transition(venue.id(), reservation.id(), customer, ReservationCommand.CONFIRM);
        waitlist.register(new WaitlistUseCase.CreateRegistration(venue.id(), slot.id(), 2,
            new WaitlistRegistrationKey(UUID.randomUUID()), customer));
        booking.transition(venue.id(), reservation.id(), customer, ReservationCommand.CANCEL);
        assertThat(requests.request(SystemPrincipal.INSTANCE, venue.id(), slot.id()).outcome())
            .isEqualTo(WaitlistPromotionRequestUseCase.Outcome.APPENDED);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_records WHERE tenant_id=?", Integer.class,
            bytes(tenant.id().value()))).isEqualTo(2);

        var template = new KafkaRelayConfiguration().publicationTemplate(new KafkaRelayConfiguration.ClientSettings(
            KAFKA.getBootstrapServers(), "PLAINTEXT", "", "", "", ""));
        try {
            var relay = new KafkaRelayWorker(ledger, template, mapping,
                new KafkaPublicationPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(2),
                    100, List.of(Duration.ZERO, Duration.ZERO)), meters, TOPIC);
            assertThat(relay.runCycle()).isEqualTo(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM event_kafka_publications WHERE state='PUBLISHED'",
                Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries", Integer.class)).isZero();
            directWorker.runCycle();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE state='DONE'",
                Integer.class)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM waitlist_offers WHERE tenant_id=?", Integer.class,
                bytes(tenant.id().value()))).isEqualTo(1);

            Properties consumer = new Properties();
            consumer.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
            consumer.put(ConsumerConfig.GROUP_ID_CONFIG, "real-workload-evidence-" + UUID.randomUUID());
            consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            consumer.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumer.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(consumer)) {
                reader.subscribe(List.of(TOPIC));
                var eventTypes = new java.util.HashSet<String>();
                var brokerRecords = new java.util.ArrayList<Map<String, Object>>();
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (eventTypes.size() < 2 && System.nanoTime() < deadline) {
                    for (var record : reader.poll(Duration.ofMillis(250))) {
                        brokerRecords.add(Map.of("key", record.key(), "value", record.value(),
                            "partition", record.partition(), "offset", record.offset()));
                        assertThat(record.key()).isEqualTo(tenant.id().value() + ":" + slot.id().value());
                        if (record.value().contains("\"eventType\":\"booking.capacity-released\""))
                            eventTypes.add("booking.capacity-released");
                        if (record.value().contains("\"eventType\":\"waitlist.promotion-requested\""))
                            eventTypes.add("waitlist.promotion-requested");
                    }
                }
                assertThat(eventTypes).containsExactlyInAnyOrder(
                    "booking.capacity-released", "waitlist.promotion-requested");
                String evidenceDirectory = System.getProperty("slotq.kafka.evidence.dir");
                if (evidenceDirectory != null) {
                    Path output = Path.of(evidenceDirectory).resolve("business-raw.json");
                    Files.createDirectories(output.getParent());
                    var raw = new java.util.LinkedHashMap<String, Object>();
                    raw.put("schemaVersion", "slotq-kafka-relay-business/v1");
                    raw.put("mysqlVersion", db.queryForObject("SELECT VERSION()", String.class));
                    raw.put("mysqlIsolation", db.queryForObject("SELECT @@transaction_isolation", String.class));
                    raw.put("tenantId", tenant.id().value().toString());
                    raw.put("slotInventoryId", slot.id().value().toString());
                    raw.put("reservationId", reservation.id().value().toString());
                    raw.put("eventRecords", db.queryForList("""
                        SELECT HEX(event_id) AS event_id, event_type, schema_version, boundary_sequence, payload
                          FROM event_records WHERE tenant_id=? ORDER BY boundary_sequence
                        """, bytes(tenant.id().value())));
                    raw.put("publications", db.queryForList("""
                        SELECT HEX(event_id) AS event_id, destination, state, cycle_attempts,
                               lifetime_attempts, fencing_token, ack_partition, ack_offset
                          FROM event_kafka_publications WHERE tenant_id=? ORDER BY discovered_boundary
                        """, bytes(tenant.id().value())));
                    raw.put("deliveries", db.queryForList("""
                        SELECT HEX(event_id) AS event_id, HEX(registration_id) AS registration_id,
                               state, cycle_attempts, lifetime_attempts, fencing_token
                          FROM event_deliveries WHERE tenant_id=? ORDER BY event_id
                        """, bytes(tenant.id().value())));
                    raw.put("offerCount", db.queryForObject("SELECT COUNT(*) FROM waitlist_offers WHERE tenant_id=?",
                        Integer.class, bytes(tenant.id().value())));
                    raw.put("brokerRecords", brokerRecords);
                    Files.writeString(output, new tools.jackson.databind.json.JsonMapper().writeValueAsString(raw));
                }
            }
        } finally {
            ((org.springframework.kafka.core.DefaultKafkaProducerFactory<?, ?>)
                template.getProducerFactory()).destroy();
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
}
