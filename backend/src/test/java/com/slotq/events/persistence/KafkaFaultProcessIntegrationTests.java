package com.slotq.events.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.events.application.EventAppendService;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRecordStore;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.events.application.KafkaConsumerCatalog;
import com.slotq.integration.waitlist.WaitlistPromotionRequestedHandler;
import com.slotq.integration.waitlist.WaitlistKafkaMessage;
import com.slotq.observability.ProductTelemetry;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.WeeklyOperatingHours;
import com.slotq.waitlist.application.WaitlistRegistrationKey;
import com.slotq.waitlist.application.WaitlistUseCase;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in #109 experiment: real application entrypoints in separate JVMs and the persistent 3-node broker. */
@Testcontainers
@EnabledIfSystemProperty(named = "slotq.fault.kafkaFaultBootstrap", matches = ".+")
@SpringBootTest(properties = {"slotq.waitlist.promotion.enabled=false",
    "slotq.events.delivery.scheduler-enabled=false", "slotq.operations.observation.enabled=false"})
class KafkaFaultProcessIntegrationTests {
    private static final String BOOTSTRAP = System.getProperty("slotq.fault.kafkaFaultBootstrap", "");
    private static final String RUN_ID = System.getProperty("slotq.fault.kafkaFaultRunId", "");
    private static final String TOPIC = "slotq.fault109." + RUN_ID;
    private static final String WAITLIST = "waitlist.promotion";
    private static final String OBSERVER = "operations.event-observation";

    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("slotq_fault_109")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withBinds(
                new Bind("slotq-fault-109-mysql-" + RUN_ID, new Volume("/var/lib/mysql"))));

    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    @Autowired EventRegistrationService registrations;
    @Autowired EventTransportCutover cutover;
    @Autowired EventAppendService append;
    @Autowired EventRecordStore records;
    @Autowired WaitlistKafkaMessage mapping;
    @Autowired KafkaConsumerCatalog catalog;
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired AccessControlProvisioning access;
    @Autowired WaitlistUseCase waitlist;

    private final List<ManagedProcess> processes = new ArrayList<>();
    private final List<Map<String, Object>> timeline = new ArrayList<>();
    private final List<UUID> originals = new ArrayList<>();
    private UUID candidateEntry;
    private UUID candidateSlot;
    private UUID candidateEvent;
    private final JsonMapper json = new JsonMapper();
    private Path output;
    private Path processLogs;

    @Test
    void realGroupsScaleRebalanceLeaderRestartAndOutageRecover() throws Exception {
        assertThat(RUN_ID).matches("[a-z0-9-]{1,24}");
        output = Path.of(System.getProperty("slotq.fault.kafkaFaultEvidenceDir"), RUN_ID);
        Files.createDirectories(output);
        processLogs = Path.of("build", "kafka-fault", RUN_ID);
        Files.createDirectories(processLogs);
        try {
            prepareTopicAndAuthority();
            UUID first = appendRequest(1);
            capture("before-relay", first);
            ManagedProcess relay1 = start("relay-1", "relay");
            ManagedProcess relay2 = start("relay-2", "relay");
            ManagedProcess waitlist1 = start("waitlist-1", WAITLIST);
            ManagedProcess observer1 = start("observer-1", OBSERVER);
            ManagedProcess product = start("product-1", "product");
            await("two independent groups", Duration.ofSeconds(90), () ->
                members(WAITLIST) == 1 && members(OBSERVER) == 1);
            awaitConvergence(first);
            capture("two-groups-converged", first);
            await("Product HTTP admission", Duration.ofSeconds(90), () -> publicVenueStatus(product) == 200);
            recordProductHttp("product-http-with-both-groups", product);

            observer1.kill();
            UUID isolated = appendRequest(2);
            capture("before-observer-outage", isolated);
            await("Waitlist proceeds while observer is down", Duration.ofSeconds(90), () ->
                count("SELECT COUNT(*) FROM event_deliveries d JOIN event_registrations r"
                    + " ON r.registration_id=d.registration_id WHERE d.event_id=?"
                    + " AND r.consumer_id='waitlist.promotion' AND d.state='DONE'", isolated) == 1);
            recordProductHttp("product-http-during-observer-outage", product);
            capture("observer-down-waitlist-done", isolated);
            start("observer-restarted", OBSERVER);
            awaitConvergence(isolated);
            capture("observer-recovered", isolated);

            start("waitlist-2", WAITLIST);
            start("waitlist-3", WAITLIST);
            start("waitlist-4", WAITLIST);
            await("four replicas", Duration.ofSeconds(90), () -> members(WAITLIST) == 4);
            Map<String, Object> scaled = capture("one-to-four-idle-member", first);
            @SuppressWarnings("unchecked") Map<String, Object> broker = (Map<String, Object>) scaled.get("broker");
            @SuppressWarnings("unchecked") Map<String, Object> waitlistGroup =
                (Map<String, Object>) ((Map<?, ?>) broker.get("groups")).get(WAITLIST);
            @SuppressWarnings("unchecked") List<Integer> assignments = (List<Integer>) waitlistGroup.get("memberPartitionCounts");
            assertThat(assignments).hasSize(4).contains(0);
            assertThat(assignments.stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);

            UUID second = appendRequest(3);
            capture("before-rebalance", second);
            waitlist1.kill();
            await("rebalanced group", Duration.ofSeconds(90), () -> members(WAITLIST) == 3);
            awaitConvergence(second);
            capture("rebalance-converged", second);
            assertThat(relay1.alive() || relay2.alive()).isTrue();

            int priorLeader = leaderFor(second);
            capture("before-leader-stop", second);
            docker("stop", brokerName(priorLeader));
            try {
                await("new leader", Duration.ofSeconds(90), () ->
                    leaderFor(second) != priorLeader && leaderFor(second) > 0);
                UUID third = appendRequest(4);
                awaitConvergence(third);
                capture("leader-down-converged", third);
            } finally {
                docker("start", brokerName(priorLeader));
            }
            await("restarted broker in ISR", Duration.ofSeconds(90), () ->
                brokerReady(3));
            capture("persistent-broker-restarted", second);

            // A full outage must leave the Product event in MySQL and expose relay failure.
            for (int node = 1; node <= 3; node++) docker("stop", brokerName(node));
            UUID fourth;
            try {
                fourth = appendRequest(5);
                recordProductHttp("product-http-during-broker-outage", product);
                capture("all-brokers-down", fourth);
                await("relay failure at the broker entrypoint", Duration.ofSeconds(35), () ->
                    logContains(relay1, "operation=kafka_relay outcome=degraded")
                        || logContains(relay2, "operation=kafka_relay outcome=degraded"));
                timeline.add(Map.of("phase", "relay-entrypoint-failure", "at", Instant.now().toString(),
                    "observation", "operation=kafka_relay outcome=degraded",
                    "relay1Observed", logContains(relay1, "operation=kafka_relay outcome=degraded"),
                    "relay2Observed", logContains(relay2, "operation=kafka_relay outcome=degraded")));
                capture("relay-failed-with-brokers-down", fourth);
                relay1.kill();
                relay2.kill();
            } finally {
                for (int node = 1; node <= 3; node++) docker("start", brokerName(node));
            }
            await("KRaft quorum after restart", Duration.ofSeconds(90), () -> brokerReady(3));
            capture("first-broker-recovery", fourth);
            ManagedProcess relayRecovered = start("relay-recovered", "relay");
            awaitConvergence(fourth);
            capture("all-brokers-recovered", fourth);

            // DB outage occurs after healthy role startup. The original is durable before the outage;
            // the test-only coordinator sends its canonical wire while JDBC is actually unavailable.
            relayRecovered.kill();
            UUID fifth = appendRequest(6);
            var stored = new TransactionTemplate(manager).execute(status ->
                records.findEventForAppend(new EventId(fifth)).orElseThrow());
            var originRow = db.queryForMap("""
                SELECT origin_request_id,origin_trace_id,origin_span_id FROM event_records WHERE event_id=?
                """, bytes(fifth));
            var origin = new ProductTelemetry.Origin((String) originRow.get("origin_request_id"),
                (String) originRow.get("origin_trace_id"), (String) originRow.get("origin_span_id"));
            var wire = mapping.encode(stored, origin);
            capture("before-db-outage", fifth);
            docker("pause", MYSQL.getContainerId());
            try {
                Map<String, Object> sent = sendWire(wire.key(), wire.body());
                timeline.add(Map.of("phase", "broker-record-during-db-outage", "at", Instant.now().toString(),
                    "coordinate", sent));
                await("real consumer JDBC failure", Duration.ofSeconds(55), () ->
                    jdbcFailureAtIntake(waitlist2()) || jdbcFailureAtIntake(waitlist3())
                        || jdbcFailureAtIntake(waitlist4()));
                timeline.add(Map.of("phase", "consumer-jdbc-entrypoint-failure",
                    "at", Instant.now().toString(), "exception", "CannotGetJdbcConnectionException",
                    "productionEntrypoint", "JdbcKafkaIntakeStore.startOffset/intake"));
                capture("db-unavailable-after-intake-entrypoint", fifth);
            } finally {
                docker("unpause", MYSQL.getContainerId());
            }
            await("first JDBC recovery", Duration.ofSeconds(30), () -> {
                try { return db.queryForObject("SELECT 1", Integer.class) == 1; }
                catch (RuntimeException unavailable) { return false; }
            });
            capture("first-db-recovery", fifth);
            for (ManagedProcess process : processes)
                if (process.label.startsWith("waitlist-") || process.label.startsWith("observer-")) process.kill();
            start("waitlist-recovered", WAITLIST);
            start("observer-recovered", OBSERVER);
            start("relay-after-db", "relay");
            awaitConvergence(fifth);
            capture("db-outage-converged", fifth);
            assertFinalOracle();
        } finally {
            for (ManagedProcess process : processes) {
                process.kill();
                timeline.add(Map.of("phase", "process-stop", "at", Instant.now().toString(),
                    "label", process.label, "pid", process.process.pid(), "exit", process.exit()));
            }
            writeEvidence();
        }
    }

    private void prepareTopicAndAuthority() throws Exception {
        try (AdminClient admin = admin()) {
            NewTopic topic = new NewTopic(TOPIC, 3, (short) 3);
            topic.configs(Map.of("min.insync.replicas", "2", "unclean.leader.election.enable", "false"));
            admin.createTopics(List.of(topic)).all().get(30, TimeUnit.SECONDS);
            String topicId = admin.describeTopics(List.of(TOPIC)).allTopicNames().get(10, TimeUnit.SECONDS)
                .get(TOPIC).topicId().toString();
            db.update("INSERT INTO event_kafka_topic_state (destination,topic_id) VALUES (?,?)", TOPIC, topicId);
        }
        for (var consumer : catalog.consumers())
            for (var route : consumer.routes()) registrations.activate(route);
        assertThat(cutover.complete("KAFKA", 100).phase()).isEqualTo("READY");
    }

    private UUID appendRequest(int index) {
        var tenant = tenants.createTenant();
        Instant start = java.time.LocalDate.now(ZoneOffset.UTC)
            .with(TemporalAdjusters.next(DayOfWeek.SUNDAY)).atTime(11, 0).toInstant(ZoneOffset.UTC);
        var venue = venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(),
            "Kafka fault " + index, "UTC", new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,
            new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(14, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10)));
        var resource = resources.createResource(new ResourceUseCase.CreateResource(
            tenant.id(), venue.id(), "Table", 4));
        var slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(
            tenant.id(), venue.id(), resource.id(), start.toString()));
        if (index == 6) {
            var customer = new AuthenticatedPrincipal(PrincipalId.newId());
            access.registerPrincipal(customer.principalId());
            candidateEntry = waitlist.register(new WaitlistUseCase.CreateRegistration(
                venue.id(), slot.id(), 2, new WaitlistRegistrationKey(UUID.nameUUIDFromBytes(
                    "slotq-109-candidate-registration".getBytes(StandardCharsets.UTF_8))), customer)).entry().id();
            candidateSlot = slot.id().value();
        }
        String payload = "{\"venueId\":\"%s\",\"resourceId\":\"%s\",\"slotInventoryId\":\"%s\"}"
            .formatted(venue.id().value(), resource.id().value(), slot.id().value());
        UUID eventId = UUID.nameUUIDFromBytes(("slotq-109-" + index).getBytes(StandardCharsets.UTF_8));
        if (index == 6) candidateEvent = eventId;
        var route = WaitlistPromotionRequestedHandler.ROUTE;
        var event = new EventEnvelope(new EventId(eventId), tenant.id(), "SlotInventory", slot.id().value(),
            route.eventType(), route.schemaVersion(), Instant.now(), payload);
        new TransactionTemplate(manager).executeWithoutResult(status -> append.appendForActiveRoute(event, route));
        originals.add(eventId);
        return eventId;
    }

    private ManagedProcess start(String label, String role) throws Exception {
        boolean relay = role.equals("relay");
        boolean product = role.equals("product");
        List<String> args = new ArrayList<>(List.of("-cp", System.getProperty("java.class.path"),
            "com.slotq.SlotqApplication", "--spring.main.web-application-type="
                + (product ? "servlet" : "none"),
            "--server.port=0",
            "--spring.kafka.bootstrap-servers=" + BOOTSTRAP,
            "--slotq.events.kafka.destination=" + TOPIC,
            "--slotq.events.delivery.authority-epoch=2",
            "--slotq.events.runtime-role=" + (relay ? "relay" : product ? "product" : "consumer"),
            "--slotq.events.kafka.relay-enabled=" + relay,
            "--slotq.events.kafka.consumer-enabled=" + (!relay && !product),
            "--slotq.events.kafka.business-enabled=" + product,
            "--slotq.events.delivery.scheduler-enabled=" + (!relay && !product),
            "--slotq.events.delivery.transport=" + (product ? "DB_DIRECT" : "KAFKA"),
            "--slotq.events.delivery.consumer-id=" + (relay || product ? WAITLIST : role),
            "--slotq.waitlist.promotion.enabled=" + (product || role.equals(WAITLIST)),
            "--slotq.waitlist.promotion.maintenance-enabled=" + product,
            "--slotq.operations.observation.enabled=" + role.equals(OBSERVER),
            "--spring.datasource.hikari.connection-timeout=3000",
            "--spring.datasource.hikari.data-source-properties.socketTimeout=3000"));
        Path argfile = processLogs.resolve(label + ".args");
        StringBuilder content = new StringBuilder();
        for (String arg : args) content.append('"').append(arg.replace("\\", "/")).append('"').append('\n');
        Files.writeString(argfile, content.toString());
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java")
            .toString(), "@" + argfile).redirectErrorStream(true)
            .redirectOutput(processLogs.resolve(label + ".log").toFile());
        builder.environment().put("SPRING_DATASOURCE_URL", MYSQL.getJdbcUrl());
        builder.environment().put("SPRING_DATASOURCE_USERNAME", MYSQL.getUsername());
        builder.environment().put("SPRING_DATASOURCE_PASSWORD", MYSQL.getPassword());
        ManagedProcess started = new ManagedProcess(label, builder.start());
        processes.add(started);
        timeline.add(Map.of("phase", "process-start", "at", Instant.now().toString(),
            "role", role, "pid", started.process.pid(), "label", label));
        return started;
    }

    private void awaitConvergence(UUID eventId) throws Exception {
        try {
            await("both durable targets DONE for " + eventId, Duration.ofSeconds(60), () ->
                count("SELECT COUNT(*) FROM event_deliveries WHERE event_id=? AND state='DONE'", eventId) == 2
                    && count("SELECT COUNT(*) FROM event_kafka_publications WHERE event_id=? AND state='PUBLISHED'",
                        eventId) == 1);
        } catch (AssertionError timeout) {
            capture("convergence-timeout", eventId);
            throw timeout;
        }
        assertThat(count("SELECT COUNT(*) FROM waitlist_promotion_receipts WHERE event_id=?", eventId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM event_observation_projections WHERE event_id=?", eventId))
            .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM event_kafka_target_intakes WHERE event_id=?", eventId))
            .isEqualTo(2);
    }

    private Map<String, Object> capture(String phase, UUID eventId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("phase", phase);
        snapshot.put("at", Instant.now().toString());
        snapshot.put("eventId", eventId.toString());
        snapshot.put("processes", processes.stream().map(p -> Map.of("label", p.label,
            "pid", p.process.pid(), "alive", p.alive(), "exit", p.exit())).toList());
        try {
            snapshot.put("mysqlVersion", db.queryForObject("SELECT VERSION()", String.class));
            snapshot.put("event", db.queryForList("""
                SELECT HEX(tenant_id) tenantId,HEX(event_id) eventId,boundary_sequence boundarySequence,
                       event_type eventType,schema_version schemaVersion,HEX(aggregate_id) aggregateId,
                       SHA2(payload,256) canonicalPayloadSha256 FROM event_records WHERE event_id=?
                """, bytes(eventId)));
            snapshot.put("publication", db.queryForList("""
                SELECT state,cycle_attempts,lifetime_attempts,fencing_token,ack_partition,ack_offset,failure_code
                  FROM event_kafka_publications WHERE event_id=?
                """, bytes(eventId)));
            snapshot.put("intake", db.queryForList("""
                SELECT consumer_id consumerId,partition_id partitionId,record_offset recordOffset,
                       disposition,failure_code failureCode,HEX(record_sha256) recordSha256,
                       HEX(registration_id) registrationId FROM event_kafka_intake_records WHERE event_id=?
                 ORDER BY consumer_id,partition_id,record_offset
                """, bytes(eventId)));
            snapshot.put("targetIntake", db.queryForList("""
                SELECT consumer_id consumerId,partition_id partitionId,first_offset firstOffset,
                       authority_epoch authorityEpoch,first_materialization firstMaterialization,
                       HEX(registration_id) registrationId
                  FROM event_kafka_target_intakes WHERE event_id=? ORDER BY consumer_id
                """, bytes(eventId)));
            snapshot.put("delivery", db.queryForList("""
                SELECT r.consumer_id consumerId,d.state,d.cycle_attempts cycleAttempts,
                       d.lifetime_attempts lifetimeAttempts,d.fencing_token fencingToken,d.failure_code failureCode,
                       HEX(d.registration_id) registrationId
                  FROM event_deliveries d JOIN event_registrations r ON r.registration_id=d.registration_id
                 WHERE d.event_id=? ORDER BY r.consumer_id
                """, bytes(eventId)));
            snapshot.put("receipt", db.queryForList("""
                SELECT consumer_id consumerId,outcome FROM waitlist_promotion_receipts WHERE event_id=?
                """, bytes(eventId)));
            snapshot.put("projectionCount", count("SELECT COUNT(*) FROM event_observation_projections WHERE event_id=?",
                eventId));
            snapshot.put("business", Map.of(
                "offers", count("SELECT COUNT(*) FROM waitlist_offers WHERE tenant_id="
                    + "(SELECT tenant_id FROM event_records WHERE event_id=?)", eventId),
                "promotionalReservations", count("SELECT COUNT(*) FROM reservations r JOIN"
                    + " waitlist_promotion_receipts w ON w.reservation_id=r.id WHERE w.event_id=?", eventId),
                "activeAllocations", count("SELECT COUNT(*) FROM capacity_allocations a JOIN reservations r"
                    + " ON r.id=a.reservation_id JOIN waitlist_promotion_receipts w"
                    + " ON w.reservation_id=r.id WHERE w.event_id=? AND a.active=TRUE", eventId),
                "notifications", count("SELECT COUNT(*) FROM waitlist_notification_requests WHERE tenant_id="
                    + "(SELECT tenant_id FROM event_records WHERE event_id=?)", eventId)));
            snapshot.put("durablePositions", db.queryForList("""
                SELECT consumer_id consumerId,partition_id partitionId,last_durable_offset lastDurableOffset
                  FROM event_kafka_consumer_positions WHERE topic=? ORDER BY consumer_id,partition_id
                """, TOPIC));
        } catch (RuntimeException failure) {
            snapshot.put("dbUnavailable", failure.getClass().getSimpleName());
        }
        snapshot.put("broker", brokerSnapshot());
        if (phase.equals("before-leader-stop") || phase.equals("leader-down-converged")
            || phase.equals("persistent-broker-restarted") || phase.equals("all-brokers-recovered"))
            snapshot.put("kraftQuorum", quorumStatus());
        timeline.add(snapshot);
        return snapshot;
    }

    private Map<String, Object> brokerSnapshot() {
        try (AdminClient admin = admin()) {
            var topic = admin.describeTopics(List.of(TOPIC)).allTopicNames().get(5, TimeUnit.SECONDS).get(TOPIC);
            List<Map<String, Object>> partitions = topic.partitions().stream().map(p -> Map.<String, Object>of(
                "partition", p.partition(), "leader", p.leader().id(), "replicas", p.replicas().stream()
                    .map(node -> node.id()).toList(), "isr", p.isr().stream().map(node -> node.id()).toList())).toList();
            Map<String, Object> groups = new LinkedHashMap<>();
            for (String consumer : List.of(WAITLIST, OBSERVER)) {
                String group = catalog.definition(consumer).groupId();
                try {
                    var description = admin.describeConsumerGroups(List.of(group)).describedGroups()
                        .get(group).get(5, TimeUnit.SECONDS);
                    Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets =
                        admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS);
                    Map<String, Object> positions = new LinkedHashMap<>();
                    offsets.forEach((partition, offset) -> {
                        if (partition.topic().equals(TOPIC))
                            positions.put(partition.topic() + ":" + partition.partition(), offset.offset());
                    });
                    groups.put(consumer, Map.of("groupId", group,
                        "memberPartitionCounts", description.members().stream().map(member ->
                            member.assignment().topicPartitions().size()).toList(), "offsets", positions));
                } catch (Exception absent) {
                    groups.put(consumer, Map.of("groupId", group, "unavailable", absent.getClass().getSimpleName()));
                }
            }
            return Map.of("available", true, "topicId", topic.topicId().toString(),
                "partitions", partitions, "groups", groups);
        } catch (Exception unavailable) {
            return Map.of("available", false, "failure", unavailable.getClass().getSimpleName());
        }
    }

    private int members(String consumer) {
        try (AdminClient admin = admin()) {
            String group = catalog.definition(consumer).groupId();
            return admin.describeConsumerGroups(List.of(group)).describedGroups().get(group)
                .get(5, TimeUnit.SECONDS).members().size();
        } catch (Exception unavailable) { return -1; }
    }

    private int leaderFor(UUID eventId) {
        try {
            Integer partition = db.queryForObject("SELECT ack_partition FROM event_kafka_publications WHERE event_id=?",
                Integer.class, bytes(eventId));
            try (AdminClient admin = admin()) {
                return admin.describeTopics(List.of(TOPIC)).allTopicNames().get(5, TimeUnit.SECONDS)
                    .get(TOPIC).partitions().get(partition).leader().id();
            }
        } catch (Exception unavailable) { return -1; }
    }

    private boolean brokerReady(int expectedIsr) {
        Map<String, Object> state = brokerSnapshot();
        if (!Boolean.TRUE.equals(state.get("available"))) return false;
        @SuppressWarnings("unchecked") List<Map<String, Object>> partitions =
            (List<Map<String, Object>>) state.get("partitions");
        return partitions.size() == 3 && partitions.stream().allMatch(p ->
            ((List<?>) p.get("isr")).size() >= expectedIsr);
    }

    private void assertFinalOracle() {
        for (UUID eventId : originals) {
            assertThat(count("SELECT COUNT(*) FROM event_records WHERE event_id=?", eventId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM event_kafka_publications WHERE event_id=? AND state='PUBLISHED'",
                eventId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM event_deliveries WHERE event_id=? AND state='DONE'",
                eventId)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM event_kafka_target_intakes WHERE event_id=?", eventId))
                .isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM waitlist_promotion_receipts WHERE event_id=?", eventId))
                .isEqualTo(1);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE state='DEAD'", Long.class))
            .isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_kafka_intake_records WHERE disposition='QUARANTINED'",
            Long.class)).isZero();
        assertThat(db.queryForObject("SELECT outcome FROM waitlist_promotion_receipts WHERE event_id=?",
            String.class, bytes(candidateEvent))).isEqualTo("PROMOTED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM waitlist_offers WHERE entry_id=?",
            Long.class, bytes(candidateEntry))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM reservations WHERE promotional_request_id=?",
            Long.class, bytes(candidateEntry))).isEqualTo(1);
        assertThat(db.queryForObject("""
            SELECT COUNT(*) FROM capacity_allocations a
              JOIN reservations r ON r.id=a.reservation_id
             WHERE r.slot_inventory_id=? AND a.active=TRUE
            """, Long.class, bytes(candidateSlot))).isEqualTo(1);
    }

    private void writeEvidence() throws Exception {
        if (output == null) return;
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("schemaVersion", "slotq-kafka-fault-process/v1");
        raw.put("seed", 109);
        raw.put("runId", RUN_ID);
        raw.put("bootstrap", BOOTSTRAP);
        raw.put("clientVersion", org.apache.kafka.common.utils.AppInfoParser.getVersion());
        raw.put("mysqlImage", "mysql:8.4");
        raw.put("brokerImage", "apache/kafka:4.1.1");
        raw.put("brokerVersion", command(Duration.ofSeconds(8), "docker", "exec", brokerName(1),
            "/opt/kafka/bin/kafka-topics.sh", "--version"));
        raw.put("mysqlContainer", MYSQL.getContainerId());
        raw.put("mysqlVolume", command(Duration.ofSeconds(8), "docker", "inspect", "--format",
            "{{json .Mounts}}", MYSQL.getContainerId()));
        raw.put("brokerVolumes", brokerVolumes());
        raw.put("revision", command(Duration.ofSeconds(5), "git", "-c", "safe.directory=C:/dev/slotq",
            "rev-parse", "HEAD"));
        raw.put("workingTreeStatus", command(Duration.ofSeconds(5), "git", "-c",
            "safe.directory=C:/dev/slotq", "status", "--short"));
        raw.put("mainClassesSha256", treeSha256(Path.of("build", "classes", "java", "main")));
        raw.put("testHarnessSha256", treeSha256(Path.of("build", "classes", "java", "test")));
        raw.put("settings", Map.of(
            "broker", "3 KRaft nodes; RF=3; min.insync.replicas=2; unclean election=false; acks=all",
            "consumer", "poll interval PT1S; max.poll.records=8; max.poll.interval.ms=300000;"
                + " session.timeout.ms=15000; heartbeat.interval.ms=5000; auto.offset.reset=none; auto.commit=false",
            "jdbc", "Hikari connection timeout 3000ms; MySQL socketTimeout 3000ms; REPEATABLE-READ",
            "process", "separate product, two relays, waitlist replicas, and observer JVMs",
            "window", "each event must reach two DONE targets within 120s"));
        raw.put("originalEventIds", originals.stream().map(UUID::toString).toList());
        raw.put("candidate", Map.of("eventId", candidateEvent.toString(),
            "entryId", candidateEntry.toString(), "slotId", candidateSlot.toString(),
            "slotCapacity", 1));
        raw.put("timeline", timeline);
        Files.writeString(output.resolve("process-raw.json"), json.writeValueAsString(raw));
    }

    private long count(String sql, UUID eventId) {
        return db.queryForObject(sql, Long.class, bytes(eventId));
    }

    private AdminClient admin() {
        return AdminClient.create(Map.of("bootstrap.servers", BOOTSTRAP,
            "request.timeout.ms", "5000", "default.api.timeout.ms", "5000"));
    }

    private void await(String description, Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(500);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private void docker(String action, String container) throws Exception {
        Process command = new ProcessBuilder("docker", action, container).redirectErrorStream(true).start();
        if (!command.waitFor(30, TimeUnit.SECONDS) || command.exitValue() != 0)
            throw new AssertionError("Docker " + action + " failed for " + container);
        timeline.add(Map.of("phase", "docker-" + action, "at", Instant.now().toString(),
            "container", container, "exit", command.exitValue()));
    }

    private Map<String, Object> sendWire(String key, String body) throws Exception {
        Map<String, Object> config = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            var sent = producer.send(new ProducerRecord<>(TOPIC, key, body)).get(20, TimeUnit.SECONDS);
            return Map.of("topic", sent.topic(), "partition", sent.partition(), "offset", sent.offset());
        }
    }

    private ManagedProcess waitlist2() { return processes.stream().filter(p -> p.label.equals("waitlist-2"))
        .findFirst().orElseThrow(); }
    private ManagedProcess waitlist3() { return processes.stream().filter(p -> p.label.equals("waitlist-3"))
        .findFirst().orElseThrow(); }
    private ManagedProcess waitlist4() { return processes.stream().filter(p -> p.label.equals("waitlist-4"))
        .findFirst().orElseThrow(); }

    private String quorumStatus() {
        for (int node = 1; node <= 3; node++) {
            String result = command(Duration.ofSeconds(8), "docker", "exec", brokerName(node),
                "/opt/kafka/bin/kafka-metadata-quorum.sh", "--bootstrap-controller",
                "kafka-" + node + ":9093", "describe", "--status");
            if (result.contains("ClusterId:")) return result;
        }
        return "unavailable";
    }

    private Map<String, String> brokerVolumes() {
        Map<String, String> mounts = new LinkedHashMap<>();
        for (int node = 1; node <= 3; node++) mounts.put(brokerName(node),
            command(Duration.ofSeconds(8), "docker", "inspect", "--format", "{{json .Mounts}}",
                brokerName(node)));
        return mounts;
    }

    private String command(Duration timeout, String... args) {
        try {
            Process command = new ProcessBuilder(args).redirectErrorStream(true).start();
            if (!command.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                command.destroyForcibly();
                return "timeout";
            }
            String body = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return command.exitValue() == 0 ? body.strip() : "exit=" + command.exitValue();
        } catch (Exception unavailable) { return unavailable.getClass().getSimpleName(); }
    }

    private String treeSha256(Path root) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var paths = Files.walk(root)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update(Files.readAllBytes(file));
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) { throw new IllegalStateException("Cannot hash source tree", failure); }
    }

    private boolean logContains(ManagedProcess process, String marker) {
        try { return Files.readString(processLogs.resolve(process.label + ".log")).contains(marker); }
        catch (Exception unavailable) { return false; }
    }

    private boolean jdbcFailureAtIntake(ManagedProcess process) {
        return logContains(process, "CannotGetJdbcConnectionException")
            && (logContains(process, "JdbcKafkaIntakeStore.startOffset")
                || logContains(process, "JdbcKafkaIntakeStore.intake"));
    }

    private int publicVenueStatus(ManagedProcess product) {
        try {
            var found = Pattern.compile("Tomcat started on port ([0-9]+)")
                .matcher(Files.readString(processLogs.resolve(product.label + ".log")));
            if (!found.find()) return -1;
            int port = Integer.parseInt(found.group(1));
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/venues")).timeout(Duration.ofSeconds(2)).GET().build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception unavailable) { return -1; }
    }

    private void recordProductHttp(String phase, ManagedProcess product) {
        int status = publicVenueStatus(product);
        timeline.add(Map.of("phase", phase, "at", Instant.now().toString(),
            "pid", product.process.pid(), "status", status, "route", "GET /api/v1/venues"));
        assertThat(status).isEqualTo(200);
    }

    private static String brokerName(int id) { return "slotq-kafka-fault-kafka-" + id + "-1"; }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
            .array();
    }

    private static final class ManagedProcess {
        private final String label;
        private final Process process;
        private ManagedProcess(String label, Process process) { this.label = label; this.process = process; }
        private boolean alive() { return process.isAlive(); }
        private int exit() { return process.isAlive() ? -1 : process.exitValue(); }
        private void kill() {
            if (process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(10, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }
}
