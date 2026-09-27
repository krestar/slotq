package com.slotq;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.events.application.*;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.observability.ProductTelemetry;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Disposable real Product/Prometheus/Grafana/Tempo evidence. No synthetic Offer, receipt or DONE rows. */
public final class ObservabilityEvidenceRunner {
    private static final JsonMapper JSON = new JsonMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Path output;
    private final String prometheus;
    private final String promAuth;
    private final Map<String, Object> evidence = new LinkedHashMap<>();

    private ObservabilityEvidenceRunner(Path output, String prometheus, String promAuth) {
        this.output = output; this.prometheus = prometheus; this.promAuth = promAuth;
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path config = root.resolve("infra/observability");
        Path output = Path.of(System.getProperty("slotq.observability.output",
            "build/reports/observability/" + UUID.randomUUID())).toAbsolutePath();
        require(!Files.exists(output), "Evidence output must be a fresh directory");
        Files.createDirectories(output);
        String scrapeToken = secret(), collectorPassword = secret(), promPassword = secret(), grafanaPassword = secret();
        var bcrypt = new BCryptPasswordEncoder(4);
        String promWeb = "basic_auth_users:\n  telemetry: '" + bcrypt.encode(promPassword) + "'\n";
        String traceAuth = "telemetry:" + bcrypt.encode(collectorPassword) + "\n";
        try (Network network = Network.newNetwork();
             var mysql = new MySQLContainer("mysql:8.4").withDatabaseName("slotq_observation").withPassword(secret());
             var tempo = new GenericContainer<>("grafana/tempo:2.8.2").withNetwork(network).withNetworkAliases("tempo")
                 .withCopyFileToContainer(MountableFile.forHostPath(config.resolve("tempo.yml")), "/etc/tempo.yml")
                 .withCommand("-config.file=/etc/tempo.yml").withExposedPorts(3200)
                 .waitingFor(Wait.forHttp("/ready").forPort(3200).withStartupTimeout(Duration.ofMinutes(2)));
             var ingress = new GenericContainer<>("nginx:1.28-alpine").withNetwork(network)
                 .withCopyFileToContainer(MountableFile.forHostPath(config.resolve("nginx.conf")), "/etc/nginx/nginx.conf")
                 .withCopyToContainer(Transferable.of(traceAuth), "/run/secrets/trace.htpasswd").withExposedPorts(4318)) {
            mysql.start(); tempo.start(); ingress.start();
            String observerPassword = secret();
            try (var admin = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword()); var sql = admin.createStatement()) {
                sql.execute("CREATE USER 'observer'@'%' IDENTIFIED BY '" + observerPassword + "'");
                sql.execute("GRANT SELECT ON slotq_observation.* TO 'observer'@'%'");
                sql.execute("GRANT SELECT ON performance_schema.* TO 'observer'@'%'");
                sql.execute("GRANT PROCESS ON *.* TO 'observer'@'%'");
            }
            String ingest = url(ingress, 4318);
            try (var app = new SpringApplicationBuilder(SlotqApplication.class, Configuration.class).run(
                    "--server.port=0", "--logging.level.root=ERROR", "--spring.main.banner-mode=off",
                    "--spring.datasource.url=" + mysql.getJdbcUrl(), "--spring.datasource.username=" + mysql.getUsername(),
                    "--spring.datasource.password=" + mysql.getPassword(), "--slotq.observability.scrape-token=" + scrapeToken,
                    "--slotq.waitlist.promotion.enabled=true", "--slotq.waitlist.promotion.maintenance-enabled=true",
                    "--slotq.events.delivery.scheduler-enabled=true", "--slotq.telemetry.otlp-endpoint=" + ingest + "/v1/traces",
                    "--slotq.telemetry.otlp-authorization=" + basic("telemetry", collectorPassword),
                    "--slotq.observability.database.enabled=true", "--slotq.observability.database.interval=PT1S",
                    "--slotq.observability.database.jdbc-url=" + mysql.getJdbcUrl(),
                    "--slotq.observability.database.username=observer", "--slotq.observability.database.password=" + observerPassword)) {
                int port = ((WebServerApplicationContext) app).getWebServer().getPort();
                Testcontainers.exposeHostPorts(port);
                String prometheusConfig = Files.readString(config.resolve("prometheus.yml"))
                    .replace("host.docker.internal:8080", "host.testcontainers.internal:" + port);
                try (var prometheus = new GenericContainer<>("prom/prometheus:v3.5.0").withNetwork(network).withNetworkAliases("prometheus")
                         .withCopyToContainer(Transferable.of(prometheusConfig), "/etc/prometheus/prometheus.yml")
                         .withCopyFileToContainer(MountableFile.forHostPath(config.resolve("alerts.yml")), "/etc/prometheus/alerts.yml")
                         .withCopyToContainer(Transferable.of(scrapeToken), "/run/secrets/scrape-token")
                         .withCopyToContainer(Transferable.of(promWeb), "/run/secrets/prometheus-web.yml")
                         .withCommand("--config.file=/etc/prometheus/prometheus.yml", "--web.config.file=/run/secrets/prometheus-web.yml")
                         .withExposedPorts(9090).waitingFor(Wait.forListeningPort());
                     var grafana = new GenericContainer<>("grafana/grafana:12.1.1").withNetwork(network)
                         .withEnv("GF_SECURITY_ADMIN_USER", "operator").withEnv("GF_SECURITY_ADMIN_PASSWORD", grafanaPassword)
                         .withEnv("GF_AUTH_ANONYMOUS_ENABLED", "false").withEnv("GF_USERS_ALLOW_SIGN_UP", "false")
                         .withEnv("METRICS_PASSWORD", promPassword)
                         .withCopyFileToContainer(MountableFile.forHostPath(config.resolve("grafana/provisioning")), "/etc/grafana/provisioning")
                         .withCopyFileToContainer(MountableFile.forHostPath(config.resolve("grafana/dashboards")), "/var/lib/grafana/dashboards")
                         .withExposedPorts(3000).waitingFor(Wait.forHttp("/api/health").forPort(3000))) {
                    prometheus.start(); grafana.start();
                    var runner = new ObservabilityEvidenceRunner(output, url(prometheus, 9090), basic("telemetry", promPassword));
                    try {
                        runner.execute(app, mysql, ingress, url(tempo, 3200), url(grafana, 3000), basic("operator", grafanaPassword));
                        runner.evidence.put("result", "PASS");
                    } finally {
                        runner.write("evidence.json", runner.evidence);
                    }
                }
            }
        }
        System.out.println("Observability evidence: " + output);
    }

    private void execute(ConfigurableApplicationContext app, MySQLContainer mysql, GenericContainer<?> ingress,
                         String tempo, String grafana, String grafanaAuth) throws Exception {
        var fixture = fixture(app);
        JdbcTemplate jdbc = fixture.jdbc;
        var worker = fixture.worker;
        var provider = app.getBean(SdkTracerProvider.class);
        evidence.put("startedAt", Instant.now().toString());
        evidence.put("images", List.of("mysql:8.4", "prom/prometheus:v3.5.0", "grafana/grafana:12.1.1", "grafana/tempo:2.8.2", "nginx:1.28-alpine"));
        evidence.put("scope", "Real HTTP cancellation / original event / real waitlist offer and receipt / DB delivery; local alerts unchanged; no Kafka implementation");
        require(get(prometheus + "/api/v1/query?query=up", null).statusCode() == 401, "Prometheus must reject anonymous callers");
        require(get(grafana + "/api/dashboards/uid/slotq-product", null).statusCode() == 401, "Grafana must reject anonymous callers");
        require(HTTP.send(HttpRequest.newBuilder(URI.create(url(ingress, 4318) + "/v1/traces"))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 401,
            "OTLP ingress must reject anonymous callers");
        evidence.put("anonymousTelemetryAccess", "Prometheus=401,Grafana=401,OTLP=401");
        await(() -> value("up{job=\"slotq-product\"}") == 1, Duration.ofSeconds(30), "Prometheus Product scrape");
        await(() -> value("slotq_observation_sample_healthy{sample=\"locks\"}") == 1, Duration.ofSeconds(30), "DB observer grants");
        write("baseline.json", snapshot());

        Release backlog = release(fixture);
        Release dead = release(fixture);
        worker.materialize();
        var store = app.getBean(EventDeliveryStore.class);
        var policy = app.getBean(DeliveryPolicy.class);
        var missingHandler = new EventDeliveryWorker(store,
            new DeliveryTransactions(app.getBean(PlatformTransactionManager.class), store, policy), policy,
            new EventHandlers(List.of()), app.getBean(EventCanonicalizer.class), app.getBean(EntityManagerFactory.class), app.getBean(ProductTelemetry.class));
        DeliveryKey deadKey = key(jdbc, dead.reservation());
        missingHandler.process(missingHandler.claim(deadKey).orElseThrow());
        require(state(jdbc, dead.reservation()).equals("DEAD"), "Fault injection must cause actual DEAD protocol result");
        evidence.put("deadFault", "Exact production target processed by a worker with the handler deliberately absent; no state seeding");

        // Hold the real event append fence while a Product cancellation waits in MySQL.
        var lockFixture = fixture.fixture();
        String lockOwner = fixture.customer();
        String lockReservation = JSON.readTree(fixture.post(lockFixture.base() + "/reservations/holds", lockOwner, lockFixture.body()).body()).get("id").asString();
        try (var lock = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             var blocking = lock.createStatement(); var executor = Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);
            blocking.executeQuery("SELECT sequence_value FROM event_boundary WHERE singleton_id=1 FOR UPDATE").close();
            var waiting = executor.submit(() -> post(fixture.port, lockFixture.base() + "/reservations/" + lockReservation + "/cancel", lockOwner));
            try {
                await(() -> firing("SlotqDatabaseLockWait"), Duration.ofSeconds(40), "Real MySQL lock wait alert");
                write("lock-firing.json", snapshot());
                evidence.put("lockWait", "Actual Product cancellation blocked on event_boundary FOR UPDATE; alert fired before lock released");
            } finally { lock.rollback(); }
            require(waiting.get(20, TimeUnit.SECONDS).statusCode() == 200, "Locked Product request must commit after lock release");
        }
        await(() -> resolved("SlotqDatabaseLockWait"), Duration.ofSeconds(30), "Lock alert resolution");
        write("lock-resolved.json", snapshot());
        await(() -> firing("SlotqDeadDelivery"), Duration.ofSeconds(30), "DEAD alert");
        write("dead-firing.json", snapshot());
        System.out.println("Waiting for the unchanged >60s plus 30s backlog alert contract");
        await(() -> firing("SlotqBacklog"), Duration.ofSeconds(110), "Real aged backlog alert");
        write("backlog-firing.json", snapshot());

        app.getBean(EventReplayService.class).replay(SystemPrincipal.INSTANCE, deadKey, "Disposable observability evidence recovery");
        worker.runCycle();
        require(state(jdbc, backlog.reservation()).equals("DONE") && state(jdbc, dead.reservation()).equals("DONE"), "Recovery must reach authoritative DONE");
        require(offerCount(jdbc, backlog.venue()) == 1 && offerCount(jdbc, dead.venue()) == 1, "Recovery must create each real waitlist offer exactly once");
        require(receipt(jdbc, backlog.reservation()).equals("PROMOTED") && receipt(jdbc, dead.reservation()).equals("PROMOTED"), "Recovery must commit real consumer receipts");
        await(() -> resolved("SlotqDeadDelivery") && resolved("SlotqBacklog"), Duration.ofSeconds(30), "Backlog and DEAD alert resolution");
        write("recovered.json", snapshot());
        evidence.put("recovery", Map.of("backlogDelivery", "DONE", "deadDelivery", "DONE", "offers", 2,
            "receipts", "PROMOTED", "replayAuditRows", jdbc.queryForObject("SELECT COUNT(*) FROM event_replay_audit", Integer.class)));

        provider.forceFlush().join(10, TimeUnit.SECONDS);
        String originTrace = jdbc.queryForObject("SELECT origin_trace_id FROM event_records WHERE aggregate_id=?", String.class,
            ProductObservabilityIntegrationTests.bytes(UUID.fromString(backlog.reservation())));
        await(() -> status(tempo + "/api/traces/" + originTrace) == 200, Duration.ofSeconds(30), "Stored original request trace");
        JsonNode trace = JSON.readTree(get(tempo + "/api/traces/" + originTrace, null).body());
        write("request-trace.json", trace);
        require(trace.toString().contains("product.request") && trace.toString().contains("product.event.append"), "Stored trace must include request and append");
        evidence.put("trace", Map.of("originTraceId", originTrace, "requestId", backlog.requestId(), "storedInTempo", true));
        String traceSearch = tempo + "/api/search?q=" + encode("{ span.slotq.request.id = \"" + backlog.requestId() + "\" }");
        await(() -> searchCount(traceSearch) >= 2, Duration.ofSeconds(30), "Stored linked consumer trace");
        JsonNode traces = JSON.readTree(get(traceSearch, null).body());
        write("correlated-trace-search.json", traces);
        boolean effectFound = false;
        for (JsonNode candidate : traces.path("traces")) {
            String id = candidate.path("traceID").asString();
            if (id.equals(originTrace)) continue;
            JsonNode effectTrace = JSON.readTree(get(tempo + "/api/traces/" + id, null).body());
            if (effectTrace.toString().contains("product.event.effect")) {
                require(effectTrace.toString().contains("links"), "Consumer trace must retain a producer link");
                write("effect-trace.json", effectTrace);
                effectFound = true;
                break;
            }
        }
        require(effectFound, "Tempo search must resolve the real effect trace from original request correlation");

        verifyDashboard(grafana, grafanaAuth);
        JsonNode kafka = query("{__name__=~\"slotq_kafka_.*\"}");
        require(kafka.path("data").path("result").isEmpty(), "Missing Kafka signals must stay absent");
        write("kafka-missing.json", kafka);
        evidence.put("kafkaSignals", "Empty result: not instrumented, never a fabricated zero");

        double failureBefore = exportFailures(app);
        DockerClientFactory.instance().client().pauseContainerCmd(ingress.getContainerId()).exec();
        try {
            Release outage = release(fixture);
            worker.runCycle();
            provider.forceFlush().join(12, TimeUnit.SECONDS);
            await(() -> exportFailures(app) > failureBefore, Duration.ofSeconds(20), "Real OTLP unavailable export failure");
            require(state(jdbc, outage.reservation()).equals("DONE") && offerCount(jdbc, outage.venue()) == 1
                && receipt(jdbc, outage.reservation()).equals("PROMOTED"), "Collector outage must preserve Product effect/receipt/DONE");
            evidence.put("collectorOutage", Map.of("mechanism", "Actual OTLP ingress container paused", "delivery", "DONE",
                "offerCount", 1, "receipt", "PROMOTED", "failedExportSpans", exportFailures(app) - failureBefore));
        } finally { DockerClientFactory.instance().client().unpauseContainerCmd(ingress.getContainerId()).exec(); }
        evidence.put("completedAt", Instant.now().toString());
    }

    private void verifyDashboard(String grafana, String auth) throws Exception {
        var dashboard = get(grafana + "/api/dashboards/uid/slotq-product", auth);
        require(dashboard.statusCode() == 200, "Provisioned dashboard must load through authenticated Grafana");
        JsonNode body = JSON.readTree(dashboard.body());
        Map<String, Object> panels = new LinkedHashMap<>();
        for (JsonNode panel : body.path("dashboard").path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                String expression = target.path("expr").asString();
                if (expression.isBlank() || expression.contains("$")) continue;
                var result = get(grafana + "/api/datasources/proxy/uid/slotq-prometheus/api/v1/query?query=" + encode(expression), auth);
                require(result.statusCode() == 200, "Dashboard panel query must reach authenticated Prometheus");
                panels.put(panel.path("title").asString() + ":" + target.path("refId").asString(), JSON.readTree(result.body()));
            }
        }
        require(!panels.isEmpty(), "Dashboard must have evaluated panels");
        write("grafana-panels.json", panels);
        evidence.put("grafana", Map.of("uid", "slotq-product", "title", body.path("dashboard").path("title").asString(), "queriedPanels", panels.size()));
    }

    private Map<String, Object> snapshot() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("at", Instant.now().toString());
        for (String query : List.of("slotq_db_delivery_targets", "slotq_db_delivery_oldest_created_age_seconds", "slotq_db_lock_waits",
                "slotq_db_lock_waits_cumulative", "slotq_db_lock_deadlocks_cumulative", "slotq_observation_sample_healthy", "ALERTS")) data.put(query, query(query));
        return data;
    }
    private boolean firing(String alert) { return value("ALERTS{alertname=\"" + alert + "\",alertstate=\"firing\"}") == 1; }
    private boolean resolved(String alert) {
        try { return query("ALERTS{alertname=\"" + alert + "\",alertstate=\"firing\"}").path("data").path("result").isEmpty(); }
        catch (Exception ignored) { return false; }
    }
    private double value(String expression) {
        try {
            JsonNode result = query(expression).path("data").path("result");
            return result.isEmpty() ? Double.NaN : Double.parseDouble(result.get(0).path("value").get(1).asString());
        } catch (Exception ignored) { return Double.NaN; }
    }
    private JsonNode query(String expression) throws Exception {
        var response = get(prometheus + "/api/v1/query?query=" + encode(expression), promAuth);
        require(response.statusCode() == 200, "Prometheus query must succeed");
        return JSON.readTree(response.body());
    }
    private void write(String name, Object content) throws Exception { Files.writeString(output.resolve(name), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(content)); }
    private static void await(BooleanSupplier ready, Duration timeout, String label) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!ready.getAsBoolean()) {
            require(System.nanoTime() < deadline, "Timed out: " + label);
            Thread.sleep(500);
        }
    }
    private static ProductObservabilityIntegrationTests fixture(ConfigurableApplicationContext app) {
        var f = new ProductObservabilityIntegrationTests();
        f.tenants = app.getBean(TenantUseCase.class); f.venues = app.getBean(VenueConfigurationUseCase.class);
        f.resources = app.getBean(ResourceUseCase.class); f.slots = app.getBean(SlotInventoryUseCase.class);
        f.access = app.getBean(AccessControlProvisioning.class); f.worker = app.getBean(EventDeliveryWorker.class);
        f.jdbc = app.getBean(JdbcTemplate.class); f.credentials = app.getBean(ProductObservabilityIntegrationTests.Credentials.class);
        f.port = ((WebServerApplicationContext) app).getWebServer().getPort();
        return f;
    }
    private static Release release(ProductObservabilityIntegrationTests f) throws Exception {
        var fixture = f.fixture(); String owner = f.customer(), waiter = f.customer();
        var hold = f.post(fixture.base() + "/reservations/holds", owner, fixture.body());
        require(hold.statusCode() == 201, "Real HOLD must succeed");
        String reservation = JSON.readTree(hold.body()).get("id").asString();
        require(f.post(fixture.base() + "/waitlist-entries", waiter, fixture.body()).statusCode() == 201, "Real waitlist registration must succeed");
        var cancelled = f.post(fixture.base() + "/reservations/" + reservation + "/cancel", owner, null);
        require(cancelled.statusCode() == 200, "Real release must commit");
        return new Release(reservation, fixture.venue(), cancelled.headers().firstValue("X-Request-ID").orElseThrow());
    }
    private static DeliveryKey key(JdbcTemplate db, String reservation) {
        return db.queryForObject("SELECT HEX(d.tenant_id),HEX(d.event_id),HEX(d.registration_id) FROM event_deliveries d JOIN event_records e ON e.event_id=d.event_id WHERE e.aggregate_id=?",
            (row, n) -> new DeliveryKey(new TenantId(uuid(row.getString(1))), new EventId(uuid(row.getString(2))), uuid(row.getString(3))), ProductObservabilityIntegrationTests.bytes(UUID.fromString(reservation)));
    }
    private static UUID uuid(String hex) { return UUID.fromString(hex.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5")); }
    private static String state(JdbcTemplate db, String reservation) {
        return db.queryForObject("SELECT d.state FROM event_deliveries d JOIN event_records e ON e.event_id=d.event_id WHERE e.aggregate_id=?", String.class, ProductObservabilityIntegrationTests.bytes(UUID.fromString(reservation)));
    }
    private static String receipt(JdbcTemplate db, String reservation) {
        return db.queryForObject("SELECT p.outcome FROM waitlist_promotion_receipts p JOIN event_records e ON e.event_id=p.event_id WHERE e.aggregate_id=?", String.class, ProductObservabilityIntegrationTests.bytes(UUID.fromString(reservation)));
    }
    private static int offerCount(JdbcTemplate db, UUID venue) { return db.queryForObject("SELECT COUNT(*) FROM waitlist_offers WHERE venue_id=?", Integer.class, ProductObservabilityIntegrationTests.bytes(venue)); }
    private static double exportFailures(ConfigurableApplicationContext app) {
        var counter = app.getBean(MeterRegistry.class).find("slotq.telemetry.export.spans").tag("outcome", "failure").counter();
        return counter == null ? 0 : counter.count();
    }
    private static HttpResponse<String> post(int port, String path, String token) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> get(String url, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).header("Accept", "application/json");
        if (authorization != null) request.header("Authorization", authorization);
        return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static int status(String url) { try { return get(url, null).statusCode(); } catch (Exception ignored) { return 0; } }
    private static int searchCount(String url) { try { return JSON.readTree(get(url, null).body()).path("traces").size(); } catch (Exception ignored) { return 0; } }
    private static String url(GenericContainer<?> container, int port) { return "http://" + container.getHost() + ":" + container.getMappedPort(port); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String basic(String user, String secret) { return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8)); }
    private static String secret() { return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private record Release(String reservation, UUID venue, String requestId) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @Primary Clock evidenceClock() { return Clock.fixed(ProductObservabilityIntegrationTests.NOW, ZoneOffset.UTC); }
        @Bean ProductObservabilityIntegrationTests.Credentials credentials() { return new ProductObservabilityIntegrationTests.Credentials(); }
        @Bean ThreadPoolTaskScheduler taskScheduler() { return new ThreadPoolTaskScheduler() {
            @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) { return new ProductObservabilityIntegrationTests.PausedTimer(); }
            @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant start, Duration delay) { return new ProductObservabilityIntegrationTests.PausedTimer(); }
        }; }
    }
}
