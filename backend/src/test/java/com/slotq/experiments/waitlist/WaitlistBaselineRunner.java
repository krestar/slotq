package com.slotq.experiments.waitlist;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import com.slotq.SlotqApplication;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.*;
import com.slotq.booking.application.*;
import com.slotq.booking.domain.*;
import com.slotq.events.application.*;
import com.slotq.integration.waitlist.*;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import com.slotq.waitlist.application.*;
import com.slotq.waitlist.domain.*;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static com.slotq.experiments.waitlist.WaitlistRecoveryDatabase.*;

/** Issue #105 only: real public use cases, production routes and one synchronous DB executor. */
public final class WaitlistBaselineRunner {
    static final int BATCH = 2, GROUPS = 6, CLIENTS = 4;
    static final Instant BASE = Instant.parse("2026-08-30T09:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();
    private final ConfigurableApplicationContext context;
    private final WaitlistProcessRecoveryRunner.MutableClock clock;
    private final SplittableRandom random;
    private final List<Map<String,Object>> commands = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String,Object>> cycles = new ArrayList<>(), observations = new ArrayList<>();
    private final List<Fixture> fixtures = new ArrayList<>();
    private final JdbcTemplate jdbc;
    private final EventDeliveryWorker worker;
    private String phase;

    private WaitlistBaselineRunner(ConfigurableApplicationContext context, long seed) {
        this.context = context;
        clock = (WaitlistProcessRecoveryRunner.MutableClock) context.getBean("baselineClock");
        random = new SplittableRandom(seed);
        jdbc = context.getBean(JdbcTemplate.class);
        worker = context.getBean(EventDeliveryWorker.class);
    }

    public static void main(String[] args) throws Exception {
        String recalculate = System.getProperty("slotq.waitlist.baseline.recalculate");
        if (recalculate != null) {
            Path folder = Path.of(recalculate).toAbsolutePath();
            Map<String,Object> raw = JSON.readValue(Files.readString(folder.resolve("raw.json")), new TypeReference<>() {});
            Map<String,Object> summary = WaitlistBaselineEvidence.summarize(raw);
            var saved = JSON.readTree(Files.readString(folder.resolve("summary.json")));
            require(saved.equals(JSON.readTree(JSON.writeValueAsString(summary))), "saved summary differs from raw recalculation");
            Path csv = Files.createTempFile("waitlist-baseline-recalculated-", ".csv");
            try {
                WaitlistBaselineEvidence.writeCsv(csv, raw);
                require(Files.mismatch(csv, folder.resolve("correspondence.csv")) == -1, "CSV differs from raw recalculation");
            } finally { Files.deleteIfExists(csv); }
            System.out.println("Raw -> summary and correspondence CSV recalculation PASS: " + folder);
            return;
        }
        long seed = Long.parseLong(System.getProperty("slotq.waitlist.baseline.seed", "10501"));
        Path output = Path.of(System.getProperty("slotq.waitlist.baseline.output", "build/reports/experiments/waitlist-baseline/" + UUID.randomUUID())).toAbsolutePath();
        require(!Files.exists(output), "Use a fresh evidence directory: " + output);
        try (var mysql = new MySQLContainer("mysql:8.4").withDatabaseName("slotq_baseline")) {
            mysql.start();
            long startup = System.nanoTime();
            try (var context = new SpringApplicationBuilder(SlotqApplication.class, Configuration.class).run(
                "--server.port=0", "--logging.level.root=ERROR", "--spring.main.banner-mode=off",
                "--spring.datasource.url=" + mysql.getJdbcUrl(), "--spring.datasource.username=" + mysql.getUsername(),
                "--spring.datasource.password=" + mysql.getPassword(), "--spring.datasource.hikari.maximum-pool-size=10",
                "--slotq.waitlist.promotion.enabled=true", "--slotq.waitlist.promotion.maintenance-enabled=true",
                "--slotq.events.delivery.scheduler-enabled=true", "--slotq.events.delivery.batch-size=2",
                "--slotq.waitlist.promotion.discovery-batch-size=2", "--slotq.waitlist.promotion.maintenance-batch-size=2")) {
                long ready=System.nanoTime();
                var runner = new WaitlistBaselineRunner(context, seed);
                Map<String,Object> raw = runner.execute(runner.manifest(mysql, seed, startup, ready));
                Files.createDirectories(output);
                write(output.resolve("raw.json"), raw); // Preserve raw before the oracle can fail.
                write(output.resolve("manifest.json"), raw.get("manifest"));
                write(output.resolve("summary.json"), WaitlistBaselineEvidence.summarize(raw));
                WaitlistBaselineEvidence.writeCsv(output.resolve("correspondence.csv"), raw);
                System.out.println("Waitlist DB direct baseline: " + output);
            }
        }
    }

    Map<String,Object> execute(Map<String,Object> manifest) throws Exception {
        require(context.getBean(WaitlistPromotionReadiness.class).isReady(), "production activation not ready");
        require("REPEATABLE-READ".equals(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)), "isolation mismatch");
        phase = "normal";
        Fixture first = fixture(), second = fixture();
        // Eligible FIFO: an earlier large-party demand at the same time is skipped for this table.
        SlotInventory large = first.slot(8), ordinary = first.slot(4);
        Candidate ineligible = first.entry(large, 7), eligible = first.entry(ordinary, 2), next = first.entry(ordinary, 2);
        var owner = first.customer();
        var held = command("hold", () -> first.booking.createHold(new ReservationUseCase.CreateHold(first.venue.id(), ordinary.id(), owner, 2)).reservation());
        command("confirm", () -> first.booking.transition(first.venue.id(), held.id(), owner, ReservationCommand.CONFIRM));
        command("ordinary-release", () -> first.booking.transition(first.venue.id(), held.id(), owner, ReservationCommand.CANCEL));
        drain();
        require(offerEntry(first, ordinary).equals(eligible.id), "eligible FIFO skipped oldest eligible entry");
        reject(first, ordinary, eligible);
        drain();
        require(offerEntry(first, ordinary).equals(next.id), "reject did not promote next");
        accept(first, ordinary, next);
        command("entry-cancel", () -> first.waitlist.cancel(first.venue.id(), new WaitlistEntryId(ineligible.id), ineligible.customer));
        // Release-free opportunity uses real discovery/admission, followed by expiry and next.
        SlotInventory free = second.slot(4);
        Candidate expiring = second.entry(free, 2), afterExpiry = second.entry(free, 2);
        var page = command("release-free-discovery", () -> context.getBean(WaitlistPromotionDiscovery.class).discover(SystemPrincipal.INSTANCE, null));
        require(page.failures().isEmpty() && page.appended() >= 1, "release-free discovery did not append");
        drain();
        require(offerEntry(second, free).equals(expiring.id), "release-free FIFO mismatch");
        Instant deadline = offer(second, free).expiresAt();
        clock.now.set(deadline);
        var expired = command("offer-expiry", () -> context.getBean(WaitlistOfferUseCase.class).reconcileTarget(SystemPrincipal.INSTANCE, second.venue.id(), offerId(second, free)));
        require(expired.outcome() == WaitlistOfferUseCase.TargetOutcome.EXPIRED, "offer expiry failed");
        drain();
        require(offerEntry(second, free).equals(afterExpiry.id), "expiry did not promote next");
        accept(second, free, afterExpiry);
        // Legal normal no-op: no Waiting demand on a released ordinary reservation.
        SlotInventory empty = first.slot(4);
        var emptyOwner = first.customer();
        var emptyHold = command("hold", () -> first.booking.createHold(new ReservationUseCase.CreateHold(first.venue.id(), empty.id(), emptyOwner, 2)).reservation());
        command("empty-release", () -> first.booking.transition(first.venue.id(), emptyHold.id(), emptyOwner, ReservationCommand.CANCEL));
        drain();
        // A real request can become a normal no-op when capacity is taken before execution.
        SlotInventory delayed=first.slot(4);first.entry(delayed,2);request(first,delayed);
        var delayedOwner=first.customer();
        var delayedHold=command("hold",()->first.booking.createHold(new ReservationUseCase.CreateHold(first.venue.id(),delayed.id(),delayedOwner,2)).reservation());
        drain();
        require(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_promotion_receipts WHERE tenant_id=? AND slot_inventory_id=? AND outcome='NO_CAPACITY'",Integer.class,bytes(first.tenant.id().value()),bytes(delayed.id().value()))==1,"delayed request did not preserve normal no-op");
        command("delayed-release",()->first.booking.transition(first.venue.id(),delayedHold.id(),delayedOwner,ReservationCommand.CANCEL));
        drain();
        duplicateProbe();
        observe();

        phase = "hot-slot";
        SlotInventory hot = first.slot(4);
        var blocker = first.customer();
        var blockingHold = command("hold", () -> first.booking.createHold(new ReservationUseCase.CreateHold(first.venue.id(), hot.id(), blocker, 2)).reservation());
        var candidates = new ArrayList<Candidate>();
        var keys = new ArrayList<WaitlistRegistrationKey>();
        for (int i=0;i<CLIENTS;i++) { candidates.add(new Candidate(null, first.customer())); keys.add(new WaitlistRegistrationKey(nextId())); }
        var gate = new CyclicBarrier(CLIENTS);
        try (var clients = Executors.newFixedThreadPool(CLIENTS)) {
            var tasks = new ArrayList<Future<UUID>>();
            for (int i=0;i<CLIENTS;i++) {
                final int index=i;
                tasks.add(clients.submit(() -> { gate.await(10, TimeUnit.SECONDS); return command("contended-registration", () -> first.waitlist.register(
                    new WaitlistUseCase.CreateRegistration(first.venue.id(),hot.id(),2,keys.get(index),candidates.get(index).customer)).entry().id()); }));
            }
            for(int i=0;i<CLIENTS;i++) candidates.set(i,new Candidate(tasks.get(i).get(30,TimeUnit.SECONDS),candidates.get(i).customer));
        }
        command("hot-release", () -> first.booking.transition(first.venue.id(),blockingHold.id(),blocker,ReservationCommand.CANCEL));
        observe();
        // One DB executor competes with real public HOLD commands for the same Slot lock.
        // Scheduling chooses the winner; business conflicts remain distinct from system failure.
        var contenders=new ArrayList<AuthenticatedPrincipal>();
        for(int i=0;i<CLIENTS;i++) contenders.add(first.customer());
        var capacityGate=new CyclicBarrier(CLIENTS+1);
        var winners=new ArrayList<Map.Entry<Reservation,AuthenticatedPrincipal>>();
        try(var clients=Executors.newFixedThreadPool(CLIENTS+1)) {
            var execution=clients.submit(()->{capacityGate.await(10,TimeUnit.SECONDS);return cycle();});
            var attempts=new ArrayList<Future<Reservation>>();
            for(var principal:contenders) attempts.add(clients.submit(()->{
                capacityGate.await(10,TimeUnit.SECONDS);
                try {return command("contended-hold",()->first.booking.createHold(new ReservationUseCase.CreateHold(first.venue.id(),hot.id(),principal,2)).reservation());}
                catch(CapacityUnavailableException businessConflict) {return null;}
            }));
            for(int i=0;i<CLIENTS;i++) {var reservation=attempts.get(i).get(30,TimeUnit.SECONDS);if(reservation!=null)winners.add(Map.entry(reservation,contenders.get(i)));}
            execution.get(30,TimeUnit.SECONDS);
        }
        require(winners.size()<=1,"hot HOLD competitors exceeded capacity");
        observe();
        for(var winner:winners) command("contended-winner-release",()->first.booking.transition(first.venue.id(),winner.getKey().id(),winner.getValue(),ReservationCommand.CANCEL));
        drain();
        UUID expected = jdbc.queryForObject("SELECT e.id FROM waitlist_entries e JOIN waitlist_demands d ON d.id=e.demand_id WHERE e.tenant_id=? AND d.starts_at=? AND e.id IN (?,?,?,?) ORDER BY e.joined_at,e.id LIMIT 1",
            (row,n) -> uuid(row.getBytes(1)), bytes(first.tenant.id().value()), LocalDateTime.ofInstant(hot.startsAt(),ZoneOffset.UTC),
            bytes(candidates.get(0).id),bytes(candidates.get(1).id),bytes(candidates.get(2).id),bytes(candidates.get(3).id));
        require(expected.equals(offerEntry(first,hot)), "hot Slot binary FIFO mismatch");
        accept(first,hot,candidates.stream().filter(c->c.id.equals(expected)).findFirst().orElseThrow());
        observe();

        phase = "independent-slots";
        for(int i=0;i<2;i++) { Fixture f=i==0?first:second; SlotInventory slot=f.slot(4); f.entry(slot,2); request(f,slot); }
        observe();drain();observe();
        phase = "backlog";
        for(int i=0;i<GROUPS;i++) { Fixture f=i%2==0?first:second; SlotInventory slot=f.slot(4); f.entry(slot,2); request(f,slot); }
        observe();
        require(fixtures.stream().mapToLong(f -> list(new WaitlistRecoveryDatabase(jdbc).snapshot(f.tenant.id().value(),clock.instant()),"event_records").size()
            - stat(new WaitlistRecoveryDatabase(jdbc).snapshot(f.tenant.id().value(),clock.instant()),"done")).sum() > BATCH, "backlog must exceed batch");
        drain(); observe();
        phase = "idle";
        for(int i=0;i<3;i++) require(cycle()==0,"idle cycle claimed work");
        observe();
        phase="duplicate-verification";
        duplicateProbe();observe();
        return Map.of("schemaVersion","slotq-waitlist-baseline/v1","manifest",manifest,"commands",commands,"cycles",cycles,"observations",observations);
    }

    private void duplicateProbe() {
        var before = fixtures.stream().map(f -> new WaitlistRecoveryDatabase(jdbc).snapshot(f.tenant.id().value(),clock.instant())).toList();
        for(Fixture f:fixtures) for(var row:list(new WaitlistRecoveryDatabase(jdbc).snapshot(f.tenant.id().value(),clock.instant()),"event_deliveries")) {
            var key=new DeliveryKey(f.tenant.id(),new EventId(hexUuid(row.get("event_id"))),hexUuid(row.get("registration_id")));
            command("duplicate-handler-probe",()->context.getBean(DeliveryTransactions.class).execute(()->{
                var target=context.getBean(EventDeliveryStore.class).target(key);
                context.getBean(EventHandlers.class).resolve(target.route()).handle(target.event()); return "receipt-reused";
            }));
            require(worker.claim(key).isEmpty(),"DONE became claimable");
        }
        var after=fixtures.stream().map(f -> new WaitlistRecoveryDatabase(jdbc).snapshot(f.tenant.id().value(),clock.instant())).toList();
        for(int i=0;i<before.size();i++) for(String table:TABLES) require(before.get(i).get(table).equals(after.get(i).get(table)),"duplicate changed "+table);
    }
    private void request(Fixture f,SlotInventory slot) { var result=command("promotion-request",()->context.getBean(WaitlistPromotionRequestUseCase.class).request(SystemPrincipal.INSTANCE,f.venue.id(),slot.id())); require(result.outcome()==WaitlistPromotionRequestUseCase.Outcome.APPENDED,"request not admitted"); }
    private int cycle() { long start=System.nanoTime();String wallStart=Instant.now().toString();int claimed=worker.runCycle();cycles.add(Map.of("phase",phase,"startNanos",start,"endNanos",System.nanoTime(),"hostStartAt",wallStart,"hostEndAt",Instant.now().toString(),"claimed",claimed));return claimed; }
    private void drain() { observe(); for(int i=0;i<100;i++) { cycle(); if(fixtures.stream().allMatch(f->drained(new WaitlistRecoveryDatabase(jdbc).snapshot(f.tenant.id().value(),clock.instant())))) {observe();return;} } throw new IllegalStateException("baseline did not drain"); }
    private void observe() { long start=System.nanoTime();String wallStart=Instant.now().toString();Instant now=clock.instant();var tenants=fixtures.stream().map(f->new WaitlistRecoveryDatabase(jdbc).snapshot(f.tenant.id().value(),now)).toList();observations.add(Map.of("phase",phase,"startNanos",start,"endNanos",System.nanoTime(),"hostStartAt",wallStart,"hostEndAt",Instant.now().toString(),"verificationNow",now.toString(),"tenants",tenants)); }
    private <T> T command(String type,Supplier<T> action) {
        long start=System.nanoTime();String wallStart=Instant.now().toString();Instant business=clock.instant();
        try {T result=action.get();commands.add(Map.of("phase",phase,"type",type,"startNanos",start,"endNanos",System.nanoTime(),"hostStartAt",wallStart,"hostEndAt",Instant.now().toString(),"businessNow",business.toString(),"committed",true,"result",String.valueOf(result)));return result;}
        catch(RuntimeException failure) {
            // Only authoritative capacity conflict is an expected workload outcome; propagate all failures.
            commands.add(Map.of("phase",phase,"type",type,"startNanos",start,"endNanos",System.nanoTime(),"hostStartAt",wallStart,"hostEndAt",Instant.now().toString(),"businessNow",business.toString(),"committed",false,"result",failure instanceof CapacityUnavailableException?"CAPACITY_UNAVAILABLE":failure.getClass().getName()));
            throw failure;
        }
    }
    private Fixture fixture() { var f=new Fixture();fixtures.add(f);return f; }
    private UUID nextId() { return new UUID(random.nextLong(),random.nextLong()); }
    private static UUID uuid(byte[] bytes) { var b=java.nio.ByteBuffer.wrap(bytes);return new UUID(b.getLong(),b.getLong()); }
    private static UUID hexUuid(Object value) {return uuid(HexFormat.of().parseHex(value.toString()));}
    private WaitlistOfferId offerId(Fixture f,SlotInventory s) { return new WaitlistOfferId(jdbc.queryForObject("SELECT id FROM waitlist_offers WHERE tenant_id=? AND slot_inventory_id=? AND state='PENDING'",(row,n)->uuid(row.getBytes(1)),bytes(f.tenant.id().value()),bytes(s.id().value()))); }
    private WaitlistOfferUseCase.OfferView offer(Fixture f,SlotInventory s) {return context.getBean(WaitlistOfferUseCase.class).getOffer(f.venue.id(),offerId(f,s),f.offerCustomer(s));}
    private UUID offerEntry(Fixture f,SlotInventory s) { return jdbc.queryForObject("SELECT entry_id FROM waitlist_offers WHERE tenant_id=? AND slot_inventory_id=? AND state='PENDING'",(row,n)->uuid(row.getBytes(1)),bytes(f.tenant.id().value()),bytes(s.id().value())); }
    private void accept(Fixture f,SlotInventory s,Candidate c) { var result=command("offer-accept",()->context.getBean(WaitlistOfferUseCase.class).accept(f.venue.id(),offerId(f,s),c.customer));require(result.outcome()==WaitlistOfferUseCase.CommandOutcome.SUCCESS,"accept failed"); }
    private void reject(Fixture f,SlotInventory s,Candidate c) { var result=command("offer-reject",()->context.getBean(WaitlistOfferUseCase.class).reject(f.venue.id(),offerId(f,s),c.customer));require(result.outcome()==WaitlistOfferUseCase.CommandOutcome.SUCCESS,"reject failed"); }
    private record Candidate(UUID id,AuthenticatedPrincipal customer) {}
    private final class Fixture {
        final Tenant tenant=context.getBean(TenantUseCase.class).createTenant();
        final Venue venue=context.getBean(VenueConfigurationUseCase.class).createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(),"Baseline fixture","UTC",new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(18,0)))),new BookingPolicyTerms(30,5,20,10)));
        final ReservationUseCase booking=context.getBean(ReservationUseCase.class);
        final WaitlistUseCase waitlist=context.getBean(WaitlistUseCase.class);
        final Map<UUID,AuthenticatedPrincipal> customers=new HashMap<>();
        SlotInventory slot(int seats) { var r=context.getBean(ResourceUseCase.class).createResource(new ResourceUseCase.CreateResource(tenant.id(),venue.id(),"Baseline table",seats));return context.getBean(SlotInventoryUseCase.class).createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(),venue.id(),r.id(),BASE.plusSeconds(7200).toString())); }
        AuthenticatedPrincipal customer() { var p=new AuthenticatedPrincipal(new PrincipalId(nextId()));context.getBean(AccessControlProvisioning.class).registerPrincipal(p.principalId());return p; }
        Candidate entry(SlotInventory slot,int party) { var p=customer();var key=new WaitlistRegistrationKey(nextId()); var entry=command("registration",()->waitlist.register(new WaitlistUseCase.CreateRegistration(venue.id(),slot.id(),party,key,p)).entry());customers.put(entry.id(),p);clock.now.set(clock.instant().plusNanos(1000));return new Candidate(entry.id(),p); }
        AuthenticatedPrincipal offerCustomer(SlotInventory slot) {return customers.get(offerEntry(this,slot));}
    }

    private Map<String,Object> manifest(MySQLContainer mysql,long seed,long startup,long ready) throws Exception {
        var result=new LinkedHashMap<String,Object>();
        result.put("revision",git("rev-parse","HEAD"));result.put("dirty",!git("status","--porcelain").isBlank());result.put("workingTreeStatus",git("status","--porcelain"));
        var hashes=new TreeMap<String,String>();
        for(String root:List.of("src/main","src/test/java/com/slotq/experiments/waitlist")) try(var paths=Files.walk(Path.of(root))) {for(Path p:paths.filter(Files::isRegularFile).sorted().toList()) hashes.put(p.toString().replace('\\','/'),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));}
        hashes.put("build.gradle",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("build.gradle")))));
        result.put("sourceSha256",hashes);result.put("javaVersion",System.getProperty("java.runtime.version"));result.put("springBootVersion",SpringBootVersion.getVersion());result.put("gradleVersion",System.getProperty("slotq.waitlist.baseline.gradleVersion","test"));
        var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        result.put("cpuProcessors",os.getAvailableProcessors());result.put("hostRamBytes",os.getTotalMemorySize());result.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        result.put("cpuModel",System.getenv().getOrDefault("PROCESSOR_IDENTIFIER","unavailable"));
        var docker=DockerClientFactory.instance().client();var info=docker.infoCmd().exec();var inspect=docker.inspectContainerCmd(mysql.getContainerId()).exec();var limits=inspect.getHostConfig();
        result.put("dockerVersion",docker.versionCmd().exec().getVersion());result.put("dockerVm",Map.of("cpus",info.getNCPU(),"ramBytes",info.getMemTotal(),"kernel",info.getKernelVersion()));
        result.put("container",Map.of("image","mysql:8.4","imageId",inspect.getImageId(),"memoryBytes",limits.getMemory(),"nanoCpus",limits.getNanoCPUs(),"cpuQuota",limits.getCpuQuota(),"cpuPeriod",limits.getCpuPeriod()));
        result.put("mysql",jdbc.queryForMap("SELECT VERSION() version, @@transaction_isolation isolation_level, @@innodb_buffer_pool_size buffer_pool_bytes, @@max_connections max_connections, @@innodb_flush_log_at_trx_commit flush_log_at_trx_commit, @@sync_binlog sync_binlog, @@system_time_zone system_time_zone, @@session.time_zone session_time_zone"));
        var pool=context.getBean(HikariDataSource.class);result.put("pool",Map.of("maximum",pool.getMaximumPoolSize(),"minimumIdle",pool.getMinimumIdle(),"connectionTimeoutMillis",pool.getConnectionTimeout(),"isolation","MySQL default REPEATABLE-READ"));
        result.put("workload",Map.of("seed",seed,"phases",List.of("normal","hot-slot","independent-slots","backlog","idle","duplicate-verification"),"tenants",2,"hotClients",CLIENTS,"backlogInputs",GROUPS,"batchSize",BATCH,"workerCount",1,"transport","db_direct","logicalConsumer","waitlist.promotion","serverIds","Product-generated random IDs retained in raw; seed controls client IDs/keys, not scheduling or DB tie breaks"));
        result.put("runtime",Map.of("scheduler","production activation enabled; test scheduler registers no timer tasks; explicit one-worker runCycle, manual discovery/expiry commands","lease","PT30S","effectTimeout","PT10S","lockWait","PT5S","maxAttempts",5,"retryDelays",List.of("PT1S","PT5S","PT30S","PT2M"),"startupMillis",(ready-startup)/1_000_000.0,"warmup","none; cold/finite workload, no steady-state claim"));
        long before=System.currentTimeMillis();String dbNow=jdbc.queryForObject("SELECT CAST(UTC_TIMESTAMP(6) AS CHAR)",String.class);long after=System.currentTimeMillis();
        var time=new LinkedHashMap<String,Object>();
        time.put("businessClock","test-only mutable UTC Clock; advances 1us per sequential registration and to actual Offer expiry");
        time.put("base",BASE.toString());time.put("elapsedClock","System.nanoTime; process-local");
        time.put("hostBeforeMillis",before);time.put("dbUtcObserved",dbNow);time.put("hostAfterMillis",after);
        long dbMillis=LocalDateTime.parse(dbNow.replace(' ','T')).toInstant(ZoneOffset.UTC).toEpochMilli();
        time.put("dbMinusHostOffsetLowerMillis",dbMillis-after-1);time.put("dbMinusHostOffsetUpperMillis",dbMillis-before+1);
        time.put("clockError","DB UTC sample bracket only; no precision/synchronization assumption");
        time.put("commitObservation","public command return and post-cycle fresh DB read are upper-bound observations; no physical commit timestamp recorded");
        time.put("recordedAt","DB insert timestamp inside event transaction; not physical commit");
        time.put("brokerAck","not measured: DB direct mode");
        time.put("durableIntake","not applicable in DB direct; target materialization observation only");
        result.put("time",time);
        return result;
    }
    private static String git(String... args) throws Exception {var command=new ArrayList<>(List.of("git","-c","safe.directory=C:/dev/slotq"));command.addAll(List.of(args));var p=new ProcessBuilder(command).redirectErrorStream(true).start();String value=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);require(p.waitFor()==0,value);return value.strip();}
    private static void write(Path path,Object data) throws Exception {Files.writeString(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data)+"\n");}
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}

    @TestConfiguration(proxyBeanMethods=false)
    static class Configuration {
        @Bean @Primary Clock baselineClock() {return new WaitlistProcessRecoveryRunner.MutableClock(BASE);}
        @Bean ThreadPoolTaskScheduler taskScheduler() {
            // Keep production bootstrap requirements; timers are controlled only in this experiment.
            return new ThreadPoolTaskScheduler() {
                @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Duration delay) {return new CompletableScheduledFuture();}
                @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Instant start,Duration delay) {return new CompletableScheduledFuture();}
            };
        }
    }
    private static final class CompletableScheduledFuture extends CompletableFuture<Void> implements ScheduledFuture<Void> {
        public long getDelay(TimeUnit unit) {return Long.MAX_VALUE;}
        public int compareTo(Delayed other) {return 1;}
    }
}
