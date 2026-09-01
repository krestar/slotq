package com.slotq.experiments.concurrency;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import com.slotq.SlotqApplication;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.WeeklyOperatingHours;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ConcurrencyBaselineRunner {

    private static final String DATABASE_IMAGE = "mysql:8.4";
    private static final int SLOT_CAPACITY = 1;
    private static final int ALLOCATION_UNIT = 1;

    private ConcurrencyBaselineRunner() {
    }

    public static void main(String[] args) throws Exception {
        ConcurrencyBaselineConfig config = ConcurrencyBaselineConfig.fromSystemProperties();
        try (MySQLContainer mysql = new MySQLContainer(DockerImageName.parse(DATABASE_IMAGE))
            .withDatabaseName("slotq")) {
            mysql.start();
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SlotqApplication.class)
                .run(
                    "--server.port=0",
                    "--spring.profiles.active=local",
                    "--spring.datasource.url=" + mysql.getJdbcUrl(),
                    "--spring.datasource.username=" + mysql.getUsername(),
                    "--spring.datasource.password=" + mysql.getPassword(),
                    "--slotq.auth.dev-bootstrap-enabled=true"
                )) {
                int port = Integer.parseInt(context.getEnvironment()
                    .getRequiredProperty("local.server.port"));
                BaselineReport report = execute(
                    context, URI.create("http://127.0.0.1:" + port), config
                );
                writeReport(context.getBean(ObjectMapper.class), config.output(), report);
            }
        }
    }

    static BaselineReport execute(
        ConfigurableApplicationContext context,
        URI baseUri,
        ConcurrencyBaselineConfig config
    ) throws Exception {
        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(config.timeout())
            .build();
        List<String> accessTokens = List.of(
            bootstrap(httpClient, objectMapper, baseUri, "customer-a", config.timeout()),
            bootstrap(httpClient, objectMapper, baseUri, "customer-b", config.timeout())
        );
        List<Fixture> fixtures = seedFixtures(context, config);
        JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
        DatabaseCounters countersBefore = databaseCounters(jdbcTemplate);
        SplittableRandom random = new SplittableRandom(config.seed());
        List<RequestResult> requests = new ArrayList<>();
        List<Long> iterationElapsedNanos = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(config.clients())) {
            for (int iteration = 0; iteration < config.iterations(); iteration++) {
                int iterationIndex = iteration;
                Fixture fixture = fixtures.get(iteration);
                CyclicBarrier startBarrier = new CyclicBarrier(config.clients() + 1);
                List<Future<RequestResult>> futures = new ArrayList<>();
                for (int client = 0; client < config.clients(); client++) {
                    int clientIndex = client;
                    String accessToken = accessTokens.get(random.nextInt(accessTokens.size()));
                    futures.add(executor.submit(() -> requestHold(
                        httpClient, objectMapper, baseUri, config, fixture,
                        iterationIndex, clientIndex, accessToken, startBarrier
                    )));
                }
                awaitBarrier(startBarrier, config.timeout());
                List<RequestResult> iterationResults = new ArrayList<>();
                for (Future<RequestResult> future : futures) {
                    iterationResults.add(future.get(
                        Math.max(1L, config.timeout().multipliedBy(2).toMillis()),
                        TimeUnit.MILLISECONDS
                    ));
                }
                requests.addAll(iterationResults);
                long firstStart = iterationResults.stream().mapToLong(RequestResult::startedNanos).min()
                    .orElseThrow();
                long lastEnd = iterationResults.stream().mapToLong(RequestResult::endedNanos).max()
                    .orElseThrow();
                iterationElapsedNanos.add(lastEnd - firstStart);
            }
        }

        Instant verificationNow = Instant.now();
        Verification verification = verify(
            jdbcTemplate, fixtures, verificationNow
        );
        DatabaseCounters countersAfter = databaseCounters(jdbcTemplate);
        requests.sort(Comparator.comparingInt(RequestResult::iteration)
            .thenComparingInt(RequestResult::client));
        Environment environment = environment(context);
        Metrics metrics = metrics(
            requests, iterationElapsedNanos, verification,
            countersAfter.minus(countersBefore)
        );
        return new BaselineReport(
            "slotq-concurrency-baseline/v2",
            UUID.randomUUID().toString(),
            Instant.now(),
            environment,
            new Workload(
                config.clients(), config.iterations(), config.seed(), config.partySize(),
                config.holdDuration().toString(), config.timeout().toString()
            ),
            new ProductModel("TABLE_X_SLOT", SLOT_CAPACITY, ALLOCATION_UNIT, config.strategy(),
                "partySize is Resource seatingCapacity eligibility only"),
            verificationNow,
            metrics,
            verification.slots(),
            requests
        );
    }

    private static List<Fixture> seedFixtures(
        ConfigurableApplicationContext context,
        ConcurrencyBaselineConfig config
    ) {
        TenantUseCase tenants = context.getBean(TenantUseCase.class);
        VenueConfigurationUseCase venues = context.getBean(VenueConfigurationUseCase.class);
        ResourceUseCase resources = context.getBean(ResourceUseCase.class);
        SlotInventoryUseCase slots = context.getBean(SlotInventoryUseCase.class);

        Tenant tenant = tenants.createTenant();
        EnumMap<DayOfWeek, DailyOperatingHours> openDays = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            openDays.put(day, new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(18, 0)));
        }
        Venue venue = venues.createVenue(new VenueConfigurationUseCase.CreateVenue(
            tenant.id(), "Concurrency baseline " + config.seed(), "UTC",
            new WeeklyOperatingHours(openDays),
            new BookingPolicyTerms(30, config.holdDurationMinutes(), 0, 0)
        ));
        long leadDays = Math.max(1L, config.holdDuration().toDays() + 2L);
        Instant startsAt = LocalDate.now(ZoneOffset.UTC).plusDays(leadDays)
            .atTime(12, 0).toInstant(ZoneOffset.UTC);
        List<Fixture> fixtures = new ArrayList<>();
        for (int iteration = 0; iteration < config.iterations(); iteration++) {
            Resource table = resources.createResource(new ResourceUseCase.CreateResource(
                tenant.id(), venue.id(), "Baseline table " + config.seed() + "-" + iteration,
                config.partySize()
            ));
            SlotInventory slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(
                tenant.id(), venue.id(), table.id(), startsAt.toString()
            ));
            if (slot.capacity() != SLOT_CAPACITY) {
                throw new IllegalStateException("Product Slot capacity must be 1");
            }
            fixtures.add(new Fixture(venue, table, slot));
        }
        return List.copyOf(fixtures);
    }

    private static RequestResult requestHold(
        HttpClient client,
        ObjectMapper objectMapper,
        URI baseUri,
        ConcurrencyBaselineConfig config,
        Fixture fixture,
        int iteration,
        int clientIndex,
        String accessToken,
        CyclicBarrier startBarrier
    ) {
        long started = 0;
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "slotInventoryId", fixture.slot().id().value(),
                "partySize", config.partySize()
            ));
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(
                    "/api/v1/venues/" + fixture.venue().id().value() + "/reservations/holds"
                ))
                .timeout(config.timeout())
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            awaitBarrier(startBarrier, config.timeout());
            started = System.nanoTime();
            HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            long ended = System.nanoTime();
            String businessCode = problemCode(objectMapper, response.body());
            Outcome outcome = response.statusCode() == 201
                ? Outcome.SUCCESS
                : response.statusCode() == 409 ? Outcome.BUSINESS_CONFLICT : Outcome.SYSTEM_FAILURE;
            return new RequestResult(
                iteration, clientIndex, outcome, response.statusCode(), businessCode,
                elapsedMillis(started, ended), started, ended
            );
        } catch (HttpTimeoutException exception) {
            long ended = System.nanoTime();
            return new RequestResult(
                iteration, clientIndex, Outcome.TIMEOUT, null, exception.getClass().getSimpleName(),
                elapsedMillis(started, ended), started, ended
            );
        } catch (Exception exception) {
            long ended = System.nanoTime();
            return new RequestResult(
                iteration, clientIndex, Outcome.SYSTEM_FAILURE, null,
                exception.getClass().getSimpleName(), elapsedMillis(started, ended), started, ended
            );
        }
    }

    private static Verification verify(
        JdbcTemplate jdbcTemplate,
        List<Fixture> fixtures,
        Instant verificationNow
    ) {
        List<SlotObservation> observations = new ArrayList<>();
        int effectiveTotal = 0;
        int rawActiveTotal = 0;
        int violations = 0;
        int partialCommits = 0;
        Timestamp capturedTimestamp = Timestamp.from(verificationNow);
        for (int iteration = 0; iteration < fixtures.size(); iteration++) {
            Fixture fixture = fixtures.get(iteration);
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT
                    COALESCE(SUM(CASE
                        WHEN allocation.active = TRUE
                         AND (reservation.state IN ('CONFIRMED', 'CHECKED_IN')
                              OR (reservation.state = 'HELD' AND reservation.expires_at > ?))
                        THEN allocation.units ELSE 0 END), 0) AS effective_occupancy,
                    COALESCE(SUM(CASE WHEN allocation.active = TRUE THEN 1 ELSE 0 END), 0)
                        AS raw_active_allocation_rows,
                    COUNT(DISTINCT reservation.id) AS reservation_rows,
                    COUNT(allocation.id) AS allocation_rows
                  FROM reservations reservation
                  LEFT JOIN capacity_allocations allocation
                    ON allocation.reservation_id = reservation.id
                 WHERE reservation.tenant_id = ?
                   AND reservation.venue_id = ?
                   AND reservation.resource_id = ?
                   AND reservation.slot_inventory_id = ?
                """,
                capturedTimestamp,
                bytes(fixture.slot().tenantId().value()),
                bytes(fixture.venue().id().value()),
                bytes(fixture.table().id().value()),
                bytes(fixture.slot().id().value())
            );
            int effective = ((Number) row.get("effective_occupancy")).intValue();
            int rawActive = ((Number) row.get("raw_active_allocation_rows")).intValue();
            int reservationRows = ((Number) row.get("reservation_rows")).intValue();
            int allocationRows = ((Number) row.get("allocation_rows")).intValue();
            boolean violation = effective > SLOT_CAPACITY;
            boolean partialCommit = reservationRows != allocationRows;
            effectiveTotal += effective;
            rawActiveTotal += rawActive;
            violations += violation ? 1 : 0;
            partialCommits += partialCommit ? 1 : 0;
            observations.add(new SlotObservation(
                iteration, fixture.slot().id().value(), verificationNow,
                effective, rawActive, reservationRows, allocationRows, violation, partialCommit
            ));
        }
        return new Verification(
            List.copyOf(observations), effectiveTotal, rawActiveTotal, violations, partialCommits
        );
    }

    private static Metrics metrics(
        List<RequestResult> requests,
        List<Long> iterationElapsedNanos,
        Verification verification,
        DatabaseCounters databaseCounters
    ) {
        List<Double> latencies = requests.stream()
            .map(RequestResult::latencyMs)
            .sorted()
            .toList();
        long elapsedNanos = iterationElapsedNanos.stream().mapToLong(Long::longValue).sum();
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        long successful = requests.stream().filter(result -> result.outcome() == Outcome.SUCCESS).count();
        long conflicts = requests.stream()
            .filter(result -> result.outcome() == Outcome.BUSINESS_CONFLICT).count();
        long timeouts = requests.stream().filter(result -> result.outcome() == Outcome.TIMEOUT).count();
        long failures = requests.stream()
            .filter(result -> result.outcome() == Outcome.SYSTEM_FAILURE).count();
        double maxStartSpread = requests.stream()
            .collect(java.util.stream.Collectors.groupingBy(RequestResult::iteration))
            .values().stream()
            .mapToDouble(batch -> {
                long first = batch.stream().mapToLong(RequestResult::startedNanos).min().orElse(0L);
                long last = batch.stream().mapToLong(RequestResult::startedNanos).max().orElse(0L);
                return elapsedMillis(first, last);
            }).max().orElse(0.0);
        return new Metrics(
            requests.size(), elapsedNanos / 1_000_000.0,
            elapsedSeconds == 0.0 ? 0.0 : requests.size() / elapsedSeconds,
            percentile(latencies, 0.50), percentile(latencies, 0.95), percentile(latencies, 0.99),
            conflicts, failures, timeouts, successful,
            verification.invariantViolations(), verification.effectiveOccupancy(),
            verification.rawActiveAllocationRows(), verification.partialCommits(), maxStartSpread,
            0L, 0L,
            0L, 0L,
            databaseCounters.lockWaits(), databaseCounters.lockWaitTimeMs(),
            databaseCounters.deadlocks()
        );
    }

    private static DatabaseCounters databaseCounters(JdbcTemplate jdbcTemplate) {
        Map<String, Long> values = new HashMap<>();
        jdbcTemplate.queryForList("""
            SHOW GLOBAL STATUS
            WHERE Variable_name IN ('Innodb_row_lock_waits', 'Innodb_row_lock_time', 'Innodb_deadlocks')
            """).forEach(row -> values.put(
                row.get("Variable_name").toString(),
                Long.parseLong(row.get("Value").toString())
            ));
        return new DatabaseCounters(
            values.getOrDefault("Innodb_row_lock_waits", 0L),
            values.getOrDefault("Innodb_row_lock_time", 0L),
            values.getOrDefault("Innodb_deadlocks", 0L)
        );
    }

    private static Environment environment(ConfigurableApplicationContext context) {
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        DataSource dataSource = context.getBean(DataSource.class);
        Map<String, Object> pool = new HashMap<>();
        pool.put("implementation", dataSource.getClass().getName());
        if (dataSource instanceof HikariDataSource hikari) {
            pool.put("maximumPoolSize", hikari.getMaximumPoolSize());
            pool.put("connectionTimeoutMs", hikari.getConnectionTimeout());
        }
        return new Environment(
            git("rev-parse", "HEAD"), git("branch", "--show-current"),
            !git("status", "--porcelain").isBlank(),
            DATABASE_IMAGE, jdbc.queryForObject("SELECT VERSION()", String.class),
            jdbc.queryForObject("SELECT @@transaction_isolation", String.class),
            jdbc.queryForObject("""
                SELECT version
                  FROM flyway_schema_history
                 WHERE success = TRUE
                 ORDER BY installed_rank DESC
                 LIMIT 1
                """, String.class),
            Map.copyOf(pool),
            System.getProperty("java.version"), SpringBootVersion.getVersion(),
            System.getProperty("slotq.baseline.gradleVersion", "unknown"),
            System.getProperty("os.name") + " " + System.getProperty("os.version"),
            System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors()
        );
    }

    private static String bootstrap(
        HttpClient client,
        ObjectMapper objectMapper,
        URI baseUri,
        String fixtureKey,
        Duration timeout
    ) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/__dev/auth/session"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(Map.of("fixtureKey", fixtureKey))
            ))
            .build();
        HttpResponse<String> response = client.send(
            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Dev auth bootstrap failed with HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body()).path("accessToken").asText();
    }

    private static String problemCode(ObjectMapper objectMapper, String body) {
        try {
            JsonNode code = objectMapper.readTree(body).path("code");
            return code.isMissingNode() || code.isNull() ? null : code.asText();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier, Duration timeout)
        throws InterruptedException, BrokenBarrierException, TimeoutException {
        barrier.await(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
    }

    private static double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, index));
    }

    private static double elapsedMillis(long started, long ended) {
        if (started <= 0 || ended < started) {
            return 0.0;
        }
        return (ended - started) / 1_000_000.0;
    }

    private static void writeReport(ObjectMapper source, Path output, BaselineReport report)
        throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        source.writeValue(absolute.toFile(), report);
        System.out.println(source.writeValueAsString(report));
        System.out.println("Baseline result written to " + absolute);
    }

    private static String git(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
                ? output : "unknown";
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "unknown";
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
            .putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits())
            .array();
    }

    private record Fixture(Venue venue, Resource table, SlotInventory slot) {
    }

    enum Outcome {
        SUCCESS,
        BUSINESS_CONFLICT,
        SYSTEM_FAILURE,
        TIMEOUT
    }

    record BaselineReport(
        String schemaVersion,
        String runId,
        Instant recordedAt,
        Environment environment,
        Workload workload,
        ProductModel productModel,
        Instant verificationNow,
        Metrics metrics,
        List<SlotObservation> slotObservations,
        List<RequestResult> requests
    ) {
    }

    record Environment(
        String applicationRevision,
        String branch,
        boolean dirty,
        String databaseImage,
        String databaseVersion,
        String transactionIsolation,
        String schemaVersion,
        Map<String, Object> connectionPool,
        String javaVersion,
        String springBootVersion,
        String gradleVersion,
        String operatingSystem,
        String architecture,
        int availableProcessors
    ) {
    }

    record Workload(
        int clients,
        int iterations,
        long seed,
        int partySize,
        String holdDuration,
        String timeout
    ) {
    }

    record ProductModel(
        String resourceModel,
        int slotCapacity,
        int allocationUnit,
        String concurrencyStrategy,
        String partySizeRole
    ) {
    }

    record Metrics(
        int totalRequests,
        double elapsedMs,
        double throughputRequestsPerSecond,
        double p50LatencyMs,
        double p95LatencyMs,
        double p99LatencyMs,
        long businessConflictCount,
        long systemFailureCount,
        long timeoutCount,
        long successfulHoldResultCount,
        int invariantViolationCount,
        int effectiveOccupancy,
        int rawActiveAllocationRows,
        int partialCommitCount,
        double maxBarrierReleaseStartSpreadMs,
        long staleRetryCount,
        long staleRetryExhaustionCount,
        long systemRetryCount,
        long systemRetryExhaustionCount,
        long lockWaitCount,
        long lockWaitTimeMs,
        long deadlockCount
    ) {
    }

    record SlotObservation(
        int iteration,
        UUID slotInventoryId,
        Instant verificationNow,
        int effectiveOccupancy,
        int rawActiveAllocationRows,
        int reservationRows,
        int allocationRows,
        boolean invariantViolation,
        boolean partialCommit
    ) {
    }

    record RequestResult(
        int iteration,
        int client,
        Outcome outcome,
        Integer httpStatus,
        String businessCode,
        double latencyMs,
        long startedNanos,
        long endedNanos
    ) {
    }

    private record Verification(
        List<SlotObservation> slots,
        int effectiveOccupancy,
        int rawActiveAllocationRows,
        int invariantViolations,
        int partialCommits
    ) {
    }

    private record DatabaseCounters(long lockWaits, long lockWaitTimeMs, long deadlocks) {
        DatabaseCounters minus(DatabaseCounters before) {
            return new DatabaseCounters(
                Math.max(0L, lockWaits - before.lockWaits),
                Math.max(0L, lockWaitTimeMs - before.lockWaitTimeMs),
                Math.max(0L, deadlocks - before.deadlocks)
            );
        }
    }
}
