package com.slotq.experiments.waitlist;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import com.slotq.SlotqApplication;
import com.slotq.events.application.*;
import com.slotq.events.persistence.*;
import com.slotq.integration.operations.*;
import com.slotq.integration.waitlist.*;
import com.slotq.observability.ProductTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static com.slotq.experiments.waitlist.WaitlistRecoveryDatabase.*;

/** #111 comparison adapter around the unchanged #105 public-command trace and raw oracle. */
public final class M5TransportComparisonRunner implements WaitlistBaselineRunner.WorkloadDriver, AutoCloseable {
    static final String WAITLIST="waitlist.promotion", OBSERVER="operations.event-observation";
    static final List<String> CONSUMERS=List.of(WAITLIST,OBSERVER);
    static final JsonMapper JSON=new JsonMapper();
    private final ConfigurableApplicationContext app;
    private final JdbcTemplate db;
    private final String transport;
    private final List<EventDeliveryWorker> workers=new ArrayList<>();
    private final List<KafkaIntakeRuntime> intakes=new ArrayList<>();
    private final Set<UUID> measuredTenants=new LinkedHashSet<>();
    private final List<Map<String,Object>> observations=new ArrayList<>(), cycles=new ArrayList<>();
    private final List<Map<String,Object>> resources=Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService execution;
    private final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor();
    private AdminClient admin;
    private KafkaTemplate<String,String> template;
    private KafkaRelayWorker relay;
    private String topic;

    M5TransportComparisonRunner(ConfigurableApplicationContext app,String transport,int replicas,String bootstrap) throws Exception {
        this.app=app; this.db=app.getBean(JdbcTemplate.class); this.transport=transport;
        execution=Executors.newFixedThreadPool(replicas*2);
        var catalog=app.getBean(KafkaConsumerCatalog.class);
        for(var consumer:catalog.consumers()) for(var route:consumer.routes()) {
            long active=db.queryForObject("SELECT COUNT(*) FROM event_registrations WHERE consumer_id=? AND event_type=? AND schema_version=? AND deactivation_boundary IS NULL",Long.class,route.consumerId(),route.eventType(),route.schemaVersion());
            require(active<=1,"ambiguous active registration");
            if(active==0) app.getBean(EventRegistrationService.class).activate(route);
        }
        long epoch=1;
        if(transport.equals("KAFKA")) {
            topic="slotq.waitlist.events.v1";
            admin=AdminClient.create(Map.of("bootstrap.servers",bootstrap));
            admin.createTopics(List.of(new NewTopic(topic,3,(short)1).configs(Map.of("retention.ms","86400000")))).all().get(30,TimeUnit.SECONDS);
            epoch=app.getBean(EventTransportCutover.class).complete("KAFKA",100).authorityEpoch();
            template=new KafkaRelayConfiguration().publicationTemplate(new KafkaRelayConfiguration.ClientSettings(bootstrap,"PLAINTEXT","","","",""));
            var ledger=app.getBean(KafkaPublicationLedger.class);
            relay=new KafkaRelayWorker(ledger,template,app.getBean(WaitlistKafkaMessage.class),
                new KafkaPublicationPolicy(5,Duration.ofSeconds(30),Duration.ofSeconds(20),2,
                    List.of(Duration.ofSeconds(1),Duration.ofSeconds(5),Duration.ofSeconds(30),Duration.ofMinutes(2))),
                app.getBean(MeterRegistry.class),new KafkaRetentionProbe(admin,ledger),null,app.getBean(ProductTelemetry.class),topic);
        }
        for(String consumer:CONSUMERS) for(int i=0;i<replicas;i++) {
            var scope=new DeliveryExecutionScope(consumer,transport,epoch);
            var handlers=consumer.equals(WAITLIST)?new EventHandlers(List.of(app.getBean(BookingCapacityReleasedHandler.class),app.getBean(WaitlistPromotionRequestedHandler.class)))
                :new EventHandlers(List.of(app.getBean(BookingCapacityObservedHandler.class),app.getBean(PromotionRequestedObservedHandler.class)));
            workers.add(new EventDeliveryWorker(app.getBean(EventDeliveryStore.class),app.getBean(DeliveryTransactions.class),
                app.getBean(DeliveryPolicy.class),handlers,app.getBean(EventCanonicalizer.class),app.getBean(EntityManagerFactory.class),app.getBean(ProductTelemetry.class),scope));
            if(transport.equals("KAFKA")) {
                var family=new WaitlistKafkaMessage(consumer.equals(WAITLIST),false);
                var guard=new KafkaRuntimeGuard(db,family,catalog,scope,"consumer",false,true,true,false,consumer.equals(OBSERVER),"none");
                guard.run(null);
                intakes.add(new KafkaIntakeRuntime(app.getBean(JdbcKafkaIntakeStore.class),guard,catalog,app.getBean(MeterRegistry.class),
                    "consumer",consumer,"KAFKA",epoch,topic,bootstrap,"PLAINTEXT","","","","","0,1,2"));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path output=Path.of(System.getProperty("slotq.m5.output","build/reports/m5/"+UUID.randomUUID())).toAbsolutePath();
        if(System.getProperty("slotq.m5.recalculate")!=null) {
            M5TransportEvidence.recalculate(Path.of(System.getProperty("slotq.m5.recalculate"))); return;
        }
        require(!Files.exists(output),"fresh output required"); Files.createDirectories(output);
        String only=System.getProperty("slotq.m5.only","");
        int repeats=Integer.parseInt(System.getProperty("slotq.m5.repeats","3"));
        require(repeats>=3,"each comparison requires at least 3 repeats");
        for(int repetition=1;repetition<=repeats;repetition++) {
            var profiles=new ArrayList<>(List.of("DB_DIRECT-1","KAFKA-1","DB_DIRECT-3","KAFKA-3","KAFKA-3-extra"));
            if(repetition%2==0) Collections.reverse(profiles);
            for(String profile:profiles) if(only.isEmpty()||only.equals(profile)) run(output,profile,repetition);
        }
        M5TransportEvidence.recalculate(output);
    }

    private static void run(Path output,String profile,int repetition) throws Exception {
        String transport=profile.startsWith("DB")?"DB_DIRECT":"KAFKA";
        int replicas=profile.contains("-3")?3:1;
        boolean extra=profile.endsWith("extra");
        Path folder=output.resolve(profile+"-r"+repetition); Files.createDirectories(folder);
        try(var mysql=new MySQLContainer("mysql:8.4").withDatabaseName("slotq_m5")
                .withCommand("--log-bin-trust-function-creators=1")
                .withCreateContainerCmdModifier(c->c.getHostConfig().withNanoCPUs(transport.equals("DB_DIRECT")?1_500_000_000L:1_000_000_000L).withMemory((transport.equals("DB_DIRECT")?1536L:768L)*1024*1024));
            var kafka=new KafkaContainer("apache/kafka:4.1.1")
                .withCreateContainerCmdModifier(c->c.getHostConfig().withNanoCPUs(extra?1_500_000_000L:500_000_000L).withMemory((extra?1536L:768L)*1024*1024))) {
            mysql.start(); if(transport.equals("KAFKA")) kafka.start();
            try(var connection=java.sql.DriverManager.getConnection(mysql.getJdbcUrl(),"root",mysql.getPassword());var sql=connection.createStatement()) {
                sql.execute("CREATE DATABASE slotq_m5_warmup"); sql.execute("GRANT ALL ON slotq_m5_warmup.* TO '"+mysql.getUsername()+"'@'%'");
                sql.execute("GRANT SELECT ON performance_schema.* TO '"+mysql.getUsername()+"'@'%'");
                sql.execute("GRANT PROCESS ON *.* TO '"+mysql.getUsername()+"'@'%'");
            }
            try(var warmApp=application(mysql,mysql.getJdbcUrl().replace("/slotq_m5","/slotq_m5_warmup"));
                var warmDriver=new M5TransportComparisonRunner(warmApp,transport,replicas,transport.equals("KAFKA")?kafka.getBootstrapServers():"")) {
                var warmup=new WaitlistBaselineRunner(warmApp,10501,warmDriver);
                Map<String,Object> warm=warmup.execute(Map.of("purpose","unmeasured full trace warm-up in separate fixture database"));
                write(folder.resolve("warmup.json"),warm);
            }
            // Fresh broker topic/groups avoid carrying warm-up coordinates into measured original authority.
            if(transport.equals("KAFKA")) {
                try(var admin=AdminClient.create(Map.of("bootstrap.servers",kafka.getBootstrapServers()))) {
                    admin.deleteTopics(List.of("slotq.waitlist.events.v1")).all().get(30,TimeUnit.SECONDS);
                    for(String group:List.of("slotq.waitlist.promotion.v1","slotq.operations.event-observation.v1")) {
                        try {admin.deleteConsumerGroups(List.of(group)).all().get(10,TimeUnit.SECONDS);} catch(Exception absent) { }
                    }
                }
            }
            long startup=System.nanoTime();
            try(var app=application(mysql,mysql.getJdbcUrl());
                var driver=new M5TransportComparisonRunner(app,transport,replicas,transport.equals("KAFKA")?kafka.getBootstrapServers():"")) {
                long ready=System.nanoTime();
                driver.startResourceSampling(mysql.getContainerId(),transport.equals("KAFKA")?kafka.getContainerId():null);
                var runner=new WaitlistBaselineRunner(app,10501,driver);
                var manifest=runner.manifest(mysql,10501,startup,ready);
                manifest.put("comparison",Map.of("profile",profile,"repetition",repetition,"logicalConsumers",CONSUMERS,"replicasPerConsumer",replicas,
                    "budget",extra?"additional allocation: MySQL 1 CPU/768 MiB + Kafka 1.5 CPU/1536 MiB":"equal total container caps 1.5 CPU/1536 MiB: DB MySQL receives all; Kafka MySQL 1 CPU/768 MiB + broker 0.5 CPU/768 MiB; same 768 MiB harness JVM",
                    "processLayout","Product, relay and both scoped consumers in one bounded harness JVM; process isolation measured separately in drill",
                    "warmup","one complete #105 trace plus steady-load extension in separate fixture database; measured context/schema fresh, same JVM/MySQL/broker engine warmed",
                    "steadyLoad","24 sequential request/drain slots; closed-loop finite workload, coordinated omission possible; no saturation claim",
                    "telemetry","same ProductTelemetry and MeterRegistry; no remote exporter in either comparison"));
                manifest.put("broker",transport.equals("KAFKA")?driver.brokerManifest(kafka):Map.of("status","not applicable"));
                manifest.put("containerRoles",transport.equals("KAFKA")?Map.of(mysql.getContainerId(),"mysql",kafka.getContainerId(),"kafka"):Map.of(mysql.getContainerId(),"mysql"));
                manifest.put("kafkaClientVersion",org.apache.kafka.common.utils.AppInfoParser.getVersion());
                manifest.put("storageNetwork",Map.of("hostVolume",System.getProperty("slotq.m5.hostStorage","unavailable: caller must record actual host storage"),"dockerStorage",DockerClientFactory.instance().client().infoCmd().exec().getDriver(),"network","single host + container NAT; no emulated latency/bandwidth limit; host/VM network shared","fixtureObservationPermissions","same fixture writer account has performance_schema SELECT + PROCESS for read-only sampling; production telemetry uses dedicated read-only account"));
                var workload=new LinkedHashMap<>((Map<String,Object>)manifest.get("workload"));
                workload.remove("logicalConsumer");workload.put("logicalConsumers",CONSUMERS);workload.put("workerCount",2*replicas);workload.put("transport",transport);workload.put("phases",List.of("normal","hot-slot","independent-slots","steady-load","backlog","idle","duplicate-verification"));manifest.put("workload",workload);
                var runtime=new LinkedHashMap<>((Map<String,Object>)manifest.get("runtime"));runtime.put("scheduler","explicit scoped concurrent cycles per consumer; production guards and transactional executor retained");runtime.put("warmup","complete unmeasured trace in separate database");manifest.put("runtime",runtime);
                var times=new LinkedHashMap<>((Map<String,Object>)manifest.get("time"));times.put("brokerAck",transport.equals("KAFKA")?"actual producer ACK; publication ack_at is in-transaction persistence timestamp":"not applicable");times.put("durableIntake",transport.equals("KAFKA")?"MySQL durable target handoff; distinct from business DONE":"DB materialization, no Kafka handoff");manifest.put("time",times);
                manifest.put("jvm",Map.of("pid",ProcessHandle.current().pid(),"maxHeapBytes",Runtime.getRuntime().maxMemory(),"arguments",ManagementFactory.getRuntimeMXBean().getInputArguments()));
                var raw=new LinkedHashMap<>(runner.execute(manifest));
                raw.put("distribution",Map.of("observations",driver.observations,"cycles",driver.cycles,"resources",List.copyOf(driver.resources)));
                write(folder.resolve("raw.json"),raw); write(folder.resolve("manifest.json"),manifest);
                write(folder.resolve("summary.json"),M5TransportEvidence.summarize(raw));
                WaitlistBaselineEvidence.writeCsv(folder.resolve("correspondence.csv"),raw);
                System.out.println("#111 measured "+profile+" repeat "+repetition+": "+folder);
            }
        }
    }

    static ConfigurableApplicationContext application(MySQLContainer mysql,String jdbcUrl) {
        return new SpringApplicationBuilder(SlotqApplication.class,WaitlistBaselineRunner.Configuration.class).run(
            "--server.port=0","--logging.level.root=ERROR","--spring.main.banner-mode=off",
            "--spring.datasource.url="+jdbcUrl,"--spring.datasource.username="+mysql.getUsername(),"--spring.datasource.password="+mysql.getPassword(),
            "--spring.datasource.hikari.maximum-pool-size=10","--slotq.waitlist.promotion.enabled=true","--slotq.waitlist.promotion.maintenance-enabled=true",
            "--slotq.events.delivery.scheduler-enabled=true","--slotq.events.delivery.batch-size=2",
            "--slotq.waitlist.promotion.discovery-batch-size=2","--slotq.waitlist.promotion.maintenance-batch-size=2");
    }

    @Override public int runCycle() {
        long start=System.nanoTime();
        int published=relay==null?0:relay.runCycle();
        try {
            var intakeTasks=intakes.stream().<Callable<Void>>map(i->()->{i.runCycle();return null;}).toList();
            for(var f:execution.invokeAll(intakeTasks)) f.get();
            var tasks=workers.stream().<Callable<Integer>>map(w->w::runCycle).toList();
            var results=new ArrayList<Integer>(); for(var f:execution.invokeAll(tasks)) results.add(f.get());
            int waitlist=results.subList(0,results.size()/2).stream().mapToInt(Integer::intValue).sum();
            int observer=results.subList(results.size()/2,results.size()).stream().mapToInt(Integer::intValue).sum();
            cycles.add(Map.of("startNanos",start,"endNanos",System.nanoTime(),"publishedAttempts",published,"waitlistClaims",waitlist,"observerClaims",observer));
            return waitlist;
        } catch(Exception failure) { throw new IllegalStateException("comparison execution failed",failure); }
    }

    @Override public Map<String,Object> waitlistView(Map<String,Object> full) {
        measuredTenants.add(UUID.fromString(full.get("tenantId").toString()));
        var view=new LinkedHashMap<>(full);
        var registrations=list(full,"registrations").stream().filter(r->WAITLIST.equals(r.get("consumer_id"))).toList();
        var ids=registrations.stream().map(r->r.get("registration_id")).collect(java.util.stream.Collectors.toSet());
        view.put("registrations",registrations);
        view.put("event_deliveries",list(full,"event_deliveries").stream().filter(d->ids.contains(d.get("registration_id"))).toList());
        var stats=new LinkedHashMap<>((Map<String,Long>)full.get("stats"));
        stats.put("event_deliveries",(long)list(view,"event_deliveries").size());
        stats.put("done",count(view,"event_deliveries","state","DONE"));
        stats.put("dead",count(view,"event_deliveries","state","DEAD"));
        stats.put("processing",count(view,"event_deliveries","state","PROCESSING")); view.put("stats",stats);
        return view;
    }

    @Override public void observe(String phase,long start,long end,Instant now) {
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("phase",phase); snapshot.put("startNanos",start); snapshot.put("workloadObservationEndNanos",end);
        snapshot.put("verificationNow",now.toString());
        snapshot.put("tenants",measuredTenants.stream().map(t->new WaitlistRecoveryDatabase(db).snapshot(t,now)).toList());
        for(String table:List.of("event_observation_projections","event_kafka_publications","event_kafka_target_intakes","event_kafka_intake_records")) {
            var rows=new ArrayList<Map<String,Object>>();
            for(UUID tenant:measuredTenants) rows.addAll(rows("SELECT * FROM "+table+" WHERE tenant_id=?",bytes(tenant)));
            snapshot.put(table,rows);
        }
        snapshot.put("assignments",rows("SELECT HEX(registration_id) registration_id,transport,authority_epoch FROM event_transport_assignments"));
        snapshot.put("quarantine",rows("SELECT consumer_id,topic,partition_id,record_offset,failure_code FROM event_kafka_intake_records WHERE disposition='QUARANTINED'"));
        snapshot.put("dbStatus",rows("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Innodb_row_lock_time','Innodb_row_lock_waits','Innodb_os_log_written','Bytes_received','Bytes_sent')"));
        snapshot.put("lockWaits",rows("SELECT b.OBJECT_NAME table_name,COUNT(*) waiting FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks b ON b.ENGINE=w.ENGINE AND b.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID WHERE b.OBJECT_SCHEMA=DATABASE() GROUP BY b.OBJECT_NAME LIMIT 100"));
        var pool=app.getBean(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean();
        snapshot.put("pool",Map.of("active",pool.getActiveConnections(),"idle",pool.getIdleConnections(),"total",pool.getTotalConnections(),"waiting",pool.getThreadsAwaitingConnection()));
        var acquire=app.getBean(MeterRegistry.class).find("hikaricp.connections.acquire").timer();
        snapshot.put("poolAcquire",acquire==null?Map.of("available",false):Map.of("count",acquire.count(),"totalSeconds",acquire.totalTime(TimeUnit.SECONDS),"maxSeconds",acquire.max(TimeUnit.SECONDS)));
        if(admin!=null) snapshot.put("broker",brokerState());
        snapshot.put("endNanos",System.nanoTime()); snapshot.put("hostEndAt",Instant.now().toString()); observations.add(snapshot);
    }

    Map<String,Object> brokerState() {
        try {
            var description=admin.describeTopics(List.of(topic)).allTopicNames().get(10,TimeUnit.SECONDS).get(topic);
            var partitions=new LinkedHashMap<TopicPartition,OffsetSpec>(); description.partitions().forEach(p->partitions.put(new TopicPartition(topic,p.partition()),OffsetSpec.latest()));
            var ends=admin.listOffsets(partitions).all().get(10,TimeUnit.SECONDS);
            var result=new LinkedHashMap<String,Object>(); result.put("topicId",description.topicId().toString());
            var nodes=admin.describeCluster().nodes().get(10,TimeUnit.SECONDS);
            var logs=admin.describeLogDirs(nodes.stream().map(n->n.id()).toList()).allDescriptions().get(10,TimeUnit.SECONDS);
            var storage=new ArrayList<Map<String,Object>>();for(var broker:logs.entrySet())for(var directory:broker.getValue().values()) {
                require(directory.error()==null,"broker storage sample unavailable");
                directory.replicaInfos().forEach((partition,replica)->storage.add(Map.of("brokerId",broker.getKey(),"topic",partition.topic(),"partition",partition.partition(),"bytes",replica.size())));
            }result.put("storage",storage);
            result.put("partitions",description.partitions().stream().map(p->Map.of("partition",p.partition(),"leader",p.leader().id(),"replicas",p.replicas().size(),"isr",p.isr().size(),"endOffset",ends.get(new TopicPartition(topic,p.partition())).offset())).toList());
            var groups=new LinkedHashMap<String,Object>();
            for(String consumer:CONSUMERS) {
                String group=app.getBean(KafkaConsumerCatalog.class).definition(consumer).groupId();
                var offsets=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS);
                org.apache.kafka.clients.admin.ConsumerGroupDescription states;
                try {states=admin.describeConsumerGroups(List.of(group)).all().get(10,TimeUnit.SECONDS).get(group);}
                catch(java.util.concurrent.ExecutionException absent) {
                    if(absent.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException) {groups.put(consumer,Map.of("group",group,"state","NOT_CREATED","healthy",false,"semantics","pre-worker group/offset state unavailable; not zero lag"));continue;}
                    throw absent;
                }
                groups.put(consumer,Map.of("group",group,"state",states.groupState().toString(),"members",states.members().size(),"memberPartitionCounts",states.members().stream().map(m->m.assignment().topicPartitions().size()).toList(),"partitions",ends.entrySet().stream().map(e->Map.of("partition",e.getKey().partition(),"endOffset",e.getValue().offset(),"committedNext",offsets.containsKey(e.getKey())?offsets.get(e.getKey()).offset():0L,"lag",e.getValue().offset()-(offsets.containsKey(e.getKey())?offsets.get(e.getKey()).offset():0L))).toList()));
            }
            result.put("groups",groups); return result;
        } catch(Exception failure) { throw new IllegalStateException("broker observation unavailable",failure); }
    }

    private Map<String,Object> brokerManifest(KafkaContainer kafka) {
        var inspect=DockerClientFactory.instance().client().inspectContainerCmd(kafka.getContainerId()).exec();
        return Map.of("imageId",inspect.getImageId(),"memoryBytes",inspect.getHostConfig().getMemory(),"nanoCpus",inspect.getHostConfig().getNanoCPUs(),"profile","single local broker, RF=1, 3 partitions, 24h retention; HA evidence separately owned by #109","bootstrap",kafka.getBootstrapServers());
    }

    private void startResourceSampling(String mysql,String broker) {
        sampler.scheduleWithFixedDelay(()->{
            try {
                var command=new ArrayList<>(List.of(System.getenv().getOrDefault("SLOTQ_DOCKER_EXE","docker"),"stats","--no-stream","--format","{{json .}}",mysql)); if(broker!=null)command.add(broker);
                var process=new ProcessBuilder(command).redirectErrorStream(true).start();
                String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                if(process.waitFor(10,TimeUnit.SECONDS)&&process.exitValue()==0) {
                    var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
                    var roles=new LinkedHashMap<String,String>();roles.put(mysql.substring(0,12),"mysql");if(broker!=null)roles.put(broker.substring(0,12),"kafka");
                    resources.add(Map.of("observedAt",Instant.now().toString(),"nanos",System.nanoTime(),"containerRoles",roles,"containerStatsJsonLines",output,
                        "jvmCpuNanos",os.getProcessCpuTime(),"heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),"hostCpuLoad",os.getCpuLoad()));
                } else { process.destroyForcibly(); resources.add(Map.of("observedAt",Instant.now().toString(),"unavailable",true)); }
            } catch(Exception failure) { resources.add(Map.of("observedAt",Instant.now().toString(),"unavailable",true)); }
        },0,3,TimeUnit.SECONDS);
    }

    List<Map<String,Object>> rows(String sql,Object...args) {
        return db.query(sql,(r,n)->{var row=new LinkedHashMap<String,Object>();var meta=r.getMetaData();for(int i=1;i<=meta.getColumnCount();i++) {Object v=r.getObject(i); if(v instanceof byte[] b)v=HexFormat.of().formatHex(b);else if(v instanceof java.time.LocalDateTime t)v=t.toInstant(ZoneOffset.UTC).toString();else if(v instanceof java.sql.Timestamp t)v=t.toInstant().toString();row.put(meta.getColumnLabel(i),v);}return (Map<String,Object>)row;},args);
    }
    static void write(Path file,Object value) throws Exception { Files.writeString(file,JSON.writeValueAsString(value)+"\n"); }
    static void require(boolean condition,String message) { if(!condition)throw new IllegalStateException(message); }
    @Override public void close() {
        sampler.shutdownNow(); for(var intake:intakes)intake.close(); execution.shutdownNow();
        if(template!=null)((org.springframework.kafka.core.DefaultKafkaProducerFactory<?,?>)template.getProducerFactory()).destroy();
        if(admin!=null)admin.close(Duration.ofSeconds(5));
    }
}
