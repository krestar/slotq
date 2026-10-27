package com.slotq.mcp;

import com.slotq.ai.runtime.AgentRuntime;
import com.slotq.auth.domain.*;
import com.slotq.booking.application.*;
import com.slotq.booking.domain.*;
import com.slotq.events.application.*;
import com.slotq.experiments.waitlist.WaitlistRecoveryDatabase;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.waitlist.application.*;
import com.zaxxer.hikari.HikariDataSource;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import static com.slotq.mcp.M8CapacityEvidence.*;
import static com.slotq.mcp.RepresentativeFlowIntegrationTests.bytes;
import static org.awaitility.Awaitility.await;

/** Opt-in #182 drill inside the unchanged #181 support fixture. Secrets stay in memory. */
final class M8RecoveryDrill {
    private static final String MONITOR = "recovery_" + UUID.randomUUID() + UUID.randomUUID();
    private static final List<Map<String, Object>> SQL_FAILURES = new CopyOnWriteArrayList<>();
    private static volatile UUID poisonSlot;
    private final ConfigurableApplicationContext application;
    private final RepresentativeFlowIntegrationTests f;
    private final JdbcTemplate db;
    private final AgentRuntime runtime;
    private final MySQLContainer mysql;
    private final Path output;
    private final HttpClient http;
    private final List<Map<String, Object>> timeline = new ArrayList<>();
    private GenericContainer<?> prometheus;

    M8RecoveryDrill(ConfigurableApplicationContext application, RepresentativeFlowIntegrationTests f,
            JdbcTemplate db, AgentRuntime runtime, MySQLContainer mysql, Path output) throws Exception {
        this.application = application;
        this.f = f;
        this.db = db;
        this.runtime = runtime;
        this.mysql = mysql;
        this.output = output;
        require(application.getEnvironment().getProperty("slotq.operations.recovery.enabled", Boolean.class, false),
            "HTTPS operations recovery must be enabled in the synthetic fixture");
        var keys = java.security.KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(McpHttpIntegrationTests.KEYSTORE)) {
            keys.load(input, McpHttpIntegrationTests.PASSWORD.toCharArray());
        }
        var trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keys);
        var tls = javax.net.ssl.SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);
        http = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).build();
    }

    static boolean enabled() { return Boolean.getBoolean("slotq.capacity.recovery"); }

    static void validatePlan(int pool, int size, int rate) throws Exception {
        readPlan(Path.of(System.getProperty("slotq.capacity.recoveryPlan", "")),
            System.getProperty("slotq.capacity.recoveryDigest", ""), pool, size, rate, Instant.now());
    }

    static JsonNode readPlan(Path path, String expectedDigest, int pool, int size, int rate, Instant now) throws Exception {
        require(Files.isRegularFile(path) && digest(path).equalsIgnoreCase(expectedDigest), "Missing or changed frozen recovery plan");
        var plan = JSON.readTree(Files.readString(path));
        require(plan.path("schemaVersion").asString().equals("slotq-m8-recovery-plan/v1")
            && plan.path("state").asString().equals("FROZEN")
            && Instant.parse(plan.path("frozenAt").asString()).isBefore(now), "Recovery plan must be frozen before execution");
        boolean matched = false;
        for (var profile : plan.path("supportInputs")) {
            matched |= profile.path("pool").asInt() == pool && profile.path("size").asInt() == size
                && profile.path("rate").asInt() == rate && profile.path("seed").asLong() == 18101
                && profile.path("criteriaDigest").asString().matches("[0-9a-f]{64}");
        }
        require(matched, "Recovery profile differs from #181 handoff");
        var gates = plan.path("gates");
        require(gates.path("detectionMillis").asLong() == 45000 && gates.path("restartReadyMillis").asLong() == 60000
            && gates.path("durableConvergenceMillis").asLong() == 90000 && gates.path("postRecoveryQuietMillis").asLong() == 15000,
            "Recovery time criteria changed; use a new protocol and fresh evidence");
        for (String key : List.of("partialEffects", "duplicateEffects", "capacityViolations", "fifoViolations", "missingAudit", "automaticMutationAfterRestart")) {
            require(gates.has(key) && gates.path(key).asLong() == 0, "Missing or relaxed consistency criterion " + key);
        }
        return plan;
    }

    static Map<String, Object> properties(MySQLContainer mysql) {
        if (!enabled()) return Map.of();
        return Map.of("slotq.operations.recovery.enabled", true,
            "slotq.observability.scrape-token", MONITOR,
            "slotq.observability.database.enabled", true,
            "slotq.observability.database.jdbc-url", mysql.getJdbcUrl(),
            "slotq.observability.database.username", "m8_observer",
            "slotq.observability.database.password", MONITOR,
            "slotq.observability.database.interval", "PT1S",
            "slotq.observability.database.stale-after", "PT3S");
    }

    static void prepareObserver(MySQLContainer mysql) throws Exception {
        if (!enabled()) return;
        try (var connection = java.sql.DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE USER 'm8_observer'@'%' IDENTIFIED BY '" + MONITOR + "'");
            statement.execute("GRANT SELECT ON " + mysql.getDatabaseName() + ".* TO 'm8_observer'@'%'");
            statement.execute("GRANT SELECT ON performance_schema.* TO 'm8_observer'@'%'");
            statement.execute("GRANT PROCESS ON *.* TO 'm8_observer'@'%'");
        }
    }

    static void authFailure(String method, Throwable failure) {
        if (!enabled()) return;
        var classes = new ArrayList<String>();
        boolean sql = false;
        for (int i = 0; failure != null && i < 32; i++, failure = failure.getCause()) {
            classes.add(failure.getClass().getName());
            sql |= failure instanceof java.sql.SQLException;
        }
        if (sql) SQL_FAILURES.add(Map.of("method", method, "at", Instant.now().toString(),
            "nanos", System.nanoTime(), "exceptionClasses", classes));
    }

    static void beforeHandler(StoredEvent event) {
        if (poisonSlot != null && event.envelope().payload().contains(poisonSlot.toString())) {
            throw new EventHandlingException(DeliveryFailure.UNCLASSIFIED_FAILURE);
        }
    }

    void run() throws Exception {
        boolean complete = false;
        record("started", Map.of("supportPool", application.getBean(HikariDataSource.class).getMaximumPoolSize(),
            "size", Integer.getInteger("slotq.capacity.size", 16), "rate", Integer.getInteger("slotq.capacity.rate", 20),
            "mysqlContainer", mysql.getContainerId(), "externalProviderCalls", 0));
        try {
            startPrometheus();
            outage();
            operator();
            var snapshot = new WaitlistRecoveryDatabase(db).snapshot(f.tenant, Instant.now());
            var stats = (Map<?, ?>) snapshot.get("stats");
            for (String invariant : List.of("partialReceipts", "duplicateLogicalEffects", "occupancyViolations", "dead")) {
                require(((Number) stats.get(invariant)).longValue() == 0, "Final durable invariant: " + invariant);
            }
            require(db.queryForObject("SELECT COUNT(*) FROM waitlist_promotion_receipts p "
                + "JOIN event_records e ON e.event_id=p.event_id AND e.tenant_id=p.tenant_id JOIN event_registrations r "
                + "ON r.consumer_id=p.consumer_id AND r.event_type=e.event_type AND r.schema_version=e.schema_version "
                + "AND e.boundary_sequence>r.activation_boundary AND (r.deactivation_boundary IS NULL OR e.boundary_sequence<r.deactivation_boundary) "
                + "LEFT JOIN event_deliveries d ON d.tenant_id=p.tenant_id AND d.event_id=p.event_id AND d.registration_id=r.registration_id "
                + "WHERE p.tenant_id=? AND (d.state IS NULL OR d.state<>'DONE')", Integer.class, bytes(f.tenant)) == 0,
                "Receipt without matching DONE");
            record("final-durable-oracle", Map.of("database", snapshot));
            complete = true;
        } finally {
            poisonSlot = null;
            if (prometheus != null) prometheus.close();
            write(output.resolve("recovery.json"), Map.of("schemaVersion", "slotq-m8-recovery/v1",
                "kind", "EXECUTION", "complete", complete, "timeline", timeline,
                "planDigest", System.getProperty("slotq.capacity.recoveryDigest", "unbound-diagnostic"),
                "limitations", List.of("single local JVM/Docker host and synthetic actors; no HA/SLA",
                    "process kill and scheduler backlog are separate fresh existing recovery harnesses",
                    "no external provider, backup restore, binary rollback or final release verdict")));
        }
    }

    private void outage() throws Exception {
        UUID target = slot("Actual Product outage");
        var before = new WaitlistRecoveryDatabase(db).snapshot(f.tenant, Instant.now());
        String key = UUID.randomUUID().toString();
        var credential = f.access.issueHold(f.customer.delegation().delegationId(), target, 2, key, Instant.now().plusSeconds(120));
        var request = hold(target, key, credential.value());
        SQL_FAILURES.clear();
        record("before-database-outage", Map.of("database", before, "target", target.toString()));
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(mysql.getContainerId()).exec();
        record("database-paused", Map.of());
        try {
            Integer status = null;
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                status = response.statusCode();
                require(status >= 500, "DB outage unexpectedly accepted Product request");
            } catch (HttpTimeoutException responseUnknown) {
                // The caller stopped waiting. The server's actual SQL failure is observed independently below.
            }
            await().atMost(Duration.ofSeconds(15)).until(() ->
                SQL_FAILURES.stream().anyMatch(r -> "authenticateProduct".equals(r.get("method"))));
            require(SQL_FAILURES.stream().anyMatch(r -> "authenticateProduct".equals(r.get("method"))),
                "No real SQL failure on the Product HTTP authentication path");
            record("product-database-failure", Map.of("httpStatus", status == null ? "UNOBSERVED" : status,
                "callerOutcome", "unknown; status alone does not establish rollback", "sqlFailures", List.copyOf(SQL_FAILURES)));
            alert("SlotqDatabaseSampleUnavailable", true);
        } finally {
            docker.unpauseContainerCmd(mysql.getContainerId()).exec();
            record("database-unpaused", Map.of());
        }
        await().ignoreExceptionsInstanceOf(org.springframework.dao.DataAccessException.class)
            .atMost(Duration.ofSeconds(15)).untilAsserted(() ->
            require(db.queryForObject("SELECT 1", Integer.class) == 1, "MySQL reconnect"));
        record("database-reconnected", Map.of("targetReservations", count("reservations", target),
            "targetAllocations", count("capacity_allocations", target), "targetLedger", count("hold_idempotency_records", target)));
        require(count("reservations", target) == 0 && count("capacity_allocations", target) == 0
            && count("hold_idempotency_records", target) == 0, "Outage left partial Product state");
        await().ignoreExceptionsMatching(failure -> failure instanceof com.slotq.auth.access.AccessFailure access
                && access.reason() == com.slotq.auth.access.AccessFailure.Reason.UNAVAILABLE)
            .atMost(Duration.ofSeconds(15)).until(() -> {
                f.access.requireBoundedExecution();
                return f.access.revalidate(f.customer.delegation().delegationId()) != null;
            });
        record("auth-read-ready", Map.of("session", f.db.queryForMap("SELECT CONNECTION_ID() connection_id,"
            + "@@SESSION.innodb_lock_wait_timeout row_wait,@@SESSION.lock_wait_timeout metadata_wait,"
            + "@@SESSION.wait_timeout idle_wait,@@SESSION.max_execution_time select_wait")));
        // A new, explicitly requested operation; the failed mutation/key is never retransmitted.
        String newKey = UUID.randomUUID().toString();
        var current = f.access.issueHold(f.customer.delegation().delegationId(), target, 2, newKey, Instant.now().plusSeconds(30));
        var healthy = http.send(hold(target, newKey, current.value()), HttpResponse.BodyHandlers.ofString());
        require(healthy.statusCode() == 201 && count("reservations", target) == 1
            && count("capacity_allocations", target) == 1 && count("hold_idempotency_records", target) == 1,
            "New Product request did not recover with one durable effect");
        var flows = new com.slotq.ai.flow.RepresentativeFlows(runtime, f.limits(Duration.ofSeconds(3)));
        var read = flows.management(f.owner.delegation().value(), f.day, null);
        var result = flows.prepareReads(read, f.owner.delegation().value());
        require(result.tools().stream().anyMatch(t -> t.tool().equals("management.reservations.list")
            && t.structured() != null), "Post-outage MCP query not observed");
        quiet();
        alert("SlotqDatabaseSampleUnavailable", false);
        record("database-converged", Map.of("productStatus", healthy.statusCode(), "newExplicitOperation", true,
            "mcpTools", result.tools().stream().map(t -> t.tool()).toList(),
            "database", new WaitlistRecoveryDatabase(db).snapshot(f.tenant, Instant.now())));
    }

    private void operator() throws Exception {
        UUID target = slot("Individual operator recovery");
        poisonSlot = target;
        var principal = new AuthenticatedPrincipal(new PrincipalId(f.customer.principal()));
        var held = f.reservations.createHold(new ReservationUseCase.CreateHold(f.venue, new SlotInventoryId(target), principal, 2)).reservation();
        f.reservations.transition(f.venue, held.id(), principal, ReservationCommand.CONFIRM);
        var entries = new ArrayList<UUID>();
        for (int i = 0; i < 3; i++) {
            var customer = f.identity("CUSTOMER", f.tenant, f.venue);
            var registration = application.getBean(WaitlistUseCase.class).register(new WaitlistUseCase.CreateRegistration(
                f.venue, new SlotInventoryId(target), 2, new WaitlistRegistrationKey(UUID.randomUUID()),
                new AuthenticatedPrincipal(new PrincipalId(customer.principal()))));
            entries.add(registration.entry().id());
        }
        f.reservations.transition(f.venue, held.id(), principal, ReservationCommand.CANCEL);
        String sql = "SELECT d.event_id,d.registration_id FROM event_deliveries d JOIN event_records e ON e.event_id=d.event_id "
            + "JOIN event_registrations r ON r.registration_id=d.registration_id WHERE d.tenant_id=? "
            + "AND r.consumer_id='waitlist.promotion' AND d.state='DEAD' AND e.payload LIKE ?";
        await().atMost(Duration.ofSeconds(45)).until(() -> !db.queryForList(sql, bytes(f.tenant), "%" + target + "%").isEmpty());
        var dead = db.queryForMap(sql + " ORDER BY e.boundary_sequence LIMIT 1", bytes(f.tenant), "%" + target + "%");
        UUID event = uuid((byte[]) dead.get("event_id"));
        UUID registration = uuid((byte[]) dead.get("registration_id"));
        record("dead-observed", Map.of("target", target.toString(), "event", event.toString(), "registration", registration.toString()));
        alert("SlotqDeadDelivery", true);
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        String token = "sqop_" + HexFormat.of().formatHex(secret);
        UUID operator = UUID.randomUUID(), credential = UUID.randomUUID(), operation = UUID.randomUUID();
        db.update("INSERT INTO operations_operators(operator_id,principal_reference) VALUES (?,?)", bytes(operator), "m8-individual-" + operator);
        db.update("INSERT INTO operations_credentials(credential_id,operator_id,token_hash,expires_at) VALUES (?,?,UNHEX(SHA2(?,256)),TIMESTAMPADD(HOUR,1,UTC_TIMESTAMP(6)))",
            bytes(credential), bytes(operator), token);
        for (String action : List.of("READ", "BUSINESS_REPLAY")) {
            db.update("INSERT INTO operations_grants(operator_id,tenant_id,consumer_id,action) VALUES (?,?,?,?)",
                bytes(operator), bytes(f.tenant), "waitlist.promotion", action);
        }
        String base = "/internal/operations/tenants/" + f.tenant + "/consumers/waitlist.promotion";
        String exact = base + "/deliveries/" + event + "/" + registration;
        int machineStatus = call("GET", exact, MONITOR, null).statusCode();
        require(machineStatus == 401, "Expected operator authentication rejection; actual HTTP " + machineStatus);
        var view = body(call("GET", exact, token, null), 200);
        require(view.path("state").asString().equals("DEAD") && view.path("transport").asString().equals("DB_DIRECT")
            && view.path("eventId").asString().equals(event.toString()) && view.path("registrationId").asString().equals(registration.toString())
            && view.path("consumerId").asString().equals("waitlist.promotion"), "Exact operator target mismatch");
        var command = new LinkedHashMap<String, Object>(Map.of("operationId", operation.toString(), "reason", "controlled handler fault removed",
            "expectedState", "DEAD", "expectedFence", view.path("fencingToken").asLong(),
            "expectedTransport", "DB_DIRECT", "expectedAuthorityEpoch", view.path("authorityEpoch").asLong()));
        var stale = new LinkedHashMap<>(command);
        stale.put("expectedFence", view.path("fencingToken").asLong() + 1);
        require(call("POST", exact + "/replay", token, stale).statusCode() == 409, "Stale fence not rejected");
        poisonSlot = null;
        record("operator-recovery-started", Map.of("operatorId", operator.toString(), "credentialId", credential.toString(),
            "operationId", operation.toString(), "exactRead", view));
        var admitted = body(call("POST", exact + "/replay", token, command), 200);
        require(body(call("POST", exact + "/replay", token, command), 200).equals(admitted), "Same operation opened another recovery cycle");
        body(call("GET", base + "/operations/" + operation, token, null), 200);
        await().atMost(Duration.ofSeconds(90)).until(() -> body(call("GET", exact, token, null), 200).path("state").asString().equals("DONE"));
        var audit = db.queryForMap("SELECT HEX(operator_id) operator_id,HEX(tenant_id) tenant_id,consumer_id,result_json FROM operations_recovery_audit WHERE operation_id=?", bytes(operation));
        require(audit.get("operator_id").toString().equalsIgnoreCase(operator.toString().replace("-", ""))
            && audit.get("tenant_id").toString().equalsIgnoreCase(f.tenant.toString().replace("-", ""))
            && audit.get("consumer_id").equals("waitlist.promotion"), "Atomic individual audit mismatch");
        require(JSON.readTree(audit.get("result_json").toString()).equals(admitted), "Operation result and atomic audit differ");
        require(admitted.path("operationId").asString().equals(operation.toString())
            && admitted.path("eventId").asString().equals(event.toString())
            && admitted.path("registrationId").asString().equals(registration.toString())
            && admitted.path("transport").asString().equals("DB_DIRECT")
            && admitted.path("authorityEpoch").asLong() == view.path("authorityEpoch").asLong()
            && admitted.path("priorFence").asLong() == view.path("fencingToken").asLong()
            && !admitted.path("correlationId").asString().isBlank(), "Exact recovery authority/audit identity mismatch");
        var durable = db.queryForMap("SELECT cycle_attempts,lifetime_attempts,fencing_token FROM event_deliveries WHERE tenant_id=? AND event_id=? AND registration_id=?",
            bytes(f.tenant), bytes(event), bytes(registration));
        require(((Number) durable.get("lifetime_attempts")).longValue() == view.path("lifetimeAttempts").asLong() + 1
            && ((Number) durable.get("fencing_token")).longValue() > view.path("fencingToken").asLong(), "Replay reset lifetime/fence");
        require(db.queryForObject("SELECT COUNT(*) FROM waitlist_promotion_receipts WHERE tenant_id=? AND event_id=? AND outcome IS NOT NULL",
            Integer.class, bytes(f.tenant), bytes(event)) == 1, "Missing durable receipt");
        require(db.queryForObject("SELECT COUNT(*) FROM waitlist_offers WHERE tenant_id=? AND entry_id=?",
            Integer.class, bytes(f.tenant), bytes(entries.getFirst())) == 1, "Eligible FIFO head was not offered");
        require(db.queryForObject("SELECT COUNT(*) FROM waitlist_offers WHERE tenant_id=? AND entry_id IN (?,?)",
            Integer.class, bytes(f.tenant), bytes(entries.get(1)), bytes(entries.get(2))) == 0, "FIFO or active Offer invariant violated");
        db.update("UPDATE operations_credentials SET revoked_at=UTC_TIMESTAMP(6) WHERE credential_id=?", bytes(credential));
        require(call("GET", exact, token, null).statusCode() == 401, "Revoked operator still authorized");
        quiet();
        alert("SlotqDeadDelivery", false);
        record("operator-recovery-converged", Map.of("operationId", operation.toString(), "admission", admitted,
            "audit", audit, "delivery", durable, "database", new WaitlistRecoveryDatabase(db).snapshot(f.tenant, Instant.now())));
    }

    private void quiet() {
        var pool = application.getBean(HikariDataSource.class).getHikariPoolMXBean();
        var consecutive = new java.util.concurrent.atomic.AtomicInteger();
        await().pollInterval(Duration.ofMillis(250)).atMost(Duration.ofSeconds(15)).until(() -> {
            boolean idle = pool.getActiveConnections() == 0 && pool.getThreadsAwaitingConnection() == 0
                && runtime.activeWorkers() == 0 && f.engine.activeWorkers() == 0
                && M8CapacityObservation.runtimePermits(runtime) == 0
                && ((Number) M8CapacityObservation.permits(application.getBean(LocalAdmission.class)).get("logical")).intValue() == 0
                && db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE state IN ('PENDING','PROCESSING')", Integer.class) == 0
                && db.queryForObject("SELECT COUNT(*) FROM event_records e JOIN event_registrations r "
                    + "ON r.event_type=e.event_type AND r.schema_version=e.schema_version AND e.boundary_sequence>r.activation_boundary "
                    + "AND (r.deactivation_boundary IS NULL OR e.boundary_sequence<r.deactivation_boundary) "
                    + "LEFT JOIN event_deliveries d ON d.tenant_id=e.tenant_id AND d.event_id=e.event_id AND d.registration_id=r.registration_id "
                    + "WHERE e.tenant_id=? AND d.event_id IS NULL", Integer.class, bytes(f.tenant)) == 0;
            if (!idle) consecutive.set(0);
            else consecutive.incrementAndGet();
            return consecutive.get() >= 3;
        });
    }

    private UUID slot(String name) {
        var resource = f.resources.createResource(new ResourceUseCase.CreateResource(new TenantId(f.tenant), f.venue, name, 4));
        return f.slots.createSlot(new SlotInventoryUseCase.CreateSlot(new TenantId(f.tenant), f.venue, resource.id(), f.day + "T18:00:00Z")).id().value();
    }

    private int count(String table, UUID target) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE slot_inventory_id=?", Integer.class, bytes(target));
    }

    private HttpRequest hold(UUID target, String key, String token) {
        return HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN + "/api/v1/venues/" + f.venue.value() + "/reservations/holds"))
            .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("slotInventoryId", target, "partySize", 2)))).build();
    }

    private HttpResponse<String> call(String method, String path, String token, Object value) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN + path))
            .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token);
        if (value == null) request.GET();
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(value)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response, int status) {
        require(response.statusCode() == status, "Unexpected recovery HTTP status " + response.statusCode());
        return JSON.readTree(response.body());
    }

    private void startPrometheus() throws Exception {
        Path certificate = output.resolve("fixture-ca.pem");
        var export = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-exportcert", "-rfc", "-alias", "test", "-keystore", McpHttpIntegrationTests.KEYSTORE.toString(),
            "-storepass:env", "SLOTQ_RECOVERY_TLS", "-file", certificate.toString());
        export.environment().put("SLOTQ_RECOVERY_TLS", McpHttpIntegrationTests.PASSWORD);
        export.redirectErrorStream(true).redirectOutput(output.resolve("keytool.log").toFile());
        require(export.start().waitFor() == 0, "Certificate export failed");
        Testcontainers.exposeHostPorts(RepresentativeFlowIntegrationTests.PORT);
        String config = "global:\n  scrape_interval: 1s\n  evaluation_interval: 1s\nrule_files: ['/etc/prometheus/alerts.yml']\nscrape_configs:\n"
            + "  - job_name: slotq-product\n    metrics_path: /actuator/prometheus\n    scheme: https\n    authorization:\n      credentials: '" + MONITOR
            + "'\n    tls_config:\n      ca_file: /etc/prometheus/tls.pem\n      server_name: localhost\n    static_configs:\n      - targets: ['host.testcontainers.internal:" + RepresentativeFlowIntegrationTests.PORT + "']\n";
        prometheus = new GenericContainer<>("prom/prometheus:v3.5.0").withExposedPorts(9090)
            .withCopyToContainer(org.testcontainers.images.builder.Transferable.of(config), "/etc/prometheus/prometheus.yml")
            .withCopyFileToContainer(MountableFile.forHostPath(certificate), "/etc/prometheus/tls.pem")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../infra/observability/alerts.yml")), "/etc/prometheus/alerts.yml");
        prometheus.start();
        await().atMost(Duration.ofSeconds(45)).until(() -> query("up{job=\"slotq-product\"}").path("data").path("result").size() == 1
            && query("up{job=\"slotq-product\"}").path("data").path("result").get(0).path("value").get(1).asString().equals("1"));
        await().atMost(Duration.ofSeconds(45)).until(() -> query("min(slotq_observation_sample_healthy)").path("data").path("result").size() == 1
            && query("min(slotq_observation_sample_healthy)").path("data").path("result").get(0).path("value").get(1).asString().equals("1"));
        alert("SlotqDatabaseSampleUnavailable", false);
        record("telemetry-ready", Map.of("checkedCertificate", true, "rulesSha256", digest(Path.of("../infra/observability/alerts.yml"))));
    }

    private JsonNode query(String expression) throws Exception {
        String origin = "http://" + prometheus.getHost() + ":" + prometheus.getMappedPort(9090);
        var request = HttpRequest.newBuilder(URI.create(origin + "/api/v1/query?query="
            + URLEncoder.encode(expression, java.nio.charset.StandardCharsets.UTF_8))).timeout(Duration.ofSeconds(4)).GET().build();
        return JSON.readTree(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body());
    }

    private void alert(String name, boolean firing) throws Exception {
        Instant start = Instant.now();
        await().atMost(Duration.ofSeconds(45)).until(() -> (query("ALERTS{alertname=\"" + name
            + "\",job=\"slotq-product\",alertstate=\"firing\"}").path("data").path("result").size() > 0) == firing);
        if (!firing) {
            require(query("up{job=\"slotq-product\"}").path("data").path("result").get(0).path("value").get(1).asString().equals("1"),
                "Missing telemetry cannot establish alert recovery");
        }
        record("alert-transition", Map.of("alert", name, "firing", firing, "awaitStartedAt", start.toString(),
            "databaseSamples", query("slotq_observation_sample_healthy").path("data")));
    }

    private void record(String phase, Map<String, ?> details) {
        var row = new LinkedHashMap<String, Object>();
        row.put("phase", phase); row.put("at", Instant.now().toString()); row.put("nanos", System.nanoTime()); row.putAll(details);
        timeline.add(row);
    }

    private static UUID uuid(byte[] bytes) {
        var buffer = java.nio.ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
