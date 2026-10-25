package com.slotq.mcp;

import com.slotq.SlotqApplication;
import com.slotq.ai.flow.*;
import com.slotq.ai.router.*;
import com.slotq.ai.runtime.*;
import com.slotq.ai.runtime.RuntimeContract.*;
import com.slotq.auth.access.*;
import com.slotq.auth.domain.*;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.persistence.ActorAccessService;
import com.slotq.booking.application.*;
import com.slotq.booking.domain.*;
import com.slotq.events.application.*;
import com.slotq.experiments.waitlist.WaitlistRecoveryDatabase;
import com.slotq.knowledge.domain.Corpus;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.waitlist.application.WaitlistUseCase;
import com.slotq.waitlist.application.WaitlistRegistrationKey;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import javax.management.ObjectName;
import javax.net.ssl.SSLContext;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;

import static com.slotq.mcp.M8CapacityEvidence.*;
import static com.slotq.mcp.RepresentativeFlowIntegrationTests.bytes;

/** Issue #181 diagnostic: real HTTPS/Auth/MCP/Runtime and DB_DIRECT, with no external model calls. */
public final class M8CapacityRunner {
    private static final List<Map<String, Object>> AUTH = Collections.synchronizedList(new ArrayList<>());
    private final ConfigurableApplicationContext context;
    private final RepresentativeFlowIntegrationTests f = new RepresentativeFlowIntegrationTests();
    private final JdbcTemplate observer;
    private final HikariDataSource pool;
    private final AgentRuntime runtime;
    private final M8CapacityResources resources;
    private final M8CapacityProbes probes;
    private final EventDeliveryWorker observationWorker;
    private final AtomicInteger observationActive = new AtomicInteger();
    private final AtomicInteger observationFailures = new AtomicInteger();
    private final List<Map<String, Object>> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> samples = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> phases = new ArrayList<>();
    private final List<Map<String, Object>> queryCosts = new ArrayList<>();
    private final List<Map<String, Object>> backgroundActions = new ArrayList<>();
    private final List<UUID> targets = new ArrayList<>();
    private final List<Reservation> background = new ArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger providerCalls = new AtomicInteger();
    private final long seed;
    private String session;
    private volatile String phase = "setup";

    private M8CapacityRunner(ConfigurableApplicationContext context, MySQLContainer mysql, long seed) throws Exception {
        this.context = context;
        this.seed = seed;
        context.getAutowireCapableBeanFactory().autowireBean(f);
        f.access = context.getBean(ActorAccessService.class);
        pool = context.getBean(HikariDataSource.class);
        observer = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword()));
        resources = new M8CapacityResources(mysql);
        probes = new M8CapacityProbes(f, observer, this::http, context);
        observationWorker = new EventDeliveryWorker(f.deliveries, f.deliveryTransactions, f.policy, f.handlers,
            f.canonicalizer, f.entityManagerFactory, new DeliveryExecutionScope("operations.event-observation", "DB_DIRECT", 1));
        var providerTraces = Collections.synchronizedMap(new IdentityHashMap<ProviderProtocol.Request, String>());
        var contexts = new RepresentativeContext(a -> a.tenantId().value().equals(f.tenant));
        PlanContextSource tracedContexts = new PlanContextSource() {
            public Context prepare(DelegatedActor actor, Stage stage, Result facts, Plan plan) {
                Context result = contexts.prepare(actor, stage, facts, plan);
                var trace = M8CapacityObservation.CURRENT.get();
                providerTraces.put(result.request(), trace == null ? "unbound" : trace.id);
                return result;
            }
            public boolean permitsDispatch(DelegatedActor actor, Plan plan, Context context) {
                return contexts.permitsDispatch(actor, plan, context);
            }
            public boolean acceptsAnswer(Plan plan, Result facts, ProviderProtocol.Output output) {
                return contexts.acceptsAnswer(plan, facts, output);
            }
        };
        runtime = new AgentRuntime(f.access, f.registry, f.engine, f.approvals, (model, classification, request, budget) -> {
            int ordinal = providerCalls.incrementAndGet();
            long started = System.nanoTime();
            String providerPhase = phase;
            budget.begin(2000);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(ordinal % 5 == 0 ? 250 : 50));
            budget.end(2000, 100L);
            M8CapacityObservation.PROVIDERS.add(Map.of("ordinal", ordinal, "trace", Objects.toString(providerTraces.remove(request), "unbound"),
                "phase", providerPhase, "startedNanos", started, "endedNanos", System.nanoTime(), "injectedFailure", ordinal % 7 == 0));
            if (ordinal % 7 == 0) return RepresentativeFlowIntegrationTests.response(null, GeminiAdapter.Failure.TEMPORARY_OVERLOAD, true);
            var output = new ProviderProtocol.Output("ANSWER", "none", "", 0, "NOT_APPLICABLE",
                List.copyOf(request.knownSources()), List.copyOf(request.knownClaims()), "합성 부하 응답입니다.");
            return RepresentativeFlowIntegrationTests.response(output, null, true);
        }, RepresentativeRouterProfile.candidates(), tracedContexts,
            new LocalAdmission(new LocalAdmission.Limit(200, 200, 4), 128, System::nanoTime), f.clock, 4096, 4);
    }

    public static void main(String[] args) throws Exception {
        String stage = System.getProperty("slotq.capacity.stage", "pilot");
        require(Set.of("pilot", "verification").contains(stage), "Unknown stage");
        int size = Integer.getInteger("slotq.capacity.size", 16);
        int maximum = Integer.getInteger("slotq.capacity.pool", 4);
        long seed = Long.getLong("slotq.capacity.seed", 18101L);
        require(size >= 4 && size <= 256 && maximum >= 3 && maximum <= 32, "Bounded diagnostic settings required");
        Path output = Path.of(System.getProperty("slotq.capacity.output", "build/m8-capacity/" + UUID.randomUUID())).toAbsolutePath();
        require(output.startsWith(Path.of("build").toAbsolutePath()) && !Files.exists(output), "Use a fresh ignored build directory");
        Files.createDirectories(output);
        var criteria = new LinkedHashMap<String, Object>();
        if (stage.equals("verification")) {
            Path path = Path.of(System.getProperty("slotq.capacity.criteria", "")).toAbsolutePath().normalize();
            require(path.startsWith(Path.of("build").toAbsolutePath().normalize()), "Criteria must remain in ignored local build directory");
            var frozen = validateCriteria(path, maximum, size, Instant.now());
            require(frozen.path("rate").asInt() == Integer.getInteger("slotq.capacity.rate", 20), "Frozen arrival rate mismatch");
            require(frozen.path("seed").asLong() == seed, "Frozen seed mismatch");
            criteria.put("sha256", digest(path));
            criteria.put("values", frozen);
        }
        try (var mysql = new MySQLContainer("mysql:8.4").withDatabaseName("m8_capacity")
                .withEnv("MYSQL_ROOT_HOST", "%").withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            int port = RepresentativeFlowIntegrationTests.PORT;
            String origin = RepresentativeFlowIntegrationTests.ORIGIN;
            try (var context = new SpringApplicationBuilder(SlotqApplication.class, Configuration.class).run(
                    "--logging.level.root=ERROR", "--spring.main.banner-mode=off", "--server.address=127.0.0.1",
                    "--server.port=" + port, "--server.ssl.enabled=true", "--server.tomcat.mbeanregistry.enabled=true",
                    "--server.ssl.key-store=" + McpHttpIntegrationTests.KEYSTORE.toUri(),
                    "--server.ssl.key-store-password=" + McpHttpIntegrationTests.PASSWORD, "--server.ssl.key-store-type=PKCS12",
                    "--spring.datasource.url=" + mysql.getJdbcUrl(), "--spring.datasource.username=" + mysql.getUsername(),
                    "--spring.datasource.password=" + mysql.getPassword(), "--spring.datasource.hikari.maximum-pool-size=" + maximum,
                    "--spring.datasource.hikari.minimum-idle=" + maximum, "--spring.datasource.hikari.connection-timeout=2000",
                    "--spring.datasource.hikari.data-source-properties.socketTimeout=2000",
                    "--spring.datasource.hikari.data-source-properties.connectTimeout=2000",
                    "--spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=2, lock_wait_timeout=2, wait_timeout=5, max_execution_time=2000",
                    "--spring.transaction.default-timeout=10s", "--spring.jpa.properties.jakarta.persistence.query.timeout=2000",
                    "--spring.jpa.properties.hibernate.session_factory.statement_inspector=" + SqlCounter.class.getName(),
                    "--slotq.auth.dev-bootstrap-enabled=false", "--slotq.mcp.enabled=true",
                    "--slotq.mcp.origin=" + origin, "--slotq.mcp.product-origin=" + origin,
                    "--slotq.mcp.handler-budget=PT5S", "--slotq.mcp.workers=8", "--slotq.mcp.ingress=8",
                    "--slotq.mcp.quota.rate=200", "--slotq.mcp.quota.burst=200", "--slotq.mcp.quota.concurrency=4",
                    "--spring.task.scheduling.pool.size=2", "--slotq.waitlist.promotion.enabled=true",
                    "--slotq.waitlist.promotion.maintenance-enabled=true", "--slotq.waitlist.promotion.maintenance-interval=PT1S",
                    "--slotq.events.delivery.scheduler-enabled=true", "--slotq.events.delivery.poll-interval=PT0.2S",
                    "--slotq.events.delivery.batch-size=2", "--slotq.operations.observation.enabled=true",
                    "--slotq.waitlist.promotion.maintenance-batch-size=8",
                    "--slotq.waitlist.promotion.discovery-batch-size=8")) {
                var runner = new M8CapacityRunner(context, mysql, seed);
                try {
                    runner.seed(size);
                    var manifest = runner.manifest(mysql, stage, size, criteria);
                    if (stage.equals("verification")) {
                        var frozen = JSON.valueToTree(criteria.get("values"));
                        var pilot = JSON.readTree(Files.readString(Path.of(frozen.path("pilotPath").asString())));
                        require(pilot.path("manifest").path("sourceDigests").equals(JSON.valueToTree(manifest.get("sourceDigests"))), "Source changed since pilot");
                    }
                    write(output.resolve("manifest.json"), manifest);
                    try (var sampler = Executors.newSingleThreadScheduledExecutor();
                            var observation = Executors.newSingleThreadScheduledExecutor()) {
                        observation.scheduleWithFixedDelay(runner::observeEvents, 0, 200, TimeUnit.MILLISECONDS);
                        sampler.scheduleAtFixedRate(runner::sample, 0, 250, TimeUnit.MILLISECONDS);
                        runner.calibrate();
                        runner.load("warmup", 10, 3, false);
                        runner.load("distributed", Integer.getInteger("slotq.capacity.rate", 20), stage.equals("pilot") ? 10 : 30, false);
                        runner.probes.seed();
                        try (var probeClients = Executors.newSingleThreadExecutor()) {
                            var probeRun = probeClients.submit(() -> runner.probes.run());
                            runner.load("probes", 5, 10, false);
                            probeRun.get(30, TimeUnit.SECONDS);
                        }
                        runner.load("hot-slot", 80, 8, true);
                        runner.load("burst", 320, 3, false);
                        runner.quietRecovery();
                        runner.load("recovery", 5, 10, false);
                        runner.phase = "query-scale";
                        M8CapacityObservation.phase = runner.phase;
                        runner.queryScale(size);
                        runner.phase = "final-quiet";
                        M8CapacityObservation.phase = runner.phase;
                        runner.quietFor(Duration.ofSeconds(3));
                        observation.shutdown();
                        require(observation.awaitTermination(15, TimeUnit.SECONDS), "Observer did not finish");
                        sampler.shutdown();
                        require(sampler.awaitTermination(10, TimeUnit.SECONDS), "Sampler did not finish");
                    }
                    var raw = new LinkedHashMap<String, Object>();
                    raw.put("manifest", manifest);
                    raw.put("schemaVersion", "slotq-m8-capacity/v2");
                    raw.put("phases", runner.phases);
                    raw.put("requests", runner.requests);
                    raw.put("samples", runner.samples);
                    raw.put("queryCosts", runner.queryCosts);
                    raw.put("backgroundActions", runner.backgroundActions);
                    raw.put("providerCalls", runner.providerCalls.get());
                    raw.put("observationWorkerFailures", runner.observationFailures.get());
                    raw.put("authCalls", List.copyOf(AUTH));
                    raw.put("acquisitions", List.copyOf(M8CapacityObservation.ACQUISITIONS));
                    raw.put("http", List.copyOf(M8CapacityObservation.HTTP));
                    raw.put("tools", List.copyOf(M8CapacityObservation.TOOLS));
                    raw.put("providers", List.copyOf(M8CapacityObservation.PROVIDERS));
                    raw.put("resourceSamples", List.copyOf(runner.resources.samples));
                    raw.put("probes", runner.probes.results);
                    raw.put("oracle", Map.of("observation", "INCOMPLETE"));
                    raw.put("endedAt", Instant.now().toString());
                    raw.put("limitations", List.of("Single local JVM/Docker host; bounded declared workload only; no SLA or actual-model claim",
                        "Docker CPU/memory and MySQL file IO are measured; host process isolation and unrelated device workloads are not claimed",
                        "Overload is clipped at the declared client in-flight bound; it does not establish a universal server peak"));
                    write(output.resolve("raw.json"), raw);
                    raw.put("oracle", runner.oracle());
                    write(output.resolve("raw.json"), raw);
                    write(output.resolve("summary.json"), summarize(JSON.valueToTree(raw)));
                    Files.writeString(output.resolve("raw.sha256"), digest(output.resolve("raw.json")) + "\n");
                    System.out.println("M8 capacity evidence written: " + output);
                } finally { runner.resources.close(); runner.runtime.close(); }
            }
        }
    }

    private void seed(int size) throws Exception {
        f.http = HttpClient.newBuilder().sslContext(McpHttpIntegrationTests.tls()).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).build();
        f.tenant = f.tenants.createTenant().id().value();
        f.day = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        f.venue = f.createVenue(f.tenant);
        f.customer = f.identity("CUSTOMER", f.tenant, f.venue);
        f.owner = f.identity("OWNER", f.tenant, f.venue);
        for (int i = 0; i < size; i++) {
            UUID target = createSlot("Capacity " + i);
            targets.add(target);
            UUID backgroundSlot = createSlot("Background " + i, "14:00:00Z");
            var actor = new AuthenticatedPrincipal(new PrincipalId(f.customer.principal()));
            var held = f.reservations.createHold(new ReservationUseCase.CreateHold(f.venue, new SlotInventoryId(backgroundSlot), actor, 2)).reservation();
            f.reservations.transition(f.venue, held.id(), actor, ReservationCommand.CONFIRM);
            background.add(held);
            var waiting = new PrincipalId(new UUID(seed + 1, i + 1));
            context.getBean(AccessControlProvisioning.class).registerPrincipal(waiting);
            context.getBean(WaitlistUseCase.class).register(new WaitlistUseCase.CreateRegistration(f.venue,
                new SlotInventoryId(backgroundSlot), 2, new WaitlistRegistrationKey(new UUID(seed, i)), new AuthenticatedPrincipal(waiting)));
            f.publish(Corpus.Visibility.VENUE_OPERATOR, "guide 합성 안내 " + i);
        }
        var initialized = f.rpc(f.owner.delegation().value(), null, "initialize",
            Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo", Map.of("name", "capacity", "version", "1")), 1);
        require(initialized.statusCode() == 200, "MCP initialization failed");
        session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
        require(f.rpc(f.owner.delegation().value(), session, "notifications/initialized", Map.of(), null).statusCode() == 202, "MCP readiness failed");
        require(!AUTH.isEmpty(), "Auth timing observation missing");
    }

    private UUID createSlot(String name) {
        return createSlot(name, "12:00:00Z");
    }

    private UUID createSlot(String name, String time) {
        var resource = f.resources.createResource(new ResourceUseCase.CreateResource(new TenantId(f.tenant), f.venue, name, 4));
        return f.slots.createSlot(new SlotInventoryUseCase.CreateSlot(new TenantId(f.tenant), f.venue, resource.id(), f.day + "T" + time)).id().value();
    }

    private void load(String name, int rate, int seconds, boolean hot) throws Exception {
        phase = name;
        M8CapacityObservation.phase = name;
        // No generator queue: each planned arrival is attempted even while preceding requests are pending.
        var outstanding = new Semaphore(64);
        try (var clients = Executors.newVirtualThreadPerTaskExecutor(); var backgroundClients = Executors.newSingleThreadExecutor()) {
            int planned = rate * seconds;
            var commands = new ArrayList<Command>();
            long preparationStarted = System.nanoTime();
            for (int i = 0; i < planned; i++) commands.add(command(i, hot));
            long start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
            var plan = new LinkedHashMap<String, Object>(Map.of("name", name, "rate", rate, "seconds", seconds, "planned", planned,
                "startNanos", start, "preparationMillis", (System.nanoTime() - preparationStarted) / 1e6));
            phases.add(plan);
            for (int i = 0; i < planned; i++) {
                long scheduled = start + i * 1_000_000_000L / rate;
                while (System.nanoTime() < scheduled) LockSupport.parkNanos(scheduled - System.nanoTime());
                Command command = commands.get(i);
                if (outstanding.tryAcquire()) {
                    clients.execute(() -> {
                        try { execute(name, scheduled, command); }
                        finally { outstanding.release(); }
                    });
                } else {
                    requests.add(Map.of("phase", name, "operation", command.operation(), "scheduledNanos", scheduled,
                        "outcome", "GENERATOR_REJECTED", "responded", false, "dispatchLagMs", (System.nanoTime() - scheduled) / 1e6,
                        "scheduledLatencyMs", (System.nanoTime() - scheduled) / 1e6));
                }
                if (name.equals("distributed") && i < background.size()) {
                    Reservation held = background.get(i);
                    backgroundClients.execute(() -> {
                    // Public command, one attempt. A failure must not be retried or asserted rolled back.
                    try {
                        f.reservations.transition(f.venue, held.id(), new AuthenticatedPrincipal(new PrincipalId(f.customer.principal())), ReservationCommand.CANCEL);
                        backgroundActions.add(Map.of("phase", name, "operation", "background-cancel", "outcome", "SUCCESS"));
                    } catch (RuntimeException failure) {
                        backgroundActions.add(Map.of("phase", name, "operation", "background-cancel", "outcome", "UNKNOWN"));
                    }
                    });
                }
            }
            clients.shutdown();
            require(clients.awaitTermination(30, TimeUnit.SECONDS), "Generator drain timeout");
            backgroundClients.shutdown();
            require(backgroundClients.awaitTermination(30, TimeUnit.SECONDS), "Background command drain timeout");
            plan.put("drainEndedNanos", System.nanoTime());
        }
        System.out.println("capacity phase finished: " + name);
    }

    private Command command(int index, boolean hot) {
        if (phase.equals("generator-calibration")) return new Command("generator-echo", () -> http(HttpRequest.newBuilder(URI.create(calibrationOrigin)).timeout(Duration.ofSeconds(8)).GET().build()));
        return switch (index % 10) {
            case 0, 1, 2, 3 -> {
                UUID target = targets.get(hot ? 0 : (index / 2) % targets.size());
                String key = new UUID(seed + (hot ? 2 : 0), (hot ? index / 2 : (index / 2) % targets.size()) + 1).toString();
                var credential = f.access.issueHold(f.customer.delegation().delegationId(), target, 2, key, Instant.now().plusSeconds(180));
                var request = HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN + "/api/v1/venues/" + f.venue.value() + "/reservations/holds"))
                    .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + credential.value())
                    .header("Idempotency-Key", key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("slotInventoryId", target, "partySize", 2)))).build();
                yield new Command("product-hold", () -> http(request), Map.of("slot", target.toString(), "keyDigest", Corpus.digest(key),
                    "customer", f.customer.principal().toString(), "venue", f.venue.value().toString(), "tenant", f.tenant.toString()));
            }
            case 4 -> new Command("availability", () -> http(availability()));
            case 5 -> new Command("product-get", () -> {
                var held = background.get(index % background.size());
                var credential = f.access.issueProduct(f.customer.delegation().delegationId(), ProductOperation.RESERVATION_GET,
                    held.id().value(), Instant.now().plusSeconds(30));
                return http(HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN + "/api/v1/venues/" + f.venue.value()
                    + "/reservations/" + held.id().value())).header("Authorization", "Bearer " + credential.value()).GET().timeout(Duration.ofSeconds(8)).build());
            });
            case 6, 7 -> new Command("mcp-management", () -> {
                var response = rpc("tools/call",
                    Map.of("name", "management.reservations.list", "arguments", Map.of("date", f.day.toString())), 100);
                return classify(response, true);
            });
            case 8 -> new Command("runtime-synthetic", () -> {
                var flows = new RepresentativeFlows(runtime, f.limits(Duration.ofSeconds(3)));
                var flow = flows.management(f.owner.delegation().value(), f.day, "guide");
                flows.prepareReads(flow, f.owner.delegation().value());
                var result = runtime.generate(flow.id(), f.owner.delegation().value());
                var trace = M8CapacityObservation.CURRENT.get();
                trace.runtimeId = result.runId().toString();
                trace.runtimeFailure = result.failure();
                return new Observation(result.answer() == null ? "UNAVAILABLE" : "SUCCESS", true, 0, 0);
            });
            default -> new Command("mcp-observations", () -> classify(rpc("tools/call",
                Map.of("name", "management.event-observations.list", "arguments", Map.of("from", Instant.now().minusSeconds(3600).toString(),
                    "to", Instant.now().plusSeconds(60).toString(), "limit", 20)), 101), true));
        };
    }

    private void execute(String name, long scheduled, Command command) {
        var trace = new M8CapacityObservation.Trace(UUID.randomUUID().toString());
        trace.target = Objects.toString(command.material().get("slot"), null);
        trace.keyDigest = Objects.toString(command.material().get("keyDigest"), null);
        M8CapacityObservation.CURRENT.set(trace);
        long started = System.nanoTime();
        int active = inFlight.incrementAndGet();
        Observation observation;
        try { observation = command.action().call(); }
        catch (RuntimeFailure failure) {
            String outcome = switch (failure.reason()) {
                case ADMISSION -> "REJECTED";
                case UNAVAILABLE -> "UNAVAILABLE";
                case AUTHORITY -> "DENIED";
                default -> "UNKNOWN";
            };
            observation = new Observation(outcome, false, 0, 0);
        }
        catch (Exception failure) { observation = new Observation("UNKNOWN", false, 0, 0); }
        long ended = System.nanoTime();
        inFlight.decrementAndGet();
        var row = new LinkedHashMap<String, Object>();
        row.put("phase", name); row.put("operation", command.operation()); row.put("scheduledNanos", scheduled);
        row.put("startedNanos", started); row.put("endedNanos", ended); row.put("inFlight", active);
        row.put("dispatchLagMs", (started - scheduled) / 1e6); row.put("scheduledLatencyMs", (ended - scheduled) / 1e6);
        row.put("serviceLatencyMs", (ended - started) / 1e6); row.put("outcome", observation.outcome());
        row.put("responded", observation.responded()); row.put("responseBytes", observation.bytes()); row.put("hibernateSql", observation.sql());
        row.put("trace", trace.id);
        row.put("material", command.material());
        row.put("knownTarget", Objects.toString(trace.knownTarget, ""));
        row.put("httpStatus", trace.status);
        row.put("runtimeId", Objects.toString(trace.runtimeId, ""));
        row.put("runtimeFailure", Objects.toString(trace.runtimeFailure, ""));
        row.put("wireRequestId", Objects.toString(trace.wireRequestId, ""));
        row.put("jdbcExecutions", trace.responseSql);
        requests.add(row);
        M8CapacityObservation.CURRENT.remove();
    }

    private HttpRequest availability() {
        return HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN + "/api/v1/venues/" + f.venue.value()
            + "/availability?date=" + f.day + "&partySize=2")).timeout(Duration.ofSeconds(8)).GET().build();
    }

    private Observation http(HttpRequest request) throws Exception {
        var trace = M8CapacityObservation.CURRENT.get();
        if (trace != null) {
            var builder = HttpRequest.newBuilder(request, (name, value) -> true).header("X-Capacity-Id", trace.id);
            if (trace.dropResponse) builder.header("X-Capacity-Drop", "true");
            request = builder.build();
        }
        var response = f.http.send(request, HttpResponse.BodyHandlers.ofString());
        if (trace != null) {
            trace.status = response.statusCode();
            trace.responseSql = response.headers().firstValue("X-Capacity-Jdbc-Sql").map(Integer::parseInt).orElse(-1);
            String location = response.headers().firstValue("Location").orElse("");
            if (!location.isEmpty()) trace.knownTarget = location.substring(location.lastIndexOf('/') + 1);
            else if (!response.body().isBlank()) {
                try {
                    var id = JSON.readTree(response.body()).path("id").asString();
                    if (!id.isEmpty()) trace.knownTarget = id;
                } catch (RuntimeException malformed) { }
            }
        }
        return classify(response, false);
    }

    static Observation classify(HttpResponse<String> response, boolean mcp) {
        String outcome;
        if (response.statusCode() == 429) outcome = "REJECTED";
        else if (response.statusCode() == 503) outcome = "UNAVAILABLE";
        else if (response.statusCode() == 409) outcome = "BUSINESS_CONFLICT";
        else if (response.statusCode() >= 500) outcome = "UNKNOWN";
        else if (response.statusCode() >= 400) outcome = "DENIED";
        else if (mcp) {
            try {
                var result = JSON.readTree(response.body()).path("result");
                if (!result.isObject() || !result.has("isError") || !result.path("structuredContent").isObject()) outcome = "UNKNOWN";
                else if (result.path("isError").asBoolean()) {
                    String body = response.body();
                    outcome = body.contains("rate_limited") ? "REJECTED" : body.contains("unavailable") ? "UNAVAILABLE"
                        : body.contains("unknown") || body.contains("timeout") ? "UNKNOWN" : "DENIED";
                } else outcome = "SUCCESS";
            } catch (RuntimeException malformed) { outcome = "UNKNOWN"; }
        } else outcome = response.body().isBlank() ? "UNKNOWN" : "SUCCESS";
        return new Observation(outcome, true, response.body().getBytes(StandardCharsets.UTF_8).length,
            response.headers().firstValue("X-Capacity-Hibernate-Sql").map(Integer::parseInt).orElse(-1));
    }

    private void queryScale(int size) throws Exception {
        var credential = f.access.issueProduct(f.owner.delegation().delegationId(), ProductOperation.MANAGEMENT_LIST, null, Instant.now().plusSeconds(120));
        for (int i = 0; i < 20; i++) {
            for (var command : List.of(new Command("availability", () -> http(availability())), new Command("management", () -> http(
                    HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN + "/api/v1/management/venues/" + f.venue.value() + "/reservations?date=" + f.day))
                        .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + credential.value()).GET().build())))) {
                var trace = new M8CapacityObservation.Trace(UUID.randomUUID().toString());
                M8CapacityObservation.CURRENT.set(trace);
                long started = System.nanoTime();
                Observation result = command.action().call();
                queryCosts.add(Map.of("operation", command.operation(), "size", size, "repeat", i,
                    "elapsedMs", (System.nanoTime() - started) / 1e6, "hibernateSql", result.sql(), "jdbcExecutions", trace.responseSql,
                    "responseBytes", result.bytes(), "outcome", result.outcome()));
                M8CapacityObservation.CURRENT.remove();
            }
            long before = knowledgeSql();
            long started = System.nanoTime();
            var response = rpc("tools/call",
                Map.of("name", "knowledge.search", "arguments", Map.of("query", "guide", "limit", 5)), 200);
            long ended = System.nanoTime();
            var result = classify(response, true);
            var search = JSON.readTree(response.body()).path("result").path("structuredContent");
            queryCosts.add(Map.of("operation", "knowledge", "size", size, "repeat", i,
                "elapsedMs", (ended - started) / 1e6, "knowledgeSql", knowledgeSql() - before,
                "responseBytes", result.bytes(), "outcome", result.outcome(),
                "category", search.path("category").asString(), "hits", search.path("results").size()));
        }
    }

    private HttpResponse<String> rpc(String method, Object params, Integer id) throws Exception {
        var body = new LinkedHashMap<String, Object>(Map.of("jsonrpc", "2.0", "method", method, "params", params));
        if (id != null) body.put("id", id);
        var builder = HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN + "/mcp"))
            .header("Authorization", "Bearer " + f.owner.delegation().value()).header("Mcp-Session-Id", session)
            .header("MCP-Protocol-Version", "2025-11-25").header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream").timeout(Duration.ofSeconds(8));
        var trace = M8CapacityObservation.CURRENT.get();
        if (trace != null) builder.header("X-Capacity-Id", trace.id);
        var response = f.http.send(builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        if (trace != null) {
            trace.status = response.statusCode();
            try {
                var result = JSON.readTree(response.body()).path("result");
                trace.wireRequestId = result.path("_meta").path("requestId").asString();
                if (trace.wireRequestId.isEmpty()) trace.wireRequestId = result.path("structuredContent").path("requestId").asString();
            } catch (RuntimeException malformed) { trace.wireRequestId = ""; }
        }
        return response;
    }

    private long knowledgeSql() {
        // No other Knowledge requests run during this phase; background tasks use different tables.
        return observer.queryForObject("SELECT COALESCE(SUM(COUNT_STAR),0) FROM performance_schema.events_statements_summary_by_digest "
            + "WHERE SCHEMA_NAME='m8_capacity' AND DIGEST_TEXT LIKE 'SELECT%knowledge_%'", Long.class);
    }

    private void sample() {
        var sample = new LinkedHashMap<String, Object>();
        sample.put("phase", phase); sample.put("nanos", System.nanoTime());
        var mx = pool.getHikariPoolMXBean();
        sample.put("active", mx.getActiveConnections()); sample.put("idle", mx.getIdleConnections());
        sample.put("pending", mx.getThreadsAwaitingConnection()); sample.put("inFlight", inFlight.get());
        sample.put("mcpWorkers", f.engine.activeWorkers()); sample.put("runtimeWorkers", runtime.activeWorkers());
        sample.put("observationWorker", observationActive.get());
        sample.put("mcpPermits", ((Number) M8CapacityObservation.permits(context.getBean(LocalAdmission.class)).get("logical")).intValue());
        sample.put("runtimePermits", M8CapacityObservation.runtimePermits(runtime));
        @SuppressWarnings("unchecked") var servlet = (org.springframework.boot.web.servlet.ServletRegistrationBean<com.slotq.mcp.web.McpServlet>) context.getBean("mcpServlet");
        sample.put("mcpIngress", M8CapacityObservation.ingress(servlet.getServlet(), 8));
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        sample.put("processCpuLoad", os.getProcessCpuLoad()); sample.put("systemCpuLoad", os.getCpuLoad());
        sample.put("heapBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        sample.put("committedVirtualBytes", os.getCommittedVirtualMemorySize());
        sample.put("hostFreeRamBytes", os.getFreeMemorySize());
        sample.put("httpThreads", httpThreads());
        var resource = resources.latest();
        long age = resource.get("endedNanos") instanceof Number number ? System.nanoTime() - number.longValue() : Long.MAX_VALUE;
        sample.put("resourceAgeMs", age / 1e6);
        if ("OBSERVED".equals(resource.get("status")) && age <= TimeUnit.SECONDS.toNanos(1)) {
            for (String key : List.of("lockWaits", "backlog", "unmaterialized")) sample.put(key, resource.get(key));
            sample.put("databaseObservation", "OBSERVED");
        } else sample.put("databaseObservation", "UNAVAILABLE");
        var meters = new ArrayList<Map<String, Object>>();
        context.getBean(MeterRegistry.class).getMeters().stream().filter(m -> m.getId().getName().startsWith("hikaricp.")
            || m.getId().getName().startsWith("tomcat.threads") || m.getId().getName().startsWith("slotq.maintenance")
            || m.getId().getName().startsWith("slotq.delivery")).forEach(m -> {
                var values = new TreeMap<String, Double>();
                m.measure().forEach(v -> { if (Double.isFinite(v.getValue())) values.put(v.getStatistic().toString(), v.getValue()); });
                meters.add(Map.of("name", m.getId().getName(), "tags", m.getId().getTags().toString(), "values", values));
            });
        sample.put("meters", meters);
        sample.put("endedNanos", System.nanoTime());
        samples.add(sample);
    }

    private void quietRecovery() {
        phase = "burst-quiet";
        M8CapacityObservation.phase = phase;
        var burst = phases.stream().filter(p -> p.get("name").equals("burst")).findFirst().orElseThrow();
        long end = ((Number) burst.get("startNanos")).longValue() + TimeUnit.SECONDS.toNanos(((Number) burst.get("seconds")).longValue());
        long until = end + TimeUnit.SECONDS.toNanos(16);
        while (System.nanoTime() < until) LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(50), until - System.nanoTime()));
    }

    private void quietFor(Duration duration) {
        long until = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < until) LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(50), until - System.nanoTime()));
    }

    private String calibrationOrigin;
    private void calibrate() throws Exception {
        var store = java.security.KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(McpHttpIntegrationTests.KEYSTORE)) { store.load(input, McpHttpIntegrationTests.PASSWORD.toCharArray()); }
        var managers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, McpHttpIntegrationTests.PASSWORD.toCharArray());
        var tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        var echo = com.sun.net.httpserver.HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 64);
        echo.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls));
        var received = new AtomicInteger();
        echo.createContext("/echo", exchange -> {
            received.incrementAndGet();
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            echo.setExecutor(executor);
            echo.start();
            calibrationOrigin = "https://localhost:" + echo.getAddress().getPort() + "/echo";
            for (int i = 0; i < 10; i++) http(HttpRequest.newBuilder(URI.create(calibrationOrigin)).GET().build());
            received.set(0);
            load("generator-calibration", 320, 3, false);
            phases.getLast().put("echoReceived", received.get());
        } finally { echo.stop(0); calibrationOrigin = null; }
    }

    private static List<Map<String, Object>> httpThreads() {
        var result = new ArrayList<Map<String, Object>>();
        try {
            var server = ManagementFactory.getPlatformMBeanServer();
            for (var object : server.queryNames(new ObjectName("*:type=ThreadPool,*"), null)) {
                var row = new TreeMap<String, Object>();
                for (String name : List.of("maxThreads", "currentThreadCount", "currentThreadsBusy", "connectionCount")) {
                    try { row.put(name, server.getAttribute(object, name)); }
                    catch (Exception unavailable) { row.put(name, "UNAVAILABLE"); }
                }
                result.add(row);
            }
        } catch (Exception unavailable) { return List.of(); }
        return result;
    }

    private void observeEvents() {
        observationActive.incrementAndGet();
        try { observationWorker.runCycle(); }
        catch (RuntimeException failure) { observationFailures.incrementAndGet(); }
        finally { observationActive.decrementAndGet(); }
    }

    private Map<String, Object> oracle() {
        var result = new LinkedHashMap<String, Object>();
        // Fresh physical root connections; this observer never consumes the Product Hikari pool.
        var checks = Map.of(
            "occupancy", "SELECT COUNT(*) FROM (SELECT a.slot_inventory_id FROM capacity_allocations a JOIN reservations r ON r.id=a.reservation_id WHERE a.active=TRUE AND (r.state IN ('CONFIRMED','CHECKED_IN') OR (r.state='HELD' AND r.expires_at>UTC_TIMESTAMP(6))) GROUP BY a.slot_inventory_id HAVING SUM(a.units)>1) x",
            "idempotency", "SELECT COUNT(*) FROM (SELECT reservation_id FROM hold_idempotency_records WHERE reservation_id IS NOT NULL GROUP BY reservation_id HAVING COUNT(*)>1) x",
            "receipt", "SELECT COUNT(*) FROM waitlist_promotion_receipts WHERE outcome IS NULL",
            "retry", "SELECT COUNT(*) FROM event_deliveries WHERE cycle_attempts>5",
            "doneWithoutReceipt", "SELECT COUNT(*) FROM event_deliveries d JOIN event_registrations g ON g.registration_id=d.registration_id LEFT JOIN waitlist_promotion_receipts r ON r.event_id=d.event_id AND r.consumer_id=g.consumer_id WHERE g.consumer_id='waitlist.promotion' AND d.state='DONE' AND r.event_id IS NULL",
            "bindingMismatch", "SELECT COUNT(*) FROM hold_idempotency_records k LEFT JOIN reservations r ON r.id=k.reservation_id WHERE k.state<>'COMPLETED' OR r.id IS NULL OR k.tenant_id<>r.tenant_id OR k.venue_id<>r.venue_id OR k.slot_inventory_id<>r.slot_inventory_id OR k.customer_principal_id<>r.customer_principal_id OR k.party_size<>r.party_size");
        long violations = 0;
        for (var check : checks.entrySet()) {
            long count = observer.queryForObject(check.getValue(), Long.class);
            result.put(check.getKey(), count); violations += count;
        }
        for (String table : List.of("venues", "slot_inventories", "reservations", "capacity_allocations", "hold_idempotency_records",
                "waitlist_entries", "waitlist_offers", "event_records", "event_deliveries", "waitlist_promotion_receipts")) {
            result.put(table, observer.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        result.put("deliveryStates", observer.queryForList("SELECT state,COUNT(*) count FROM event_deliveries GROUP BY state"));
        var bindings = observer.queryForList("SELECT SHA2(idempotency_key,256) key_digest,HEX(tenant_id) tenant,HEX(customer_principal_id) customer,HEX(venue_id) venue,HEX(slot_inventory_id) slot_id,HEX(reservation_id) reservation_id,state FROM hold_idempotency_records ORDER BY key_digest");
        result.put("idempotencyBindings", bindings);
        int checked = 0;
        for (var request : requests) {
            if (!request.get("operation").equals("product-hold") || !request.get("outcome").equals("SUCCESS")) continue;
            checked++;
            @SuppressWarnings("unchecked") var material = (Map<String, Object>) request.get("material");
            var found = bindings.stream().filter(b -> b.get("key_digest").equals(material.get("keyDigest"))).toList();
            boolean valid = found.size() == 1;
            if (valid) {
                var b = found.getFirst();
                for (String key : List.of("tenant", "customer", "venue"))
                    valid &= b.get(key).toString().equalsIgnoreCase(material.get(key).toString().replace("-", ""));
                valid &= b.get("slot_id").toString().equalsIgnoreCase(material.get("slot").toString().replace("-", ""));
                valid &= b.get("reservation_id").toString().equalsIgnoreCase(request.get("knownTarget").toString().replace("-", ""));
            }
            if (!valid) violations++;
        }
        result.put("successfulHoldBindingsChecked", checked);
        result.put("independentPhysicalMySql", true);
        result.put("waitlistSnapshot", new WaitlistRecoveryDatabase(observer).snapshot(f.tenant, Instant.now()));
        result.put("violations", violations);
        return result;
    }

    private Map<String, Object> manifest(MySQLContainer mysql, String stage, int size, Map<String, Object> criteria) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "slotq-m8-capacity/v2"); result.put("kind", "EXECUTION"); result.put("measured", true);
        result.put("stage", stage); result.put("startedAt", Instant.now().toString());
        result.put("revision", git("rev-parse", "HEAD")); result.put("dirty", !git("status", "--porcelain").isBlank());
        var hashes = new TreeMap<String, String>();
        try (var paths = Files.walk(Path.of("src"))) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) hashes.put(path.toString(), digest(path));
        }
        hashes.put("build.gradle", digest(Path.of("build.gradle"))); result.put("sourceDigests", hashes);
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        result.put("host", Map.of("os", System.getProperty("os.name"), "cpu", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "UNAVAILABLE"),
            "processors", os.getAvailableProcessors(), "ramBytes", os.getTotalMemorySize(), "storageFreeBytes", Files.getFileStore(Path.of(".")).getUsableSpace(),
            "network", "same host loopback HTTPS; Docker published MySQL TCP; generator and service share JVM"));
        result.put("java", System.getProperty("java.runtime.version")); result.put("jvmFlags", ManagementFactory.getRuntimeMXBean().getInputArguments());
        var docker = DockerClientFactory.instance().client();
        var inspect = docker.inspectContainerCmd(mysql.getContainerId()).exec();
        result.put("docker", Map.of("image", "mysql:8.4", "imageId", inspect.getImageId(), "cpus", docker.infoCmd().exec().getNCPU(),
            "memoryBytes", docker.infoCmd().exec().getMemTotal(), "containerMemoryLimit", inspect.getHostConfig().getMemory()));
        result.put("mysql", observer.queryForMap("SELECT VERSION() version,@@transaction_isolation isolation_level,@@innodb_buffer_pool_size buffer_pool_bytes,@@max_connections max_connections"));
        var config = new TreeMap<String, Object>();
        for (String key : List.of("server.tomcat.threads.max", "server.tomcat.accept-count", "spring.datasource.hikari.connection-timeout",
                "spring.datasource.hikari.maximum-pool-size", "spring.datasource.hikari.connection-init-sql", "spring.transaction.default-timeout",
                "slotq.mcp.workers", "slotq.mcp.ingress", "slotq.mcp.quota.rate", "slotq.mcp.quota.burst", "slotq.mcp.quota.concurrency",
                "slotq.mcp.handler-budget", "spring.task.scheduling.pool.size", "slotq.events.delivery.batch-size",
                "slotq.events.delivery.poll-interval", "slotq.events.delivery.max-attempts", "slotq.events.delivery.lease", "slotq.events.delivery.effect-timeout",
                "slotq.waitlist.promotion.maintenance-batch-size", "slotq.waitlist.promotion.discovery-batch-size")) config.put(key, context.getEnvironment().getProperty(key, "UNAVAILABLE"));
        result.put("settings", config); result.put("criteria", criteria);
        result.put("effectiveDeliveryPolicy", Map.of("maxAttempts", f.policy.maxAttempts(), "lease", f.policy.lease().toString(), "effectTimeout", f.policy.effectTimeout().toString()));
        result.put("runtime", Map.of("deadline", "PT3S", "workers", 4, "quota", "rate=200,burst=200,concurrency=4", "runRetentionBound", 4096));
        result.put("features", Map.of("devBootstrap", false, "waitlistMaintenance", true, "dbDirectDelivery", true,
            "eventObservation", true, "externalModelCalls", false, "bindAddress", "127.0.0.1", "credentialProvisioning", "in-process scoped PRODUCT credential and delegated synthetic Actors"));
        result.put("effectiveHttpThreads", httpThreads());
        result.put("data", Map.of("seed", seed, "tenants", 1, "venues", 1, "slots", size * 2, "initialReservations", size,
            "documents", size, "timezone", "UTC", "baseTime", f.clock.instant().toString(), "bindings", "Product-generated random UUIDs; seed controls target/key schedule only"));
        result.put("runId", UUID.randomUUID().toString());
        result.put("containerId", mysql.getContainerId());
        result.put("targetRate", Integer.getInteger("slotq.capacity.rate", 20));
        result.put("measurement", Map.of("generatorOutstandingLimit", 64, "queue", "virtual thread per accepted arrival; semaphore drop recorded", "sampleMillis", 250,
            "latency", "scheduled arrival -> terminal; dispatch lag and service separately", "acceptance", "Product: security-passed handler entry; MCP: registered tool handler entry; HTTP intake and terminal separately",
            "sql", "Executed JDBC statements per synchronous Product request including Auth; Hibernate separately; Knowledge MySQL digest counter in isolated query phase",
            "provider", "controlled adapter 50/250ms wait, every seventh call unavailable; zero external calls", "transport", "DB_DIRECT"));
        return result;
    }

    private static String git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-c", "safe.directory=C:/dev/slotq")); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        require(process.waitFor() == 0, "Git identity unavailable"); return output.strip();
    }

    record Observation(String outcome, boolean responded, int bytes, int sql) { }
    record Command(String operation, Callable<Observation> action, Map<String, Object> material) {
        Command(String operation, Callable<Observation> action) { this(operation, action, Map.of()); }
    }

    public static final class SqlCounter implements StatementInspector {
        static final ThreadLocal<AtomicInteger> CURRENT = new ThreadLocal<>();
        @Override public String inspect(String sql) {
            var counter = CURRENT.get(); if (counter != null) counter.incrementAndGet(); return sql;
        }
    }

    private static MethodInterceptor authTiming() {
        return invocation -> {
            if (!Set.of("requireHoldAdmission", "authenticateProduct", "authenticateMcp").contains(invocation.getMethod().getName())) {
                return invocation.proceed();
            }
            long started = System.nanoTime();
            boolean outer = TransactionSynchronizationManager.isActualTransactionActive();
            String outcome = "SUCCESS";
            String previous = M8CapacityObservation.AUTH_METHOD.get();
            M8CapacityObservation.AUTH_METHOD.set(invocation.getMethod().getName());
            try { return invocation.proceed(); }
            catch (AccessFailure failure) { outcome = failure.reason().name(); throw failure; }
            catch (Throwable failure) { outcome = "UNKNOWN"; throw failure; }
            finally {
                AUTH.add(Map.of("method", invocation.getMethod().getName(), "startedNanos", started,
                    "elapsedMs", (System.nanoTime() - started) / 1e6, "outerTransactionActive", outer, "outcome", outcome,
                    "trace", M8CapacityObservation.CURRENT.get() == null ? "async-or-background" : M8CapacityObservation.CURRENT.get().id,
                    "phase", M8CapacityObservation.phase));
                if (previous == null) M8CapacityObservation.AUTH_METHOD.remove();
                else M8CapacityObservation.AUTH_METHOD.set(previous);
            }
        };
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean @ConfigurationProperties("spring.datasource.hikari")
        M8CapacityObservation.DataSource dataSource(DataSourceProperties properties) {
            return properties.initializeDataSourceBuilder().type(M8CapacityObservation.DataSource.class).build();
        }
        @Bean static BeanPostProcessor capacityAuthTiming() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof McpRegistrations registrations) return (McpRegistrations) () -> M8CapacityObservation.tools(registrations);
                    if (bean instanceof SlotInventoryRepository repository) return M8CapacityProbes.slotProxy(repository);
                    if (bean instanceof EventAppendService append) return M8CapacityProbes.appendProxy(append);
                    if (bean instanceof com.slotq.integration.waitlist.BookingCapacityReleasedHandler handler) return M8CapacityProbes.handlerProxy(handler);
                    if (!(bean instanceof ActorAccessService)) return bean;
                    // ActorAccessService owns programmatic REQUIRES_NEW boundaries; keep the target unchanged.
                    var proxy = new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice(authTiming());
                    return proxy.getProxy();
                }
            };
        }
        @Bean @Primary RepresentativeFlowIntegrationTests.FlowClock capacityClock() {
            return new RepresentativeFlowIntegrationTests.FlowClock() {
                @Override public Instant instant() { return Instant.now(); }
            };
        }
        @Bean SSLContext capacityTrust() throws Exception { return McpHttpIntegrationTests.tls(); }
        @Bean ProductToolsIntegrationTests.ResponseFault responseFault() { return new ProductToolsIntegrationTests.ResponseFault(); }
        @Bean FilterRegistrationBean<Filter> capacityIntakeFilter() {
            Filter filter = (request, response, chain) -> {
                var incoming = (HttpServletRequest) request;
                var outgoing = (HttpServletResponse) response;
                String supplied = incoming.getHeader("X-Capacity-Id");
                var trace = new M8CapacityObservation.Trace(supplied == null ? UUID.randomUUID().toString() : supplied);
                var row = new ConcurrentHashMap<String, Object>();
                row.put("trace", trace.id);
                row.put("phase", M8CapacityObservation.phase);
                row.put("path", incoming.getRequestURI());
                row.put("receivedNanos", System.nanoTime());
                row.put("external", supplied != null);
                row.put("authenticatedHandler", false);
                M8CapacityObservation.CURRENT.set(trace);
                incoming.setAttribute("capacity.trace", trace);
                incoming.setAttribute("capacity.row", row);
                var completed = new java.util.concurrent.atomic.AtomicBoolean();
                Runnable finish = () -> {
                    if (!completed.compareAndSet(false, true)) return;
                    row.put("completedNanos", System.nanoTime());
                    row.put("status", outgoing.getStatus());
                    row.put("serverRequestId", Objects.toString(outgoing.getHeader("X-Request-ID"), ""));
                    row.put("jdbcExecutions", trace.sql.get());
                    M8CapacityObservation.HTTP.add(Map.copyOf(row));
                };
                try {
                    chain.doFilter(request, response);
                    if (incoming.isAsyncStarted()) {
                        try { incoming.getAsyncContext().addListener(new AsyncListener() {
                            public void onComplete(AsyncEvent event) { finish.run(); }
                            public void onTimeout(AsyncEvent event) { row.put("asyncTimeout", true); }
                            public void onError(AsyncEvent event) { row.put("asyncError", true); }
                            public void onStartAsync(AsyncEvent event) { }
                        }); } catch (IllegalStateException alreadyComplete) { finish.run(); }
                    } else finish.run();
                } finally { M8CapacityObservation.CURRENT.remove(); }
            };
            var bean = new FilterRegistrationBean<>(filter);
            bean.setOrder(-200);
            bean.setAsyncSupported(true);
            return bean;
        }
        @Bean FilterRegistrationBean<Filter> capacitySqlFilter() {
            Filter filter = (request, response, chain) -> {
                var wrapped = new ContentCachingResponseWrapper((HttpServletResponse) response);
                @SuppressWarnings("unchecked") var row = (Map<String, Object>) request.getAttribute("capacity.row");
                if (row != null) { row.put("authenticatedHandler", true); row.put("handlerEnteredNanos", System.nanoTime()); }
                SqlCounter.CURRENT.set(new AtomicInteger());
                try { chain.doFilter(request, wrapped); }
                finally {
                    wrapped.setHeader("X-Capacity-Hibernate-Sql", String.valueOf(SqlCounter.CURRENT.get().get()));
                    var trace = (M8CapacityObservation.Trace) request.getAttribute("capacity.trace");
                    wrapped.setHeader("X-Capacity-Jdbc-Sql", String.valueOf(trace == null ? -1 : trace.sql.get()));
                    if ("true".equals(((HttpServletRequest) request).getHeader("X-Capacity-Drop")) && wrapped.getStatus() == 201) {
                        if (row != null) row.put("responseDroppedAfterCommit", true);
                        wrapped.resetBuffer();
                    }
                    SqlCounter.CURRENT.remove(); wrapped.copyBodyToResponse();
                }
            };
            var bean = new FilterRegistrationBean<>(filter);
            bean.addUrlPatterns("/api/*");
            bean.setOrder(0);
            return bean;
        }
    }
}
