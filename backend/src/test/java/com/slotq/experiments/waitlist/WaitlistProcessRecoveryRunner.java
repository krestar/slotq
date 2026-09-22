package com.slotq.experiments.waitlist;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.transaction.support.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;
import static com.slotq.experiments.waitlist.WaitlistRecoveryDatabase.*;

/** Test-classpath-only process coordinator. No production code, state machine or synthetic effect. */
public final class WaitlistProcessRecoveryRunner {
    static final String SCHEMA="slotq-waitlist-process-recovery/v1";
    static final int BATCH=2, GROUPS=3;
    static final ObjectMapper JSON=new ObjectMapper();
    static String mode;
    static Path directory;
    static UUID tenantId;
    static volatile boolean ticks;
    static MutableClock clock;

    public static void main(String[] args) throws Exception {
        if(args.length>0) child(args); else parent();
    }

    private static void parent() throws Exception {
        Path output=Path.of(System.getProperty("slotq.waitlist.recovery.output","build/reports/experiments/waitlist-recovery" )).toAbsolutePath();
        Files.createDirectories(output);
        var environment=new LinkedHashMap<String,Object>();
        environment.put("applicationRevision",git("rev-parse","HEAD"));
        environment.put("branch",git("branch","--show-current"));
        environment.put("dirty",!git("status","--porcelain").isBlank());
        environment.put("workingTreeStatus",git("status","--porcelain"));
        environment.put("productionSourceUnchanged",git("diff","HEAD","--","src/main").isBlank());
        environment.put("javaVersion",System.getProperty("java.runtime.version"));
        environment.put("springBootVersion",SpringBootVersion.getVersion());
        environment.put("gradleVersion",System.getProperty("slotq.recovery.gradleVersion"));
        environment.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version"));
        environment.put("recordedAt",Instant.now().toString());
        environment.put("databaseContinuity","one persistent MySQL container/database across every child JVM and all five cases; no row deletion/reset");
        environment.put("testConfiguration",Map.of("batchSize",BATCH,"groupsPerBacklogKind",GROUPS,"lease","PT8S",
            "effectTimeout","PT3S","lockWait","PT1S","maxAttempts",3,"retryDelays",List.of("PT0.1S","PT0.2S"),
            "schedulerIntervals","PT0.1S","productionSlo",false));
        var hashes=new TreeMap<String,String>();
        try(var sources=Files.list(Path.of("src/test/java/com/slotq/experiments/waitlist"))) {
            for(Path source:sources.filter(p->p.toString().endsWith(".java")).toList())
                hashes.put(source.getFileName().toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
        }
        environment.put("harnessSourceSha256",hashes);
        var cases=new ArrayList<Map<String,Object>>();
        try(var mysql=new MySQLContainer("mysql:8.4").withDatabaseName("slotq_waitlist_process_recovery")) {
            mysql.start();
            environment.put("mysqlContainerId",mysql.getContainerId());
            environment.put("databaseName",mysql.getDatabaseName());
            String url=mysql.getJdbcUrl();
            var db=new JdbcTemplate(new DriverManagerDataSource(url+(url.contains("?")?"&":"?")
                +"connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",mysql.getUsername(),mysql.getPassword()));
            var oracle=new WaitlistRecoveryDatabase(db);
            environment.put("mysqlVersion",db.queryForObject("SELECT VERSION()",String.class));
            environment.put("transactionIsolation",db.queryForObject("SELECT @@transaction_isolation",String.class));
            environment.put("dockerVersion",DockerClientFactory.instance().client().versionCmd().exec().getVersion());
            String only=System.getProperty("slotq.waitlist.recovery.only","all");
            String[] names={"RELEASE_CRASH","EFFECT_CRASH","COMMIT_CRASH","BACKLOG_SEED","OUTAGE_SEED"};
            try {
                for(int index=0;index<names.length;index++) {
                    if(!only.equals("all")&&!only.equals(names[index])) continue;
                    cases.add(runCase(mysql,oracle,output,names[index],index));
                }
            } finally {
                write(output.resolve("report.json"),Map.of("schemaVersion",SCHEMA,"environment",environment,"cases",cases,
                    "summary",Map.of("passed",cases.size(),"expected",only.equals("all")?5:1,"complete",cases.size()==(only.equals("all")?5:1))));
            }
        }
        System.out.println("Waitlist process recovery evidence: "+output);
    }

    private static Map<String,Object> runCase(MySQLContainer mysql,WaitlistRecoveryDatabase db,Path output,String name,int index) throws Exception {
        Path folder=output.resolve(name);
        require(!Files.exists(folder),"Use a fresh output directory; never overwrite prior fault evidence: "+folder);
        Files.createDirectories(folder);
        // Separate Sunday fixtures, monotonically increasing business time; previous tenants are never deleted.
        Instant now=Instant.parse("2026-08-30T09:00:00Z").plus(Duration.ofDays(index*7L));
        boolean backlog=index>=3;
        long beginning=System.nanoTime();
        var children=new ArrayList<Map<String,Object>>();
        int seedExit=new int[]{86,87,88,89,91}[index];
        try(var seed=start(mysql,name,folder,now)) { seed.expect(seedExit);children.add(Map.of("role",name,"pid",seed.process().pid(),"exitCode",seedExit)); }
        var manifest=JSON.readTree(Files.readString(folder.resolve("manifest.json")));
        UUID tenant=UUID.fromString(manifest.path("tenantId").asText());
        Instant recoveryNow=backlog?now.plusSeconds(31*60):now;
        var first=db.snapshot(tenant,recoveryNow);write(folder.resolve("first.json"),first);
        if(index==0) require(stat(first,"event_records")==1&&stat(first,"event_deliveries")==0,"release before materialization");
        if(index==1) {
            require(stat(first,"processing")==1&&stat(first,"reservations")==1&&stat(first,"capacity_allocations")==1
                &&stat(first,"waitlist_offers")==0&&stat(first,"waitlist_promotion_receipts")==0
                &&stat(first,"waitlist_notification_requests")==0&&stat(first,"waiting")==1&&stat(first,"activeAllocations")==0,"partial effect committed");
            require(attempt(first)==1,"crash attempt not charged");
        }
        if(index==2) { single(first);require(attempt(first)==1,"postcommit attempt"); }
        if(backlog) require(stat(first,"dueHeld")==6&&stat(first,"pendingOffers")==3&&stat(first,"dueWaiting")==3
            &&stat(first,"waiting")==12,"backlog does not exceed batch in all kinds");
        var row=new LinkedHashMap<String,Object>();row.put("name",name);row.put("seedExitCode",seedExit);
        row.put("childProcesses",children);
        row.put("firstAuthoritativeState",first);
        row.put("faultPoint",switch(index){case 0->"after Booking release command physical commit, before materialization";
            case 1->"actual handler and JPA flush complete, before M3 DONE SQL / effect commit";
            case 2->"physical effect+receipt+DONE commit, before transaction completion returns";
            case 3->"due durable backlog while maintenance timer is paused";default->"activated production worker DB access while MySQL is paused";});
        if(index==1||index==2) {
            row.put("uncommittedFaultDiagnostic",JSON.readTree(Files.readString(folder.resolve("inside-effect.json"))));
            require(!Files.exists(folder.resolve("process-returned")),"faulting caller observed process return");
        }
        if(index==4) {
            require(stat(first,"processing")==1&&stat(first,"unfinishedRequestLinks")==1,"outage needs durable outstanding request and claim");
            boolean paused=false;
            try(var outage=start(mysql,"OUTAGE_ACCESS",folder,recoveryNow)) {
                awaitFile(folder.resolve("outage-ready"),outage);
                DockerClientFactory.instance().client().pauseContainerCmd(mysql.getContainerId()).exec();paused=true;
                Files.createFile(folder.resolve("outage-go"));outage.expect(90);
                children.add(Map.of("role","OUTAGE_ACCESS","pid",outage.process().pid(),"exitCode",90));
                row.put("databaseOutageProbe",JSON.readTree(Files.readString(folder.resolve("database-failure.json"))));
            } finally { if(paused) DockerClientFactory.instance().client().unpauseContainerCmd(mysql.getContainerId()).exec(); }
            var after=db.snapshot(tenant,recoveryNow);write(folder.resolve("after-outage.json"),after);
            for(String table:TABLES) require(first.get(table).equals(after.get(table)),"outage changed durable "+table);
            row.put("afterOutageAuthoritativeState",after);row.put("childExitCode",90);
        } else row.put("childExitCode",seedExit);
        long recoveryStart=System.nanoTime();
        Map<String,Object> recovered;
        try(var child=start(mysql,backlog?"RECOVER_BACKLOG":"RECOVER_SINGLE",folder,recoveryNow)) {
            awaitFile(folder.resolve("recovery-ready"),child);
            if(backlog) {
                var middle=awaitState(db,tenant,recoveryNow,s->drained(s)&&stat(s,"waitlist_offers")==12&&stat(s,"pendingOffers")==9
                    &&stat(s,"offered")==9&&stat(s,"dueWaiting")==0&&stat(s,"dueHeld")==0,child);
                write(folder.resolve("progress.json"),middle);row.put("progressAuthoritativeState",middle);
                require(stat(middle,"waitlist_promotion_requests")>=3,"missing release-free request progress");
                Files.createFile(folder.resolve("advance-clock"));recoveryNow=now.plusSeconds(7200);
                recovered=awaitState(db,tenant,recoveryNow,s->drained(s)&&stat(s,"pendingOffers")==0&&stat(s,"held")==0
                    &&stat(s,"waiting")==0&&stat(s,"activeAllocations")==0&&stat(s,"expiredEntries")==15,child);
                require(stat(recovered,"waitlist_offers")==12&&stat(recovered,"waitlist_notification_requests")==12,"backlog duplicate or missing effect");
            } else {
                recovered=awaitState(db,tenant,recoveryNow,s->drained(s)&&stat(s,"waitlist_offers")==1,child);single(recovered);
                require(attempt(recovered)==(index==1?2:1),"unexpected restart attempt budget");
            }
            require(first.get("registrations").equals(recovered.get("registrations")),"restart changed registration generation");
            require(stat(recovered,"duplicateLogicalEffects")==0&&stat(recovered,"occupancyViolations")==0,"duplicate/capacity invariant");
            long observation=System.nanoTime();
            row.put("recoveryLaunchToObservationMillis",TimeUnit.NANOSECONDS.toMillis(observation-recoveryStart));
            row.put("seedLaunchToRecoveredObservationUpperBoundMillis",TimeUnit.NANOSECONDS.toMillis(observation-beginning));
            write(folder.resolve("recovered.json"),recovered);
            Files.createFile(folder.resolve("stop"));child.expect(0);
            children.add(Map.of("role",backlog?"RECOVER_BACKLOG":"RECOVER_SINGLE","pid",child.process().pid(),"exitCode",0));
        }
        row.put("recoveryAuthoritativeState",recovered);row.put("recoveryExitCode",0);row.put("outcome","PASS");
        row.put("timingMeaning","parent monotonic window includes startup/fault/restart; upper bound, not commandNow latency or production SLO");
        System.out.println(name+" PASS");return row;
    }

    private static void child(String[] args) throws Exception {
        mode=args[0];directory=Path.of(args[1]);clock=new MutableClock(Instant.parse(args[2]));ticks=false;
        if(Files.exists(directory.resolve("manifest.json"))) tenantId=UUID.fromString(JSON.readTree(Files.readString(directory.resolve("manifest.json"))).path("tenantId").asText());
        try(var context=context()) {
            require(context.getBean(WaitlistPromotionReadiness.class).isReady(),"production activation failed");
            var worker=context.getBean(EventDeliveryWorker.class);
            if(mode.startsWith("RECOVER")) {
                ticks=true;Files.createFile(directory.resolve("recovery-ready"));
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
                while(!Files.exists(directory.resolve("stop"))) {
                    if(Files.exists(directory.resolve("advance-clock"))) {
                        var manifest=JSON.readTree(Files.readString(directory.resolve("manifest.json")));
                        clock.now.set(Instant.parse(manifest.path("startAt").asText()));
                    }
                    require(System.nanoTime()<end,"parent did not observe recovery within bound");Thread.sleep(25);
                }
                ticks=false;context.getBean(ThreadPoolTaskScheduler.class).shutdown();return;
            }
            if(mode.equals("OUTAGE_ACCESS")) {
                Files.createFile(directory.resolve("outage-ready"));waitFile(directory.resolve("outage-go"));
                try { worker.runCycle(); }
                catch(RuntimeException failure) {
                    String type=databaseFailure(failure);if(type==null)throw failure;
                    write(directory.resolve("database-failure.json"),Map.of("entryPoint","EventDeliveryWorker.runCycle",
                        "contextReadyBeforePause",true,"databaseAccessFailureObserved",true,"failureType",type,"childExitCode",90));
                    Runtime.getRuntime().halt(90);
                }
                throw new IllegalStateException("DB access succeeded during pause");
            }
            if(mode.equals("BACKLOG_SEED")||mode.equals("OUTAGE_SEED")) {
                seedBacklog(context,mode.equals("OUTAGE_SEED"));Runtime.getRuntime().halt(mode.equals("BACKLOG_SEED")?89:91);
            }
            seedSingle(context);
            if(mode.equals("RELEASE_CRASH"))Runtime.getRuntime().halt(86);
            worker.runCycle();Files.createFile(directory.resolve("process-returned"));
            throw new IllegalStateException("Expected effect crash did not occur");
        }
    }

    private static ConfigurableApplicationContext context() {
        return new SpringApplicationBuilder(SlotqApplication.class,ChildConfiguration.class).properties(Map.of(
            "spring.datasource.url",System.getenv("M4_DB_URL"),"spring.datasource.username",System.getenv("M4_DB_USER"),
            "spring.datasource.password",System.getenv("M4_DB_PASSWORD"),"spring.datasource.hikari.connection-timeout","1000",
            "spring.datasource.hikari.validation-timeout","1000","spring.main.banner-mode","off","server.port","0","logging.level.root","ERROR"))
            .run("--slotq.waitlist.promotion.enabled=true","--slotq.waitlist.promotion.maintenance-enabled=true",
                "--slotq.events.delivery.scheduler-enabled=true","--slotq.events.delivery.poll-interval=PT0.1S",
                "--slotq.waitlist.promotion.maintenance-interval=PT0.1S","--slotq.waitlist.promotion.maintenance-batch-size=2",
                "--slotq.waitlist.promotion.discovery-batch-size=2","--slotq.events.delivery.batch-size=2",
                "--slotq.events.delivery.max-attempts=3","--slotq.events.delivery.lease=PT8S",
                "--slotq.events.delivery.effect-timeout=PT3S","--slotq.events.delivery.lock-wait=PT1S",
                "--slotq.events.delivery.retry-delays=PT0.1S,PT0.2S");
    }

    private static void seedSingle(ConfigurableApplicationContext context) throws Exception {
        var f=new BusinessFixture(context);var slot=f.slot(f.start);var owner=f.customer();var hold=f.hold(slot,owner);
        f.entry(slot);f.manifest();f.booking.transition(f.venue.id(),hold.id(),owner,ReservationCommand.CANCEL);
    }
    private static void seedBacklog(ConfigurableApplicationContext context,boolean outage) throws Exception {
        var f=new BusinessFixture(context);var free=new ArrayList<SlotInventory>();
        for(int i=0;i<GROUPS;i++) {
            var ordinary=f.slot(f.start);f.hold(ordinary,f.customer());f.entry(ordinary);
            var promotion=f.slot(f.start);var owner=f.customer();var held=f.hold(promotion,owner);f.entry(promotion);
            f.booking.transition(f.venue.id(),held.id(),owner,ReservationCommand.CANCEL);
            context.getBean(EventDeliveryWorker.class).runCycle();
            clock.now.set(clock.instant().plusSeconds(1));f.entry(promotion);
            f.entry(f.slot(f.base.plusSeconds(1800)));
            var available=f.slot(f.start);f.entry(available);free.add(available);
        }
        f.manifest();
        if(outage) {
            var request=context.getBean(WaitlistPromotionRequestUseCase.class).request(SystemPrincipal.INSTANCE,f.venue.id(),free.getFirst().id());
            require(request.outcome()==WaitlistPromotionRequestUseCase.Outcome.APPENDED,"outage request missing");
            var worker=context.getBean(EventDeliveryWorker.class);worker.materialize();
            var jdbc=context.getBean(JdbcTemplate.class);
            var registration=jdbc.queryForObject("SELECT registration_id FROM event_registrations WHERE event_type='waitlist.promotion-requested' AND deactivation_boundary IS NULL",byte[].class);
            var buffer=java.nio.ByteBuffer.wrap(registration);var id=new UUID(buffer.getLong(),buffer.getLong());
            require(worker.claim(new DeliveryKey(f.tenant.id(),new EventId(request.eventId()),id)).isPresent(),"outage request not claimed");
        }
    }
    private static final class BusinessFixture {
        final ConfigurableApplicationContext context;final Tenant tenant;final Venue venue;final ReservationUseCase booking;
        final Instant base=clock.instant(),start=base.plusSeconds(7200);
        BusinessFixture(ConfigurableApplicationContext context) {
            this.context=context;booking=context.getBean(ReservationUseCase.class);tenant=context.getBean(TenantUseCase.class).createTenant();tenantId=tenant.id().value();
            venue=context.getBean(VenueConfigurationUseCase.class).createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(),"Recovery fixture","UTC",
                new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(14,0)))),new BookingPolicyTerms(30,5,20,10)));
        }
        SlotInventory slot(Instant start) { var r=context.getBean(ResourceUseCase.class).createResource(new ResourceUseCase.CreateResource(tenant.id(),venue.id(),"Fixture table",4));
            return context.getBean(SlotInventoryUseCase.class).createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(),venue.id(),r.id(),start.toString())); }
        AuthenticatedPrincipal customer() { var p=new AuthenticatedPrincipal(PrincipalId.newId());context.getBean(AccessControlProvisioning.class).registerPrincipal(p.principalId());return p; }
        Reservation hold(SlotInventory slot,AuthenticatedPrincipal p) { return booking.createHold(new ReservationUseCase.CreateHold(venue.id(),slot.id(),p,2)).reservation(); }
        void entry(SlotInventory slot) { context.getBean(WaitlistUseCase.class).register(new WaitlistUseCase.CreateRegistration(venue.id(),slot.id(),2,new WaitlistRegistrationKey(UUID.randomUUID()),customer())); }
        void manifest() throws Exception {write(directory.resolve("manifest.json"),Map.of("tenantId",tenant.id().value().toString(),"startAt",start.toString(),"baseAt",base.toString(),"seed","public commands; random server IDs preserved in snapshots"));}
    }

    @TestConfiguration(proxyBeanMethods=false)
    static class ChildConfiguration {
        @Bean @Primary Clock fixtureClock() { return clock; }
        @Bean ThreadPoolTaskScheduler taskScheduler() {
            var scheduler=new ThreadPoolTaskScheduler() {
                @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Duration delay) {
                    return super.scheduleWithFixedDelay(gated(task),delay);
                }
                @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Instant start,Duration delay) {
                    return super.scheduleWithFixedDelay(gated(task),start,delay);
                }
                private Runnable gated(Runnable task) {
                    return ()->{
                        if(!ticks)return;
                        // Single-event crash isolation only. Backlog recovery uses BOTH unmodified production schedulers.
                        if(mode.equals("RECOVER_SINGLE")&&task instanceof ScheduledMethodRunnable scheduled
                            &&scheduled.getTarget() instanceof WaitlistMaintenanceScheduler)return;
                        task.run();
                    };
                }
            };
            scheduler.setPoolSize(1);scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(10);return scheduler;
        }
        @Bean static BeanPostProcessor effectCrashHook(ObjectProvider<JdbcTemplate> jdbc) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if(!(bean instanceof EventDeliveryStore)||(!mode.equals("EFFECT_CRASH")&&!mode.equals("COMMIT_CRASH")))return bean;
                    return Proxy.newProxyInstance(EventDeliveryStore.class.getClassLoader(),new Class<?>[]{EventDeliveryStore.class},(proxy,method,args)->{
                        if(method.getName().equals("done")) {
                            require(TransactionSynchronizationManager.isActualTransactionActive(),"effect hook outside transaction");
                            var inside=WaitlistRecoveryDatabase.read(jdbc.getObject(),tenantId,clock.instant());
                            require(stat(inside,"waitlist_offers")==1&&stat(inside,"promoted")==1&&stat(inside,"waitlist_notification_requests")==1
                                &&stat(inside,"reservations")==2&&stat(inside,"capacity_allocations")==2&&stat(inside,"offered")==1,"actual effect writes not reached");
                            write(directory.resolve("inside-effect.json"),inside);
                            if(mode.equals("EFFECT_CRASH"))Runtime.getRuntime().halt(87);
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override public void afterCommit(){Runtime.getRuntime().halt(88);}
                            });
                        }
                        try{return method.invoke(bean,args);}catch(InvocationTargetException e){throw e.getCause();}
                    });
                }
            };
        }
    }
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now;MutableClock(Instant now){this.now=new AtomicReference<>(now);}
        public Instant instant(){return now.get();}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
    }

    private static RunningChild start(MySQLContainer mysql,String command,Path folder,Instant now) throws Exception {
        Path log=folder.resolve(command.toLowerCase(Locale.ROOT)+".log"),args=folder.resolve(command.toLowerCase(Locale.ROOT)+".args");
        Files.writeString(args,"-cp\n\""+System.getProperty("java.class.path").replace('\\','/')+"\"\n"+WaitlistProcessRecoveryRunner.class.getName()
            +"\n"+command+"\n\""+folder.toString().replace('\\','/')+"\"\n"+now+"\n");
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+args).redirectErrorStream(true).redirectOutput(log.toFile());
        String url=mysql.getJdbcUrl();builder.environment().put("M4_DB_URL",url+(url.contains("?")?"&":"?")+"connectTimeout=1000&socketTimeout=1000");
        builder.environment().put("M4_DB_USER",mysql.getUsername());builder.environment().put("M4_DB_PASSWORD",mysql.getPassword());
        return new RunningChild(builder.start(),log,args);
    }
    private record RunningChild(Process process,Path log,Path args) implements AutoCloseable {
        void expect(int exit) throws Exception {require(process.waitFor(60,TimeUnit.SECONDS),"child timeout: "+log);require(process.exitValue()==exit,"expected exit "+exit+" got "+process.exitValue()+": "+Files.readString(log));}
        void alive() throws Exception { require(process.isAlive(),"child exited prematurely: "+Files.readString(log)); }
        public void close() throws Exception {if(process.isAlive())process.destroyForcibly().waitFor();Files.deleteIfExists(args);}
    }
    private static Map<String,Object> awaitState(WaitlistRecoveryDatabase db,UUID tenant,Instant now,Predicate<Map<String,Object>> predicate,RunningChild child) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);Map<String,Object> snapshot;
        do {child.alive();snapshot=db.snapshot(tenant,now);if(predicate.test(snapshot))return snapshot;Thread.sleep(50);}while(System.nanoTime()<end);
        write(child.log().resolveSibling("failed-recovery.json"),snapshot);throw new IllegalStateException("recovery timed out: "+snapshot.get("stats")+" / "+child.log());
    }
    private static void awaitFile(Path path,RunningChild child) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(!Files.exists(path)){child.alive();require(System.nanoTime()<end,"gate timeout: "+path);Thread.sleep(25);}}
    private static void waitFile(Path path) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);while(!Files.exists(path)){require(System.nanoTime()<end,"gate timeout");Thread.sleep(25);}}
    private static String databaseFailure(Throwable failure) {for(Throwable e=failure;e!=null;e=e.getCause())if(e instanceof SQLException||e instanceof DataAccessException)return e.getClass().getName();return null;}
    private static long attempt(Map<String,Object> s) {return ((Number)list(s,"event_deliveries").getFirst().get("cycle_attempts")).longValue();}
    private static void single(Map<String,Object> s) { require(drained(s)&&stat(s,"event_records")==1&&stat(s,"done")==1&&stat(s,"promoted")==1
        &&stat(s,"waitlist_offers")==1&&stat(s,"waitlist_notification_requests")==1&&stat(s,"reservations")==2
        &&stat(s,"capacity_allocations")==2&&stat(s,"activeAllocations")==1&&stat(s,"offered")==1,"single event effect not atomic"); }
    static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    static void write(Path path,Object value) throws Exception {Files.writeString(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)+"\n");}
    private static String git(String...args) throws Exception {var command=new ArrayList<>(List.of("git","-c","safe.directory=C:/dev/slotq"));command.addAll(List.of(args));
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);require(process.waitFor()==0,output);return output.strip();}
}
