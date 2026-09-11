package com.slotq.experiments.events;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.slotq.SlotqApplication;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.events.application.ConsumerRoute;
import com.slotq.events.application.DeliveryClaim;
import com.slotq.events.application.DeliveryKey;
import com.slotq.events.application.EventAppendService;
import com.slotq.events.application.EventDeliveryWorker;
import com.slotq.events.application.EventEnvelope;
import com.slotq.events.application.EventHandler;
import com.slotq.events.application.EventHandlingException;
import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRegistrationService;
import com.slotq.events.application.EventReplayService;
import com.slotq.events.application.StoredEvent;
import com.slotq.tenancy.domain.TenantId;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * M3-WP3 process fault harness. Production delivery entrypoints and persistence are used unchanged;
 * process coordination, the synthetic consumer and crash timing exist only on the test classpath.
 */
public final class EventProcessRecoveryRunner {
    private static final String SCHEMA = "slotq-event-process-recovery/v1";
    private static final ConsumerRoute ROUTE =
        new ConsumerRoute("ProcessRecoveryProjection", "ProcessRecoveryChanged", 1);
    private static final int CRASH_EXIT = 86;
    private static final int EFFECT_CRASH_EXIT = 87;
    private static final int COMMIT_UNKNOWN_EXIT = 88;
    private static final int BACKLOG_EXIT = 89;
    private static final int BACKLOG_SIZE = 8;
    private static final int BATCH_SIZE = 3;

    private EventProcessRecoveryRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            child(args);
            return;
        }
        runParent();
    }

    private static void runParent() throws Exception {
        String runId = System.getProperty("slotq.events.recovery.runId", UUID.randomUUID().toString());
        Path output = Path.of(System.getProperty("slotq.events.recovery.output",
            "build/reports/experiments/events/" + runId)).toAbsolutePath();
        String revision = git("rev-parse", "HEAD");
        String branch = git("branch", "--show-current");
        boolean dirty = !git("status", "--porcelain").isBlank();
        List<Map<String, Object>> cases = new ArrayList<>();

        try (var mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("slotq_event_process_recovery")) {
            mysql.start();
            RecoveryDatabase database = new RecoveryDatabase(mysql);
            String only = System.getProperty("slotq.events.recovery.only", "all");

            if (only.equals("all") || only.equals("materialization")) materializationRecovery(mysql, database, cases);
            if (only.equals("all") || only.equals("claim")) claimRecovery(mysql, database, cases);
            if (only.equals("all") || only.equals("effect")) effectTransactionCrash(mysql, database, cases);
            if (only.equals("all") || only.equals("commit")) commitOutcomeUnknown(mysql, database, cases);
            if (only.equals("all") || only.equals("stale")) staleOwner(mysql, database, cases);
            if (only.equals("all") || only.equals("exhaustion")) exhaustionAndReplay(mysql, database, cases);
            if (only.equals("all") || only.equals("database")) databaseUnavailable(mysql, database, cases);
            if (only.equals("all") || only.equals("backlog")) backlogDrain(mysql, database, cases);

            Map<String, Object> environment = environment(mysql, database, runId, revision, branch, dirty);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", SCHEMA);
            report.put("environment", environment);
            report.put("cases", cases);
            report.put("summary", Map.of(
                "faultCases", cases.size(),
                "passed", cases.stream().filter(row -> "PASS".equals(row.get("outcome"))).count(),
                "failed", cases.stream().filter(row -> !"PASS".equals(row.get("outcome"))).count(),
                "wp3ReliabilityGate", "PASS"));

            writeEvidence(output, report);
            System.out.println("Event process recovery evidence: " + output);
        }
    }

    private static void materializationRecovery(MySQLContainer mysql, RecoveryDatabase db,
                                                List<Map<String, Object>> cases) throws Exception {
        child(mysql, "SEED", "materialization", "1").expect(0);
        int exit = child(mysql, "HALT_BEFORE_MATERIALIZE", "materialization").expect(CRASH_EXIT);
        Map<String, Object> first = db.snapshot();
        require(db.count("event_records") == 1 && db.count("event_deliveries") == 0,
            "materialization crash must leave only the committed event");
        child(mysql, "DRAIN", "materialization").expect(0);
        Map<String, Object> recovered = db.snapshot();
        require(db.done() == 1 && db.effects() == 1 && db.receipts() == 1,
            "materialization restart did not recover the target");
        cases.add(caseRow("MATERIALIZATION_BEFORE_PROCESS_EXIT",
            "committed event before materialize()", "Runtime.halt", exit, first, recovered));
    }

    private static void claimRecovery(MySQLContainer mysql, RecoveryDatabase db,
                                      List<Map<String, Object>> cases) throws Exception {
        child(mysql, "SEED", "claim", "1").expect(0);
        int exit = child(mysql, "CLAIM_HALT", "claim").expect(CRASH_EXIT);
        Map<String, Object> first = db.snapshot();
        require(db.processing() == 1 && db.attempts() == 1 && db.effects() == 0,
            "claim crash must durably consume one attempt without an effect");
        Map<String, Object> beforeLease = db.snapshot();
        require(db.processing() == 1 && db.attempts() == 1 && db.effects() == 0,
            "durable claim changed before its lease expiry");
        db.awaitLeaseExpiry();
        child(mysql, "DRAIN", "claim").expect(0);
        Map<String, Object> recovered = db.snapshot();
        require(db.done() == 1 && db.attempts() == 2 && db.effects() == 1 && db.receipts() == 1,
            "expired claim did not converge through a new durable attempt");
        cases.add(caseRow("CLAIM_COMMIT_THEN_PROCESS_EXIT",
            "claim() commit before handler", "Runtime.halt", exit, first, recovered,
            Map.of("beforeLeaseExpiry", beforeLease)));
    }

    private static void effectTransactionCrash(MySQLContainer mysql, RecoveryDatabase db,
                                               List<Map<String, Object>> cases) throws Exception {
        child(mysql, "SEED", "effect-crash", "1").expect(0);
        int exit = child(mysql, "EFFECT_CRASH", "effect-crash").expect(EFFECT_CRASH_EXIT);
        Map<String, Object> first = db.snapshot();
        require(db.processing() == 1 && db.attempts() == 1 && db.effects() == 0 && db.receipts() == 0,
            "effect transaction crash left a partial durable effect or receipt");
        db.awaitLeaseExpiry();
        child(mysql, "DRAIN", "effect-crash").expect(0);
        Map<String, Object> recovered = db.snapshot();
        require(db.done() == 1 && db.attempts() == 2 && db.effects() == 1 && db.receipts() == 1,
            "rolled-back effect transaction did not recover exactly once");
        cases.add(caseRow("EFFECT_TRANSACTION_PROCESS_EXIT",
            "after synthetic effect/receipt writes, before effect transaction commit",
            "Runtime.halt", exit, first, recovered));
    }

    private static void commitOutcomeUnknown(MySQLContainer mysql, RecoveryDatabase db,
                                             List<Map<String, Object>> cases) throws Exception {
        child(mysql, "SEED", "commit-unknown", "1").expect(0);
        int exit = child(mysql, "COMMIT_UNKNOWN", "commit-unknown").expect(COMMIT_UNKNOWN_EXIT);
        Map<String, Object> first = db.snapshot();
        require(db.done() == 1 && db.attempts() == 1 && db.effects() == 1 && db.receipts() == 1,
            "commit-unknown committed branch was not atomic");
        child(mysql, "DRAIN", "commit-unknown").expect(0);
        Map<String, Object> recovered = db.snapshot();
        require(db.done() == 1 && db.attempts() == 1 && db.effects() == 1 && db.receipts() == 1,
            "durable DONE was redelivered after child could not observe commit completion");
        cases.add(caseRow("EFFECT_AND_DONE_COMMIT_OUTCOME_UNKNOWN",
            "after physical effect + DONE commit, before transaction completion returns",
            "afterCommit Runtime.halt", exit, first, recovered));
    }

    private static void staleOwner(MySQLContainer mysql, RecoveryDatabase db,
                                   List<Map<String, Object>> cases) throws Exception {
        child(mysql, "SEED", "stale-owner", "1").expect(0);
        Path ready = Files.createTempFile("slotq-stale-owner-ready-", ".gate");
        Path release = Files.createTempFile("slotq-stale-owner-release-", ".gate");
        Files.deleteIfExists(ready);
        Files.deleteIfExists(release);
        RunningChild ownerA = startChild(mysql, "CLAIM_WAIT_PROCESS", "stale-owner",
            ready.toString(), release.toString());
        try {
            awaitFile(ready, Duration.ofSeconds(30));
            Map<String, Object> claimed = db.snapshot();
            require(db.processing() == 1 && db.token() == 1 && db.effects() == 0,
                "JVM A did not hold the first durable claim");
            db.awaitLeaseExpiry();
            child(mysql, "PROCESS_ONE", "stale-owner").expect(0);
            Map<String, Object> ownerB = db.snapshot();
            require(db.done() == 1 && db.token() == 2 && db.effects() == 1 && db.receipts() == 1,
                "JVM B did not reclaim and finish with a newer token");
            Files.createFile(release);
            int exit = ownerA.await(Duration.ofSeconds(30));
            require(exit == 0, "stale JVM A did not finish its rejected process attempt");
            Map<String, Object> staleRetry = db.snapshot();
            require(db.done() == 1 && db.token() == 2 && db.effects() == 1 && db.receipts() == 1,
                "stale owner changed effect or delivery state");
            cases.add(caseRow("STALE_OWNER_AFTER_NEW_OWNER_DONE",
                "JVM A claim -> lease expiry -> JVM B reclaim/DONE -> JVM A process",
                "coordinated two-JVM interleaving", exit, ownerB, staleRetry,
                Map.of("ownerAClaimed", claimed)));
        } finally {
            ownerA.close();
            Files.deleteIfExists(ready);
            Files.deleteIfExists(release);
        }
    }

    private static void exhaustionAndReplay(MySQLContainer mysql, RecoveryDatabase db,
                                            List<Map<String, Object>> cases) throws Exception {
        child(mysql, "SEED", "exhaustion", "1").expect(0);
        List<Map<String, Object>> attempts = new ArrayList<>();
        int lastExit = 0;
        for (int expected = 1; expected <= 3; expected++) {
            lastExit = child(mysql, "CLAIM_HALT", "exhaustion").expect(CRASH_EXIT);
            attempts.add(db.snapshot());
            require(db.processing() == 1 && db.attempts() == expected && db.effects() == 0,
                "each claim crash must consume exactly one attempt");
            db.awaitLeaseExpiry();
        }
        child(mysql, "CLAIM_ONLY", "exhaustion").expect(0);
        Map<String, Object> dead = db.snapshot();
        require(db.dead() == 1 && db.attempts() == 3 && db.token() == 3 && db.effects() == 0
                && "CRASH_EXHAUSTED".equals(db.failureCode()),
            "claim crash budget did not converge to CRASH_EXHAUSTED: " + dead);
        child(mysql, "DRAIN", "exhaustion").expect(0);
        require(db.dead() == 1 && db.attempts() == 3,
            "restart alone attempted a CRASH_EXHAUSTED delivery");
        cases.add(caseRow("CLAIM_CRASH_EXHAUSTION",
            "claim() commit followed by repeated Runtime.halt", "Runtime.halt x3", lastExit,
            dead, db.snapshot(), Map.of("attemptSnapshots", attempts)));

        child(mysql, "REPLAY_DRAIN", "exhaustion").expect(0);
        Map<String, Object> replayed = db.snapshot();
        require(db.done() == 1 && db.attempts() == 1 && db.lifetimeAttempts() == 4
                && db.token() == 5 && db.effects() == 1 && db.receipts() == 1 && db.audits() == 1,
            "trusted replay did not preserve lifetime identity/audit and open one new cycle");
        cases.add(caseRow("TRUSTED_INTERNAL_REPLAY_AFTER_CRASH_EXHAUSTION",
            "CRASH_EXHAUSTED replay and new retry cycle", "new child JVM", 0, dead, replayed));
    }

    private static void databaseUnavailable(MySQLContainer mysql, RecoveryDatabase db,
                                            List<Map<String, Object>> cases) throws Exception {
        child(mysql, "SEED", "db-unavailable", "1").expect(0);
        child(mysql, "CLAIM_HALT", "db-unavailable").expect(CRASH_EXIT);
        Map<String, Object> beforeOutage = db.snapshot();
        RunningChild unavailable = null;
        boolean paused = false;
        boolean forced;
        int exit;
        try {
            DockerClientFactory.instance().client().pauseContainerCmd(mysql.getContainerId()).exec();
            paused = true;
            unavailable = startChild(mysql, "DRAIN", "db-unavailable");
            Thread.sleep(1500);
            forced = unavailable.isAlive();
            exit = unavailable.terminate();
        } finally {
            if (paused) {
                DockerClientFactory.instance().client().unpauseContainerCmd(mysql.getContainerId()).exec();
            }
            if (unavailable != null) unavailable.close();
        }
        Map<String, Object> afterOutage = db.snapshot();
        require(db.processing() == 1 && db.attempts() == 1 && db.effects() == 0 && db.receipts() == 0,
            "DB outage created memory-only or partial durable delivery state");
        db.awaitLeaseExpiry();
        child(mysql, "DRAIN", "db-unavailable").expect(0);
        Map<String, Object> recovered = db.snapshot();
        require(db.done() == 1 && db.attempts() == 2 && db.effects() == 1 && db.receipts() == 1,
            "same MySQL state did not recover after temporary unavailability");
        cases.add(caseRow("DATABASE_UNAVAILABLE_AND_RECOVERY",
            "committed PROCESSING claim while MySQL container is paused",
            forced ? "parent destroyForcibly during DB outage" : "child exit during DB connection failure",
            exit, afterOutage, recovered,
            Map.of("beforeOutage", beforeOutage)));
    }

    private static void backlogDrain(MySQLContainer mysql, RecoveryDatabase db,
                                     List<Map<String, Object>> cases) throws Exception {
        child(mysql, "SEED", "backlog", Integer.toString(BACKLOG_SIZE)).expect(0);
        int exit = child(mysql, "RUN_ONE_CYCLE_HALT", "backlog").expect(BACKLOG_EXIT);
        Map<String, Object> first = db.snapshot();
        require(db.count("event_records") == BACKLOG_SIZE && db.done() == BATCH_SIZE
                && db.effects() == BATCH_SIZE,
            "first bounded cycle did not stop at the configured batch boundary");
        child(mysql, "DRAIN", "backlog").expect(0);
        Map<String, Object> recovered = db.snapshot();
        require(db.done() == BACKLOG_SIZE && db.effects() == BACKLOG_SIZE
                && db.receipts() == BACKLOG_SIZE && db.dueOrExpired() == 0
                && db.count("event_deliveries") == BACKLOG_SIZE
                && db.discoveryCursor() == db.eventBoundary(),
            "restart did not drain and account for the complete backlog");
        cases.add(caseRow("BACKLOG_LARGER_THAN_BATCH_RESTART_DRAIN",
            "one production runCycle() completes a partial batch before process exit",
            "Runtime.halt after runCycle", exit, first, recovered));
    }

    private static void child(String[] args) throws Exception {
        String command = args[0];
        String scenario = args[1];
        try (ConfigurableApplicationContext context = childContext()) {
            JdbcTemplate db = context.getBean(JdbcTemplate.class);
            EventDeliveryWorker worker = context.getBean(EventDeliveryWorker.class);
            switch (command) {
                case "SEED" -> seed(context, scenario, Integer.parseInt(args[2]));
                case "HALT_BEFORE_MATERIALIZE" -> Runtime.getRuntime().halt(CRASH_EXIT);
                case "CLAIM_HALT" -> {
                    worker.materialize();
                    Optional<DeliveryClaim> claim = worker.claim(firstKey(db));
                    if (claim.isPresent()) Runtime.getRuntime().halt(CRASH_EXIT);
                }
                case "CLAIM_ONLY" -> {
                    worker.materialize();
                    worker.claim(firstKey(db));
                }
                case "PROCESS_ONE" -> {
                    worker.materialize();
                    worker.claim(firstKey(db)).ifPresent(worker::process);
                }
                case "EFFECT_CRASH" -> worker.runCycle();
                case "COMMIT_UNKNOWN" -> worker.runCycle();
                case "CLAIM_WAIT_PROCESS" -> {
                    worker.materialize();
                    DeliveryClaim claim = worker.claim(firstKey(db)).orElseThrow();
                    Path ready = Path.of(args[2]);
                    Path release = Path.of(args[3]);
                    Files.createFile(ready);
                    awaitFile(release, Duration.ofSeconds(60));
                    worker.process(claim);
                }
                case "REPLAY_DRAIN" -> {
                    context.getBean(EventReplayService.class).replay(SystemPrincipal.INSTANCE, firstKey(db),
                        "process recovery cause resolved");
                    drain(worker);
                }
                case "RUN_ONE_CYCLE_HALT" -> {
                    worker.runCycle();
                    Runtime.getRuntime().halt(BACKLOG_EXIT);
                }
                case "DRAIN" -> drain(worker);
                default -> throw new IllegalArgumentException("Unknown command: " + command);
            }
        }
    }

    private static ConfigurableApplicationContext childContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.datasource.url", requiredEnvironment("EVENT_RECOVERY_DB_URL"));
        properties.put("spring.datasource.username", requiredEnvironment("EVENT_RECOVERY_DB_USER"));
        properties.put("spring.datasource.password", requiredEnvironment("EVENT_RECOVERY_DB_PASSWORD"));
        properties.put("spring.datasource.hikari.connection-timeout", "1000");
        properties.put("spring.datasource.hikari.validation-timeout", "1000");
        properties.put("spring.main.banner-mode", "off");
        properties.put("server.port", "0");
        properties.put("logging.level.root", "ERROR");
        SpringApplication application = new SpringApplicationBuilder(SlotqApplication.class,
            ChildConfiguration.class).properties(properties).build();
        // Command-line properties intentionally override production defaults only inside the child harness.
        return application.run(
            "--slotq.events.delivery.scheduler-enabled=false",
            "--slotq.events.delivery.max-attempts=3",
            "--slotq.events.delivery.lease=PT4S",
            "--slotq.events.delivery.effect-timeout=PT2S",
            "--slotq.events.delivery.lock-wait=PT1S",
            "--slotq.events.delivery.batch-size=" + BATCH_SIZE,
            "--slotq.events.delivery.retry-delays=PT0S,PT0S");
    }

    private static void seed(ConfigurableApplicationContext context, String scenario, int count) {
        JdbcTemplate db = context.getBean(JdbcTemplate.class);
        db.execute("""
            CREATE TABLE IF NOT EXISTS event_process_recovery_owner (
                owner_id BINARY(16) PRIMARY KEY, tenant_id BINARY(16) NOT NULL, current_revision INT NOT NULL,
                UNIQUE KEY (tenant_id, owner_id)
            ) ENGINE=InnoDB
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS event_process_recovery_effect (
                consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                event_id BINARY(16) NOT NULL, tenant_id BINARY(16) NOT NULL,
                owner_id BINARY(16) NOT NULL, current_revision INT NOT NULL, applications INT NOT NULL,
                PRIMARY KEY (consumer_id, event_id)
            ) ENGINE=InnoDB
            """);
        db.execute("""
            CREATE TABLE IF NOT EXISTS event_process_recovery_receipt (
                consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                event_id BINARY(16) NOT NULL, tenant_id BINARY(16) NOT NULL,
                aggregate_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                aggregate_id BINARY(16) NOT NULL, event_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                schema_version INT NOT NULL, occurred_at DATETIME(6) NOT NULL, payload MEDIUMTEXT NOT NULL,
                PRIMARY KEY (consumer_id, event_id)
            ) ENGINE=InnoDB
            """);
        db.update("DELETE FROM event_process_recovery_receipt");
        db.update("DELETE FROM event_process_recovery_effect");
        db.update("DELETE FROM event_process_recovery_owner");
        db.update("DELETE FROM event_replay_audit");
        db.update("DELETE FROM event_deliveries");
        db.update("DELETE FROM event_records");
        db.update("DELETE FROM event_registrations");
        db.update("UPDATE event_discovery SET boundary_sequence = 0 WHERE singleton_id = 1");
        db.update("UPDATE event_boundary SET sequence_value = 0 WHERE singleton_id = 1");

        UUID tenant = named(scenario + ":tenant");
        UUID owner = named(scenario + ":owner");
        db.update("INSERT IGNORE INTO tenants (id, status) VALUES (?, 'ACTIVE')", bytes(tenant));
        db.update("INSERT INTO event_process_recovery_owner VALUES (?, ?, 7)", bytes(owner), bytes(tenant));
        context.getBean(EventRegistrationService.class).activate(ROUTE);
        TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        EventAppendService append = context.getBean(EventAppendService.class);
        for (int index = 0; index < count; index++) {
            int eventIndex = index;
            transaction.executeWithoutResult(status -> append.append(new EventEnvelope(
                new EventId(named(scenario + ":event:" + eventIndex)), new TenantId(tenant),
                "ProcessRecoveryOwner", owner, ROUTE.eventType(), ROUTE.schemaVersion(),
                Instant.parse("2026-09-11T00:00:00Z").plusNanos(eventIndex * 1_000L), "{\"signal\":true}")));
        }
    }

    private static void drain(EventDeliveryWorker worker) {
        for (int cycle = 0; cycle < 100; cycle++) {
            if (worker.runCycle() == 0) return;
        }
        throw new IllegalStateException("Recovery drain exceeded 100 bounded cycles");
    }

    private static DeliveryKey firstKey(JdbcTemplate db) {
        return db.query("""
            SELECT tenant_id, event_id, registration_id FROM event_deliveries
             ORDER BY event_id, registration_id LIMIT 1
            """, (row, n) -> new DeliveryKey(new TenantId(uuid(row.getBytes("tenant_id"))),
            new EventId(uuid(row.getBytes("event_id"))), uuid(row.getBytes("registration_id"))))
            .stream().findFirst().orElseThrow();
    }

    private static ChildResult child(MySQLContainer mysql, String... arguments) throws Exception {
        try (RunningChild child = startChild(mysql, arguments)) {
            return new ChildResult(child.await(Duration.ofSeconds(45)), child.log());
        }
    }

    private static RunningChild startChild(MySQLContainer mysql, String... arguments) throws Exception {
        Path argumentFile = Files.createTempFile("slotq-event-recovery-jvm-", ".args");
        Path log = Files.createTempFile("slotq-event-recovery-jvm-", ".log");
        StringBuilder content = new StringBuilder();
        content.append("-cp\n\"").append(System.getProperty("java.class.path").replace("\\", "/"))
            .append("\"\n").append(EventProcessRecoveryRunner.class.getName()).append('\n');
        for (String argument : arguments) {
            content.append('"').append(argument.replace("\\", "/").replace("\"", "\\\""))
                .append("\"\n");
        }
        Files.writeString(argumentFile, content);
        ProcessBuilder builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + argumentFile)
            .redirectErrorStream(true).redirectOutput(log.toFile());
        String jdbcUrl = mysql.getJdbcUrl();
        builder.environment().put("EVENT_RECOVERY_DB_URL", jdbcUrl
            + (jdbcUrl.contains("?") ? "&" : "?") + "connectTimeout=1000&socketTimeout=1000");
        builder.environment().put("EVENT_RECOVERY_DB_USER", mysql.getUsername());
        builder.environment().put("EVENT_RECOVERY_DB_PASSWORD", mysql.getPassword());
        builder.environment().put("EVENT_RECOVERY_FAULT", arguments[0]);
        return new RunningChild(builder.start(), argumentFile, log);
    }

    private static Map<String, Object> environment(MySQLContainer mysql, RecoveryDatabase db, String runId,
                                                   String revision, String branch, boolean dirty) throws Exception {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("runId", runId);
        environment.put("applicationRevision", revision);
        environment.put("branch", branch);
        environment.put("dirty", dirty);
        environment.put("recordedAt", Instant.now().toString());
        environment.put("javaVersion", System.getProperty("java.runtime.version"));
        environment.put("springBootVersion", SpringBootVersion.getVersion());
        environment.put("gradleVersion", System.getProperty("slotq.events.gradleVersion"));
        environment.put("mysqlImage", "mysql:8.4");
        environment.put("mysqlVersion", db.scalar("SELECT VERSION()"));
        environment.put("transactionIsolation", db.scalar("SELECT @@transaction_isolation"));
        environment.put("dockerVersion", DockerClientFactory.instance().client().versionCmd().exec().getVersion());
        environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        environment.put("processors", Runtime.getRuntime().availableProcessors());
        environment.put("maxJvmMemoryBytes", Runtime.getRuntime().maxMemory());
        environment.put("childTermination", "Runtime.halt or parent destroyForcibly; shutdown hooks bypassed");
        environment.put("databaseContinuity", "one MySQL container and database retained across all child JVMs");
        environment.put("testDeliveryPolicy", Map.of(
            "maxAttempts", 3, "lease", "PT4S", "effectTimeout", "PT2S", "lockWait", "PT1S",
            "batchSize", BATCH_SIZE, "retryDelays", List.of("PT0S", "PT0S"),
            "productionSlo", false));
        return environment;
    }

    private static Map<String, Object> caseRow(String name, String faultPoint, String termination, int exitCode,
                                               Map<String, Object> first, Map<String, Object> recovered) {
        return caseRow(name, faultPoint, termination, exitCode, first, recovered, Map.of());
    }

    private static Map<String, Object> caseRow(String name, String faultPoint, String termination, int exitCode,
                                               Map<String, Object> first, Map<String, Object> recovered,
                                               Map<String, Object> additional) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("faultPoint", faultPoint);
        row.put("termination", termination);
        row.put("childExitCode", exitCode);
        row.put("firstAuthoritativeState", first);
        row.putAll(additional);
        row.put("recoveryAuthoritativeState", recovered);
        row.put("outcome", "PASS");
        return row;
    }

    private static void writeEvidence(Path output, Map<String, Object> report) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Files.createDirectories(output.resolve("raw"));
        Files.writeString(output.resolve("raw/report.json"),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        Map<?, ?> environment = (Map<?, ?>) report.get("environment");
        List<?> cases = (List<?>) report.get("cases");
        String runId = environment.get("runId").toString();
        Files.writeString(output.resolve("environment.md"), """
            # Event delivery process recovery environment

            - Run ID: `%s`
            - Revision: `%s`
            - Branch: `%s`
            - Dirty at run start: `%s`
            - Java: `%s`
            - Spring Boot: `%s`
            - Gradle: `%s`
            - MySQL image / version: `mysql:8.4` / `%s`
            - Isolation: `%s`
            - Docker: `%s`
            - Test policy: max attempts 3, lease 4s, effect timeout 2s, lock wait 1s, batch 3, retry delay 0s

            테스트 policy는 bounded fault experiment 값이며 production SLO가 아니다. 모든 child JVM은 같은
            MySQL container/database를 사용했고 DB credential은 evidence에 기록하지 않았다.
            """.formatted(runId, environment.get("applicationRevision"), environment.get("branch"),
            environment.get("dirty"), environment.get("javaVersion"), environment.get("springBootVersion"),
            environment.get("gradleVersion"), environment.get("mysqlVersion"),
            environment.get("transactionIsolation"), environment.get("dockerVersion")));
        StringBuilder matrix = new StringBuilder("""
            # Event delivery process fault matrix

            | Case | Fault point | Termination | Result |
            | --- | --- | --- | --- |
            """);
        for (Object value : cases) {
            Map<?, ?> row = (Map<?, ?>) value;
            matrix.append("| ").append(row.get("name")).append(" | ")
                .append(row.get("faultPoint")).append(" | ").append(row.get("termination"))
                .append(" | ").append(row.get("outcome")).append(" |\n");
        }
        Files.writeString(output.resolve("fault-matrix.md"), matrix);
        Files.writeString(output.resolve("summary.md"), """
            # Event delivery process recovery summary

            상태: **Measured**

            ## 결론

            WP3 reliability gate는 **PASS**다. %d개 process fault/recovery case가 모두 production
            `materialize()`, `claim()`, `process()`, `runCycle()` 및 internal replay 경로를 변경하지 않고
            통과했다. 판정은 child 출력이 아니라 fault 직후 DB가 조회 가능한 최초 시점과 recovery 후
            MySQL snapshot을 기준으로 했다.

            effect와 receipt, delivery `DONE`은 하나의 transaction에서 전체 commit 또는 전체 rollback만
            관측됐다. 별도 ACK transaction은 만들지 않았다. stale owner는 정의된 두 JVM 순서에서 새 owner의
            effect/DONE 뒤 어떤 durable 상태도 바꾸지 못했다. 세 claim crash는 `CRASH_EXHAUSTED`로 끝났고,
            trusted replay는 lifetime attempt와 identity, append-only audit을 유지한 새 cycle에서 수렴했다.

            MySQL pause 중 실행된 child는 memory-only 상태를 남기지 못했고 같은 container를 unpause한 뒤 기존
            durable claim에서 회복했다. batch 3보다 큰 8개 backlog도 process restart 뒤 전부 `DONE`으로
            drain됐으며 effect/receipt는 logical target마다 하나였다.

            ## 적용 범위와 한계

            이 결과는 ADR-0007과 `docs/architecture/event-delivery.md`의 M3-WP3 process recovery gate에
            한정된다. scheduler activation, 실제 Booking/Waitlist producer/consumer, 외부 provider,
            Kafka/Redis, 범용 Inbox 및 M3 전체 종료 판정은 포함하지 않는다.

            ## 재현

            Backend에서 Java 25와 Docker를 사용한다.

            ```powershell
            .\\gradlew.bat eventProcessRecovery -PrunId=%s "-Poutput=../docs/experiments/events/%s"
            ```

            원자료: [`raw/report.json`](raw/report.json)
            """.formatted(cases.size(), runId, runId));
    }

    private static void awaitFile(Path path, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(path)) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Process gate timed out: " + path);
            Thread.sleep(50);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing child environment: " + name);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static UUID named(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
            .array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "safe.directory=C:/dev/slotq"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("Cannot capture revision: " + result);
        return result.strip();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ChildConfiguration {
        @Bean
        EventHandler processRecoveryHandler(JdbcTemplate db, Environment environment) {
            return new ProcessRecoveryHandler(db, environment.getProperty("EVENT_RECOVERY_FAULT",
                System.getenv().getOrDefault("EVENT_RECOVERY_FAULT", "NONE")));
        }
    }

    static final class ProcessRecoveryHandler implements EventHandler {
        private final JdbcTemplate db;
        private final String fault;

        ProcessRecoveryHandler(JdbcTemplate db, String fault) {
            this.db = db;
            this.fault = fault;
        }

        @Override
        public ConsumerRoute route() {
            return ROUTE;
        }

        @Override
        public void handle(StoredEvent stored) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new IllegalStateException("Synthetic effect must join a writable Product transaction");
            }
            EventEnvelope event = stored.envelope();
            Map<String, Object> owner = db.queryForMap("""
                SELECT HEX(tenant_id) AS tenant_id, current_revision
                  FROM event_process_recovery_owner WHERE owner_id = ? FOR UPDATE
                """, bytes(event.aggregateId()));
            if (!owner.get("tenant_id").toString().equalsIgnoreCase(hex(event.tenantId().value()))) {
                throw new EventHandlingException(com.slotq.events.application.DeliveryFailure.TENANT_MISMATCH);
            }
            List<Map<String, Object>> receipt = db.queryForList("""
                SELECT HEX(tenant_id) AS tenant_id, HEX(aggregate_id) AS aggregate_id, aggregate_type,
                       event_type, schema_version, payload
                  FROM event_process_recovery_receipt WHERE consumer_id = ? AND event_id = ?
                """, ROUTE.consumerId(), bytes(event.eventId().value()));
            if (!receipt.isEmpty()) {
                Map<String, Object> previous = receipt.getFirst();
                boolean same = previous.get("tenant_id").toString().equalsIgnoreCase(hex(event.tenantId().value()))
                    && previous.get("aggregate_id").toString().equalsIgnoreCase(hex(event.aggregateId()))
                    && previous.get("aggregate_type").equals(event.aggregateType())
                    && previous.get("event_type").equals(event.eventType())
                    && ((Number) previous.get("schema_version")).intValue() == event.schemaVersion()
                    && previous.get("payload").equals(event.payload());
                if (!same) {
                    throw new EventHandlingException(com.slotq.events.application.DeliveryFailure.IDENTITY_CORRUPTION);
                }
                return;
            }
            if (!"{\"signal\":true}".equals(event.payload())) {
                throw new EventHandlingException(com.slotq.events.application.DeliveryFailure.PAYLOAD_INVALID);
            }
            db.update("""
                INSERT INTO event_process_recovery_effect
                    (consumer_id, event_id, tenant_id, owner_id, current_revision, applications)
                VALUES (?, ?, ?, ?, ?, 1)
                """, ROUTE.consumerId(), bytes(event.eventId().value()), bytes(event.tenantId().value()),
                bytes(event.aggregateId()), ((Number) owner.get("current_revision")).intValue());
            db.update("""
                INSERT INTO event_process_recovery_receipt
                    (consumer_id, event_id, tenant_id, aggregate_type, aggregate_id, event_type,
                     schema_version, occurred_at, payload)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ROUTE.consumerId(), bytes(event.eventId().value()), bytes(event.tenantId().value()),
                event.aggregateType(), bytes(event.aggregateId()), event.eventType(), event.schemaVersion(),
                java.time.LocalDateTime.ofInstant(event.occurredAt(), java.time.ZoneOffset.UTC), event.payload());
            if ("EFFECT_CRASH".equals(fault)) {
                Runtime.getRuntime().halt(EFFECT_CRASH_EXIT);
            }
            if ("COMMIT_UNKNOWN".equals(fault)) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        Runtime.getRuntime().halt(COMMIT_UNKNOWN_EXIT);
                    }
                });
            }
        }
    }

    private static String hex(UUID value) {
        return java.util.HexFormat.of().withUpperCase().formatHex(bytes(value));
    }

    private record ChildResult(int exitCode, String log) {
        int expect(int expected) {
            if (exitCode != expected) {
                throw new IllegalStateException("Expected child exit " + expected + " but got " + exitCode
                    + System.lineSeparator() + log);
            }
            return exitCode;
        }
    }

    private static final class RunningChild implements AutoCloseable {
        private final Process process;
        private final Path arguments;
        private final Path log;

        private RunningChild(Process process, Path arguments, Path log) {
            this.process = process;
            this.arguments = arguments;
            this.log = log;
        }

        int await(Duration timeout) throws Exception {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor();
                throw new IllegalStateException("Child JVM timed out: " + log());
            }
            return process.exitValue();
        }

        int terminate() throws Exception {
            if (process.isAlive()) process.destroyForcibly().waitFor();
            return process.exitValue();
        }

        boolean isAlive() {
            return process.isAlive();
        }

        String log() throws Exception {
            return Files.readString(log);
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) process.destroyForcibly().waitFor();
            Files.deleteIfExists(arguments);
            Files.deleteIfExists(log);
        }
    }

    private static final class RecoveryDatabase {
        private final JdbcTemplate db;

        private RecoveryDatabase(MySQLContainer mysql) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            this.db = new JdbcTemplate(dataSource);
        }

        Map<String, Object> snapshot() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("databaseNow", scalar("SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')"));
            snapshot.put("eventCount", count("event_records"));
            snapshot.put("registrationCount", count("event_registrations"));
            snapshot.put("discoveryCursor", discoveryCursor());
            snapshot.put("eventBoundary", eventBoundary());
            snapshot.put("deliveries", db.queryForList("""
                SELECT HEX(tenant_id) AS tenantId, HEX(event_id) AS eventId,
                       HEX(registration_id) AS registrationId, state, cycle_attempts AS cycleAttempts,
                       lifetime_attempts AS lifetimeAttempts, fencing_token AS fencingToken,
                       DATE_FORMAT(lease_until, '%Y-%m-%dT%H:%i:%s.%fZ') AS leaseUntil,
                       DATE_FORMAT(next_attempt_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS nextAttemptAt,
                       failure_code AS failureCode, failure_detail AS failureDetail
                  FROM event_deliveries ORDER BY event_id, registration_id
                """));
            snapshot.put("effects", db.queryForList("""
                SELECT consumer_id AS consumerId, HEX(event_id) AS eventId, HEX(tenant_id) AS tenantId,
                       HEX(owner_id) AS ownerId, current_revision AS currentRevision, applications
                  FROM event_process_recovery_effect ORDER BY event_id
                """));
            snapshot.put("receipts", db.queryForList("""
                SELECT consumer_id AS consumerId, HEX(event_id) AS eventId, HEX(tenant_id) AS tenantId,
                       HEX(aggregate_id) AS aggregateId, event_type AS eventType, schema_version AS schemaVersion
                  FROM event_process_recovery_receipt ORDER BY event_id
                """));
            snapshot.put("replayAudit", db.queryForList("""
                SELECT HEX(replay_id) AS replayId, HEX(event_id) AS eventId,
                       HEX(registration_id) AS registrationId, consumer_id AS consumerId, reason,
                       recovery_origin AS recoveryOrigin, prior_state AS priorState,
                       prior_cycle_attempts AS priorCycleAttempts, lifetime_attempts AS lifetimeAttempts,
                       prior_fencing_token AS priorFencingToken, prior_failure_code AS priorFailureCode
                  FROM event_replay_audit ORDER BY recorded_at, replay_id
                """));
            return snapshot;
        }

        int count(String table) {
            if (!List.of("event_records", "event_registrations", "event_deliveries").contains(table)) {
                throw new IllegalArgumentException("Unsupported evidence table");
            }
            return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        }

        int done() { return state("DONE"); }
        int processing() { return state("PROCESSING"); }
        int dead() { return state("DEAD"); }
        int effects() { return db.queryForObject("SELECT COUNT(*) FROM event_process_recovery_effect", Integer.class); }
        int receipts() { return db.queryForObject("SELECT COUNT(*) FROM event_process_recovery_receipt", Integer.class); }
        int audits() { return db.queryForObject("SELECT COUNT(*) FROM event_replay_audit", Integer.class); }
        int attempts() { return db.queryForObject("SELECT COALESCE(MAX(cycle_attempts), 0) FROM event_deliveries", Integer.class); }
        long lifetimeAttempts() { return db.queryForObject("SELECT COALESCE(MAX(lifetime_attempts), 0) FROM event_deliveries", Long.class); }
        long token() { return db.queryForObject("SELECT COALESCE(MAX(fencing_token), 0) FROM event_deliveries", Long.class); }
        String failureCode() { return db.queryForObject("SELECT MAX(failure_code) FROM event_deliveries", String.class); }
        long discoveryCursor() { return db.queryForObject("SELECT boundary_sequence FROM event_discovery", Long.class); }
        long eventBoundary() { return db.queryForObject("SELECT sequence_value FROM event_boundary", Long.class); }
        String scalar(String sql) { return db.queryForObject(sql, String.class); }

        int dueOrExpired() {
            return db.queryForObject("""
                SELECT COUNT(*) FROM event_deliveries
                 WHERE (state = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6))
                    OR (state = 'PROCESSING' AND lease_until <= UTC_TIMESTAMP(6))
                """, Integer.class);
        }

        void awaitLeaseExpiry() throws InterruptedException {
            Long micros = db.queryForObject("""
                SELECT GREATEST(TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), MAX(lease_until)), 0)
                  FROM event_deliveries WHERE state = 'PROCESSING'
                """, Long.class);
            if (micros != null && micros > 0) Thread.sleep(micros / 1000 + 150);
        }

        private int state(String state) {
            return db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE state = ?", Integer.class, state);
        }
    }
}
