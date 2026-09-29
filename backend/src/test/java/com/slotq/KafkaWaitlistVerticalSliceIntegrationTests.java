package com.slotq;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.CapacityReleaseReadiness;
import com.slotq.booking.application.ReservationCommand;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.events.application.DeliveryExecutionScope;
import com.slotq.events.application.DeliveryPolicy;
import com.slotq.events.application.DeliveryTransactions;
import com.slotq.events.application.EventCanonicalizer;
import com.slotq.events.application.EventDeliveryStore;
import com.slotq.events.application.EventDeliveryWorker;
import com.slotq.events.application.EventHandlers;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.events.application.KafkaConsumerCatalog;
import com.slotq.events.application.KafkaPublicationPolicy;
import com.slotq.events.application.KafkaRelayConfiguration;
import com.slotq.events.application.KafkaRelayWorker;
import com.slotq.events.application.KafkaRuntimeGuard;
import com.slotq.events.persistence.EventTransportCutover;
import com.slotq.events.persistence.JdbcKafkaIntakeStore;
import com.slotq.events.persistence.JdbcKafkaPublicationLedger;
import com.slotq.events.persistence.KafkaIntakeRuntime;
import com.slotq.integration.operations.BookingCapacityObservedHandler;
import com.slotq.integration.operations.PromotionRequestedObservedHandler;
import com.slotq.integration.waitlist.BookingCapacityReleasedHandler;
import com.slotq.integration.waitlist.WaitlistPromotionRequestedHandler;
import com.slotq.observability.DatabaseObservation;
import com.slotq.integration.waitlist.WaitlistKafkaMessage;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.WeeklyOperatingHours;
import com.slotq.waitlist.application.WaitlistOfferUseCase;
import com.slotq.waitlist.application.WaitlistPromotionRequestUseCase;
import com.slotq.waitlist.application.WaitlistRegistrationKey;
import com.slotq.waitlist.application.WaitlistUseCase;
import com.slotq.waitlist.domain.WaitlistOfferId;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Real original commands, broker delivery, durable intake, scoped DB execution, and M4 effects. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {"slotq.waitlist.promotion.enabled=true",
    "slotq.events.delivery.scheduler-enabled=false", "slotq.operations.observation.enabled=false"})
@Import(KafkaWaitlistVerticalSliceIntegrationTests.FixtureConfiguration.class)
class KafkaWaitlistVerticalSliceIntegrationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.slotq.integration.waitlist.WaitlistPromotionBootstrap bootstrap;

    private static final String TOPIC = "slotq.waitlist.events.v1";
    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_kafka_m4_slice");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired AccessControlProvisioning access;
    @Autowired ReservationUseCase booking;
    @Autowired WaitlistUseCase waitlist;
    @Autowired WaitlistOfferUseCase offers;
    @Autowired WaitlistPromotionRequestUseCase requests;
    @Autowired EventRegistrationService registrations;
    @Autowired EventTransportCutover cutover;
    @Autowired JdbcKafkaPublicationLedger publications;
    @Autowired JdbcKafkaIntakeStore intakes;
    @Autowired EventDeliveryStore deliveries;
    @Autowired EventDeliveryWorker directExecutor;
    @Autowired EventCanonicalizer canonicalizer;
    @Autowired DeliveryPolicy policy;
    @Autowired KafkaConsumerCatalog catalog;
    @Autowired WaitlistKafkaMessage mapping;
    @Autowired BookingCapacityReleasedHandler releaseHandler;
    @Autowired WaitlistPromotionRequestedHandler requestHandler;
    @Autowired BookingCapacityObservedHandler observedReleaseHandler;
    @Autowired PromotionRequestedObservedHandler observedRequestHandler;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory entities;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate db;
    @Autowired MutableClock clock;

    private org.springframework.kafka.core.KafkaTemplate<String, String> template;
    private KafkaRelayWorker relay;
    private KafkaIntakeRuntime waitlistIntake;
    private KafkaIntakeRuntime observerIntake;
    private EventDeliveryWorker waitlistExecutor;
    private EventDeliveryWorker observerExecutor;
    private final List<Map<String, Object>> outcomes = new ArrayList<>();

    @BeforeAll void startKafkaTransport() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
            String topicId = admin.describeTopics(List.of(TOPIC)).allTopicNames().get(10, TimeUnit.SECONDS)
                .get(TOPIC).topicId().toString();
            db.update("INSERT INTO event_kafka_topic_state (destination,topic_id) VALUES (?,?)", TOPIC, topicId);
        }
        for (var consumer : catalog.consumers())
            for (var route : consumer.routes()) registrations.activate(route);
        var state = cutover.complete("KAFKA", 100);
        assertThat(state.phase()).isEqualTo("READY");
        assertThat(state.authorityEpoch()).isEqualTo(2);
        template = new KafkaRelayConfiguration().publicationTemplate(new KafkaRelayConfiguration.ClientSettings(
            KAFKA.getBootstrapServers(), "PLAINTEXT", "", "", "", ""));
        relay = new KafkaRelayWorker(publications, template, mapping,
            new KafkaPublicationPolicy(3, Duration.ofSeconds(10), Duration.ofSeconds(2),
                100, List.of(Duration.ZERO, Duration.ZERO)), meters, TOPIC);
        waitlistIntake = intake("waitlist.promotion", state.authorityEpoch());
        observerIntake = intake("operations.event-observation", state.authorityEpoch());
        waitlistExecutor = executor("waitlist.promotion", state.authorityEpoch(),
            new EventHandlers(List.of(releaseHandler, requestHandler)));
        observerExecutor = executor("operations.event-observation", state.authorityEpoch(),
            new EventHandlers(List.of(observedReleaseHandler, observedRequestHandler)));
    }

    @AfterAll void stopKafkaTransport() {
        if (waitlistIntake != null) waitlistIntake.close();
        if (observerIntake != null) observerIntake.close();
        if (template != null) ((org.springframework.kafka.core.DefaultKafkaProducerFactory<?, ?>)
            template.getProducerFactory()).destroy();
    }

    @Test void releaseRequestFifoTerminalAndNewDemandConvergeThroughKafkaAndDbLedger() throws Exception {
        Instant start = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
            .atTime(11, 0).toInstant(ZoneOffset.UTC);
        clock.set(start.minus(Duration.ofHours(2)));
        Fixture first = fixture(start);
        AuthenticatedPrincipal occupant = customer();
        var original = booking.createHold(new ReservationUseCase.CreateHold(first.venueId, first.slotId, occupant, 2))
            .reservation();
        booking.transition(first.venueId, original.id(), occupant, ReservationCommand.CONFIRM);
        Entry earliest = entry(first);
        Entry second = entry(first);
        booking.transition(first.venueId, original.id(), occupant, ReservationCommand.CANCEL);
        UUID release = db.queryForObject("SELECT event_id FROM event_records WHERE aggregate_id=?",
            (row, number) -> uuid(row.getBytes(1)), bytes(original.id().value()));
        converge(release, "PROMOTED");
        UUID firstOffer = offerFor(earliest.id);
        assertThat(firstOffer).isNotNull();
        assertThat(offerFor(second.id)).isNull();
        assertThat(offers.reject(first.venueId, new WaitlistOfferId(firstOffer), earliest.customer).outcome())
            .isEqualTo(WaitlistOfferUseCase.CommandOutcome.SUCCESS);
        UUID rejectRelease = releaseForOffer(firstOffer);
        converge(rejectRelease, "PROMOTED");
        UUID secondOffer = offerFor(second.id);
        assertThat(secondOffer).isNotNull();
        assertThat(offerFor(earliest.id)).isEqualTo(firstOffer);
        assertNoOpRequest(first);

        Entry third = entry(first);
        clock.set(clock.instant().plusSeconds(301));
        assertThat(offers.reconcileTarget(SystemPrincipal.INSTANCE, first.venueId,
            new WaitlistOfferId(secondOffer)).outcome()).isEqualTo(WaitlistOfferUseCase.TargetOutcome.EXPIRED);
        UUID expiryRelease = releaseForOffer(secondOffer);
        converge(expiryRelease, "PROMOTED");
        UUID thirdOffer = offerFor(third.id);
        assertThat(thirdOffer).isNotNull();
        assertNoOpRequest(first);
        assertThat(offers.accept(first.venueId, new WaitlistOfferId(thirdOffer), third.customer).outcome())
            .isEqualTo(WaitlistOfferUseCase.CommandOutcome.SUCCESS);
        assertThat(db.queryForObject("SELECT state FROM waitlist_entries WHERE id=?", String.class, bytes(third.id)))
            .isEqualTo("FULFILLED");
        assertThat(db.queryForObject("SELECT state FROM waitlist_offers WHERE id=?", String.class, bytes(thirdOffer)))
            .isEqualTo("ACCEPTED");

        Fixture noCandidate = fixture(start.plusSeconds(1800));
        AuthenticatedPrincipal idleOccupant = customer();
        var idleReservation = booking.createHold(new ReservationUseCase.CreateHold(
            noCandidate.venueId, noCandidate.slotId, idleOccupant, 2)).reservation();
        booking.transition(noCandidate.venueId, idleReservation.id(), idleOccupant, ReservationCommand.CONFIRM);
        booking.transition(noCandidate.venueId, idleReservation.id(), idleOccupant, ReservationCommand.CANCEL);
        UUID noCandidateEvent = db.queryForObject("SELECT event_id FROM event_records WHERE aggregate_id=?",
            (row, number) -> uuid(row.getBytes(1)), bytes(idleReservation.id().value()));
        converge(noCandidateEvent, "NO_CANDIDATE");
        Entry newDemand = entry(noCandidate);
        UUID newRequest = request(noCandidate);
        converge(newRequest, "PROMOTED");
        assertThat(offerFor(newDemand.id)).isNotNull();
        assertThat(receipt(noCandidateEvent)).isEqualTo("NO_CANDIDATE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM waitlist_offers WHERE venue_id=?", Integer.class,
            bytes(noCandidate.venueId.value()))).isEqualTo(1);
        writeEvidence();
    }

    private void converge(UUID eventId, String expectedOutcome) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (count("SELECT COUNT(*) FROM event_kafka_publications WHERE event_id=? AND state='PUBLISHED'", eventId) == 0
            && System.nanoTime() < deadline) relay.runCycle();
        assertThat(count("SELECT COUNT(*) FROM event_kafka_publications WHERE event_id=? AND state='PUBLISHED'", eventId))
            .isEqualTo(1);
        assertThat(directExecutor.runCycle()).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_deliveries WHERE event_id=?", eventId)).isZero();
        while (count("SELECT COUNT(*) FROM event_kafka_target_intakes WHERE event_id=?", eventId) < 2
            && System.nanoTime() < deadline) {
            waitlistIntake.runCycle();
            observerIntake.runCycle();
        }
        assertThat(count("SELECT COUNT(*) FROM event_kafka_target_intakes WHERE event_id=?", eventId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM event_deliveries WHERE event_id=? AND state='PENDING'", eventId))
            .isEqualTo(2);
        assertObservedDelivery("PENDING");
        while (count("SELECT COUNT(*) FROM event_deliveries WHERE event_id=? AND state='DONE'", eventId) < 2
            && System.nanoTime() < deadline) {
            waitlistExecutor.runCycle();
            observerExecutor.runCycle();
        }
        assertThat(count("SELECT COUNT(*) FROM event_deliveries WHERE event_id=? AND state='DONE'", eventId))
            .isEqualTo(2);
        assertObservedDelivery("DONE");
        assertThat(receipt(eventId)).isEqualTo(expectedOutcome);
        assertThat(count("SELECT COUNT(*) FROM event_observation_projections WHERE event_id=?", eventId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM event_kafka_intake_records WHERE event_id=? AND disposition='TARGET'",
            eventId)).isEqualTo(2);
        outcomes.add(Map.of("eventId", eventId.toString(), "businessOutcome", expectedOutcome));
    }

    private void assertObservedDelivery(String state) throws Exception {
        try (var connection = db.getDataSource().getConnection()) {
            for (String consumerId : List.of("waitlist.promotion", "operations.event-observation")) {
                var observed = new DatabaseObservation().readKafkaDeliveries(connection, consumerId);
                assertThat(observed.values().get(new DatabaseObservation.Key(
                    "delivery.targets", "delivery_state", state))).isGreaterThanOrEqualTo(1.0);
            }
        }
    }

    private UUID request(Fixture fixture) {
        var result = requests.request(SystemPrincipal.INSTANCE, fixture.venueId, fixture.slotId);
        assertThat(result.outcome()).isEqualTo(WaitlistPromotionRequestUseCase.Outcome.APPENDED);
        return result.eventId();
    }

    private void assertNoOpRequest(Fixture fixture) {
        assertThat(requests.request(SystemPrincipal.INSTANCE, fixture.venueId, fixture.slotId).outcome())
            .isEqualTo(WaitlistPromotionRequestUseCase.Outcome.NO_OP);
    }

    private Fixture fixture(Instant start) {
        var tenant = tenants.createTenant();
        var venue = venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(), "Kafka M4", "UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,
                new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(14, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10)));
        var resource = resources.createResource(new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Table", 2));
        var slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(), venue.id(), resource.id(),
            start.toString()));
        return new Fixture(venue.id(), slot.id());
    }

    private Entry entry(Fixture fixture) {
        // FIFO timestamps must be distinct even when the fixture uses a fixed clock.
        clock.set(clock.instant().plusMillis(1));
        var customer = customer();
        var created = waitlist.register(new WaitlistUseCase.CreateRegistration(fixture.venueId,
            fixture.slotId, 2, new WaitlistRegistrationKey(UUID.randomUUID()), customer)).entry();
        return new Entry(created.id(), customer);
    }

    private AuthenticatedPrincipal customer() {
        var customer = new AuthenticatedPrincipal(PrincipalId.newId());
        access.registerPrincipal(customer.principalId());
        return customer;
    }

    private KafkaIntakeRuntime intake(String consumer, long epoch) {
        var scope = new DeliveryExecutionScope(consumer, "KAFKA", epoch);
        var family = consumer.equals("waitlist.promotion") ? mapping : new WaitlistKafkaMessage(false, false);
        var guard = new KafkaRuntimeGuard(db, family, catalog, scope, "consumer", false, true, true,
            false, consumer.equals("operations.event-observation"), "none");
        guard.run(null);
        return new KafkaIntakeRuntime(intakes, guard, catalog, meters, "consumer", consumer, "KAFKA", epoch,
            TOPIC, KAFKA.getBootstrapServers(), "PLAINTEXT", "", "", "", "", "0,1,2");
    }

    private EventDeliveryWorker executor(String consumer, long epoch, EventHandlers handlers) {
        return new EventDeliveryWorker(deliveries, new DeliveryTransactions(manager, deliveries, policy),
            policy, handlers, canonicalizer, entities, new DeliveryExecutionScope(consumer, "KAFKA", epoch));
    }

    private long count(String sql, UUID eventId) {
        return db.queryForObject(sql, Long.class, bytes(eventId));
    }

    private String receipt(UUID eventId) {
        return db.queryForObject("SELECT outcome FROM waitlist_promotion_receipts WHERE event_id=?", String.class,
            bytes(eventId));
    }

    private UUID offerFor(UUID entryId) {
        var found = db.query("SELECT id FROM waitlist_offers WHERE entry_id=?",
            (row, number) -> uuid(row.getBytes(1)), bytes(entryId));
        return found.isEmpty() ? null : found.getFirst();
    }

    private UUID releaseForOffer(UUID offerId) {
        return db.queryForObject("""
            SELECT e.event_id FROM event_records e
              JOIN waitlist_offers o ON e.aggregate_id=o.reservation_id
             WHERE o.id=? AND e.event_type='booking.capacity-released'
             ORDER BY e.boundary_sequence DESC LIMIT 1
            """, (row, number) -> uuid(row.getBytes(1)), bytes(offerId));
    }

    private void writeEvidence() throws Exception {
        String directory = System.getProperty("slotq.kafka.evidence.dir");
        if (directory == null) return;
        String revision = System.getProperty("slotq.kafka.evidence.revision");
        if (revision == null || !revision.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Evidence requires the implementation commit SHA");
        Path output = Path.of(directory).resolve("m4-kafka-vertical-raw.json");
        Files.createDirectories(output.getParent());
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("schemaVersion", "slotq-kafka-m4-vertical/v1");
        raw.put("implementationRevision", revision);
        raw.put("mysqlVersion", db.queryForObject("SELECT VERSION()", String.class));
        raw.put("mysqlIsolation", db.queryForObject("SELECT @@transaction_isolation", String.class));
        raw.put("brokerImage", "apache/kafka:4.1.1");
        raw.put("events", outcomes);
        raw.put("publications", db.queryForList("""
            SELECT HEX(event_id) AS event_id,state,ack_partition,ack_offset
              FROM event_kafka_publications ORDER BY discovered_boundary
            """));
        raw.put("targetIntakes", db.queryForList("""
            SELECT HEX(event_id) AS event_id,consumer_id,topic,partition_id,first_offset,authority_epoch,intaken_at
              FROM event_kafka_target_intakes ORDER BY intaken_at,event_id,consumer_id
            """));
        raw.put("deliveries", db.queryForList("""
            SELECT HEX(event_id) AS event_id,HEX(registration_id) AS registration_id,state,
                   cycle_attempts,lifetime_attempts,fencing_token
              FROM event_deliveries ORDER BY event_id,registration_id
            """));
        raw.put("receipts", db.queryForList("""
            SELECT HEX(event_id) AS event_id,outcome,HEX(entry_id) AS entry_id,HEX(offer_id) AS offer_id
              FROM waitlist_promotion_receipts ORDER BY event_id
            """));
        raw.put("offers", db.queryForList("""
            SELECT HEX(id) AS offer_id,HEX(entry_id) AS entry_id,state,
                    HEX(reservation_id) AS reservation_id FROM waitlist_offers ORDER BY expires_at,id
            """));
        Files.writeString(output, new JsonMapper().writeValueAsString(raw));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
            .array();
    }
    private static UUID uuid(byte[] value) {
        var buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record Fixture(com.slotq.venue.domain.VenueId venueId,
                           com.slotq.booking.domain.SlotInventoryId slotId) { }
    private record Entry(UUID id, AuthenticatedPrincipal customer) { }

    @TestConfiguration static class FixtureConfiguration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
        @Bean @Primary CapacityReleaseReadiness readiness() { return () -> true; }
    }
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        void set(Instant value) { now.set(value); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
