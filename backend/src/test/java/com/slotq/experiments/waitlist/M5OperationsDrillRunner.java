package com.slotq.experiments.waitlist;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.net.ssl.*;
import com.slotq.SlotqApplication;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.*;
import com.slotq.auth.web.BearerCredentialResolver;
import com.slotq.booking.application.*;
import com.slotq.booking.domain.*;
import com.slotq.events.application.*;
import com.slotq.events.persistence.EventTransportCutover;
import com.slotq.integration.waitlist.*;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import com.slotq.waitlist.application.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.core.type.TypeReference;
import static com.slotq.experiments.waitlist.M5TransportComparisonRunner.*;
import static com.slotq.experiments.waitlist.M5TransportEvidence.*;
import static com.slotq.experiments.waitlist.WaitlistRecoveryDatabase.*;

/** Fresh #111 representative drill: independent JVMs, HTTPS operator boundary and real Prometheus alerts. */
public final class M5OperationsDrillRunner {
    private final Path output,local;
    private final List<Map<String,Object>> timeline=new ArrayList<>();
    private final List<Role> roles=new ArrayList<>();
    private final Set<UUID> tenants=new LinkedHashSet<>();
    private final String monitor=secret("monitor_"),operator=secret("sqop_"),tlsPassword=secret("tls_"),product=secret("product_");
    private static AuthenticatedPrincipal productPrincipal;
    private static String productToken;
    private MySQLContainer mysql;
    private KafkaContainer kafka;
    private GenericContainer<?> prometheus;
    private GenericContainer<?> tempo,grafana;
    private com.sun.net.httpserver.HttpServer traceGateway;
    private final String ingest=secret("ingest_"),grafanaPassword=secret("grafana_");
    private ConfigurableApplicationContext app;
    private JdbcTemplate db;
    private AdminClient admin;
    private HttpClient http;
    private Path keyStore,certificate,handlerFault;
    private final Map<String,Integer> ports=new LinkedHashMap<>();
    private final int brokerPort;
    private long epoch=2;
    private String transport="KAFKA";
    private int serial;
    private UUID operatorId;

    private M5OperationsDrillRunner(Path output) throws Exception {
        this.output=output.toAbsolutePath();require(!Files.exists(this.output),"fresh drill output required");Files.createDirectories(this.output);
        local=Path.of("build","m5-drill",UUID.randomUUID().toString()).toAbsolutePath();Files.createDirectories(local);
        try(var socket=new java.net.ServerSocket(0)){brokerPort=socket.getLocalPort();}
        for(String role:List.of("product","waitlist","observer","relay"))try(var s=new java.net.ServerSocket(0)){ports.put(role,s.getLocalPort());}
    }
    public static void main(String[]args) throws Exception {
        if(System.getProperty("slotq.m5.recalculate")!=null){M5OperationsDrillEvidence.verify(Path.of(System.getProperty("slotq.m5.recalculate")));return;}
        var drill=new M5OperationsDrillRunner(Path.of(System.getProperty("slotq.m5.output","build/reports/m5-drill/"+UUID.randomUUID())));
        drill.run();
    }
    private void run() throws Exception {
        try(var mysql=new MySQLContainer("mysql:8.4").withDatabaseName("slotq_m5_drill").withCommand("--log-bin-trust-function-creators=1");
            var kafka=new KafkaContainer("apache/kafka:4.1.1")) {
            this.mysql=mysql;this.kafka=kafka;
            // Docker stop/start reallocates an ephemeral host port, while Kafka advertises its
            // startup endpoint. Preserve the endpoint for this actual restart fixture.
            kafka.withCreateContainerCmdModifier(command->command.getHostConfig().withPortBindings(
                com.github.dockerjava.api.model.PortBinding.parse("127.0.0.1:"+brokerPort+":9092")));
            mysql.start();kafka.start();
            tls();
            app=new SpringApplicationBuilder(SlotqApplication.class).run(baseArgs("none",0));db=app.getBean(JdbcTemplate.class);
            for(var consumer:app.getBean(KafkaConsumerCatalog.class).consumers())for(var route:consumer.routes())app.getBean(EventRegistrationService.class).activate(route);
            admin=AdminClient.create(Map.of("bootstrap.servers",kafka.getBootstrapServers()));
            admin.createTopics(List.of(new NewTopic("slotq.waitlist.events.v1",3,(short)1).configs(Map.of("retention.ms","86400000")))).all().get();
            app.getBean(EventTransportCutover.class).complete("KAFKA",100);app.close();app=null;
            observerCredential();startTraceCollector();startProduct();startPrometheus();startGrafana();
            Role relay=start("relay",null),waitlist=start("waitlist",null),observer=start("observer",null);
            Fixture first=fixture();UUID healthy=request(first);converge(healthy);
            capture("healthy",healthy);dashboardEvidence();traceEvidence(healthy);
            require(call("GET",ports.get("waitlist"),"/actuator/prometheus",operator,null).statusCode()==401,"operator token accepted for machine scrape");
            require(call("GET",ports.get("waitlist"),"/api/v1/venues/"+first.venue.id().value(),product,null).statusCode()==404,"isolated role exposed Product HTTP");
            require(call("GET",ports.get("waitlist"),"/internal/operations/tenants/"+first.tenant.id().value(),operator,null).statusCode()==404,"isolated role exposed recovery HTTP");
            record("metrics-only-network-boundary",Map.of("validMonitorScrape",200,"humanOnScrape",401,"productOnConsumer",404,"operatorOnConsumer",404));

            observer.kill();record("observer-stop",Map.of());Fixture isolated=fixture();UUID observerGap=request(isolated);
            await("Waitlist independent of observer",90,()->done(observerGap,WAITLIST));bookingHttp(isolated,"observer-down");
            alert("SlotqExecutionRuntimeUnavailable","slotq-observer",true);capture("observer-down-waitlist-done",observerGap);
            observer=start("observer",null);converge(observerGap);alert("SlotqExecutionRuntimeUnavailable","slotq-observer",false);capture("observer-recovered",observerGap);

            handlerFault=local.resolve("handler-fault");Files.writeString(handlerFault,"controlled incompatible handler");waitlist.kill();waitlist=start("waitlist",handlerFault);
            Fixture broken=fixture();UUID dead=request(broken);record("waitlist-fault-input",Map.of("eventId",dead.toString()));
            await("Waitlist DEAD and observer DONE",90,()->dead(dead)&&done(dead,OBSERVER));bookingHttp(broken,"waitlist-dead");
            alert("SlotqConsumerDeadDelivery","slotq-waitlist",true);capture("waitlist-dead-observer-done",dead);
            recover(broken,dead,handlerFault,"","SlotqConsumerDeadDelivery");

            docker("stop",kafka.getContainerId());record("broker-stop",Map.of("containerId",kafka.getContainerId()));
            Fixture unavailable=fixture();UUID duringBroker=request(unavailable);bookingHttp(unavailable,"broker-down");
            alert("SlotqKafkaLagUnavailable","slotq-waitlist",true);capture("broker-unavailable",duringBroker);
            docker("start",kafka.getContainerId());
            String restartedPort=DockerClientFactory.instance().client().inspectContainerCmd(kafka.getContainerId()).exec().getNetworkSettings().getPorts().getBindings().get(new com.github.dockerjava.api.model.ExposedPort(9092))[0].getHostPortSpec();
            require(Integer.parseInt(restartedPort)==brokerPort,"broker restart changed advertised host port");record("broker-start",Map.of("sameContainer",true,"stableBootstrap",kafka.getBootstrapServers(),"inspectedHostPort",restartedPort));
            waitlist.kill();observer.kill();relay.kill();relay=start("relay",null);waitlist=start("waitlist",null);observer=start("observer",null);
            converge(duringBroker);alert("SlotqKafkaLagUnavailable","slotq-waitlist",false);capture("broker-recovered",duringBroker);

            relay.kill();Path ack=local.resolve("ack-crash.json");Role crashed=start("relay",ack);
            Fixture ambiguous=fixture();UUID unknown=request(ambiguous);
            await("ACK-before-ledger process death",90,()->Files.exists(ack)&&!crashed.process.isAlive());
            var ackEvidence=JSON.readValue(Files.readString(ack),new TypeReference<Map<String,Object>>(){});
            require(crashed.process.exitValue()==111,"unexpected publication fault exit");
            record("publication-ack-unknown",Map.of("actualAck",ackEvidence,"exitCode",111));
            alert("SlotqPublicationClaimExpired","slotq-product",true);capture("publication-unknown-expired-lease",unknown);
            relay=start("relay",null);converge(unknown);
            await("physical publication redelivery absorbed by both consumers",60,()->db.queryForObject("SELECT COUNT(*) FROM event_kafka_intake_records WHERE event_id=?",Integer.class,bytes(unknown))>=4);
            alert("SlotqPublicationClaimExpired","slotq-product",false);capture("publication-redelivery-converged",unknown);

            // Original intake remains durable when the scoped executor JVM is absent.
            waitlist.kill();Path heldEffect=local.resolve("held-effect");Files.writeString(heldEffect,"hold");waitlist=start("waitlist",heldEffect);
            Fixture executorGap=fixture();UUID durable=request(executorGap);
            await("two durable intakes and held Waitlist executor",90,()->done(durable,OBSERVER)&&db.queryForObject("SELECT COUNT(*) FROM event_kafka_target_intakes WHERE event_id=?",Integer.class,bytes(durable))==2&&db.queryForObject("SELECT COUNT(*) FROM event_deliveries WHERE event_id=? AND state='PROCESSING'",Integer.class,bytes(durable))==1);
            capture("before-db-outage",durable);
            docker("pause",mysql.getContainerId());record("database-pause",Map.of("containerId",mysql.getContainerId()));
            try {
                alert("SlotqDatabaseSampleUnavailable","slotq-product",true);
                var unavailableState=call("GET",ports.get("product"),"/actuator/prometheus",monitor,null);require(unavailableState.statusCode()==200,"scrape blocked by DB outage");
                require(unavailableState.body().contains("slotq_observation_sample_healthy{sample=\"events\"} 0.0")||unavailableState.body().lines().anyMatch(s->s.startsWith("slotq_observation_sample_healthy")&&s.contains("sample=\"events\"")&&s.endsWith(" 0.0")),"DB outage represented as healthy zero");
                var sqlFailures=new ArrayList<Map<String,Object>>();for(Role role:roles)if(Files.exists(local.resolve(role.label+".log"))) {
                    String log=Files.readString(local.resolve(role.label+".log"));boolean executor=log.contains("EventDeliveryWorker.runCycle");
                    var failures=List.of("SQLTransientConnectionException","CommunicationsException","SQLException").stream().filter(log::contains).toList();
                    if(executor&&!failures.isEmpty())sqlFailures.add(Map.of("role",role.role,"pid",role.process.pid(),"entrypoint","EventDeliveryWorker.runCycle","exceptionClasses",failures));
                }
                require(!sqlFailures.isEmpty(),"no actual executor JDBC failure observed during DB pause");
                record("database-outage-observed",Map.of("scrapeStatus",200,"durableRead","unavailable; no zero state substituted","healthyEvents",false,"runtimeSqlFailures",sqlFailures));
            } finally {docker("unpause",mysql.getContainerId());record("database-unpause",Map.of());}
            Files.delete(heldEffect);waitlist.kill();observer.kill();relay.kill();relay=start("relay",null);observer=start("observer",null);waitlist=start("waitlist",null);
            converge(durable);alert("SlotqDatabaseSampleUnavailable","slotq-product",false);capture("database-recovered",durable);

            // Quiesce Product writers and every relay/intake/executor before the production cutover command.
            for(Role role:roles)role.kill();app.close();app=null;
            try(var maintenance=new SpringApplicationBuilder(SlotqApplication.class).run(cutoverArgs())) {
                db=maintenance.getBean(JdbcTemplate.class);capture("quiesced-before-rollback",durable);
                var result=maintenance.getBean(EventTransportCutover.class).complete("DB_DIRECT",2);
                epoch=result.authorityEpoch();transport="DB_DIRECT";record("manual-kafka-db-rollback",Map.of("result",result,"quiesced",true));capture("quiesced-after-rollback",durable);
            }
            startProduct();waitlist=start("waitlist",null);observer=start("observer",null);
            Fixture direct=fixture();UUID afterRollback=request(direct);converge(afterRollback);bookingHttp(direct,"db-direct-after-rollback");capture("db-direct-after-rollback",afterRollback);
            observer.kill();record("db-observer-stop",Map.of());Fixture dbIsolated=fixture();UUID dbObserverGap=request(dbIsolated);
            await("DB Waitlist independent of scoped observer",90,()->done(dbObserverGap,WAITLIST));bookingHttp(dbIsolated,"db-observer-down");
            alert("SlotqExecutionRuntimeUnavailable","slotq-observer",true);capture("db-observer-down-waitlist-done",dbObserverGap);
            observer=start("observer",null);converge(dbObserverGap);alert("SlotqExecutionRuntimeUnavailable","slotq-observer",false);capture("db-observer-recovered",dbObserverGap);
            Path dbFault=local.resolve("db-handler-fault");Files.writeString(dbFault,"controlled incompatible handler");waitlist.kill();waitlist=start("waitlist",dbFault);
            Fixture dbBroken=fixture();UUID dbDead=request(dbBroken);record("db-waitlist-fault-input",Map.of("eventId",dbDead.toString()));
            await("DB Waitlist DEAD and observer DONE",90,()->dead(dbDead)&&done(dbDead,OBSERVER));bookingHttp(dbBroken,"db-waitlist-dead");
            alert("SlotqDeadDelivery","slotq-waitlist",true);capture("db-waitlist-dead-observer-done",dbDead);
            recover(dbBroken,dbDead,dbFault,"db-","SlotqDeadDelivery");capture("db-direct-converged",dbDead);
            var manifest=new LinkedHashMap<String,Object>();manifest.put("schemaVersion","slotq-m5-drill/v1");manifest.put("revision",gitRevision());manifest.put("java",System.getProperty("java.runtime.version"));manifest.put("mysql",db.queryForMap("SELECT VERSION() version,@@transaction_isolation isolation_level"));manifest.put("broker",Map.of("image","apache/kafka:4.1.1","imageId",DockerClientFactory.instance().client().inspectContainerCmd(kafka.getContainerId()).exec().getImageId(),"partitions",3,"replicationFactor",1,"retentionMs",86400000));manifest.put("prometheus","prom/prometheus:v3.5.0; 1s local scrape/evaluation; checked TLS certificate and bearer credential");manifest.put("roles",ports.keySet());manifest.put("layout","Product coordinator and independent relay/Waitlist/observer JVMs on one host, shared MySQL; explicit process restarts only");manifest.put("faults","test-only handler advice and ACK-before-ledger halt; actual broker stop/start and MySQL pause/unpause; no business SQL recovery or offset manipulation");manifest.put("retryBudget",5);manifest.put("limitations",List.of("local single broker, not production/HA/SLO evidence","outage DB snapshots unavailable; before/after durable snapshots retained","representative integration, not a repetition of #109 fault matrix"));
            var hashes=new TreeMap<String,String>();for(String file:List.of("M5DrillRole.java","M5OperationsDrillRunner.java","M5OperationsDrillEvidence.java","M5CanonicalEvidence.java"))hashes.put(file,M5CanonicalEvidence.sha(Files.readAllBytes(Path.of("src/test/java/com/slotq/experiments/waitlist",file))));manifest.put("harnessSourceSha256",hashes);
            manifest.put("jvmBudgets",Map.of("coordinatorHeapBytes",Runtime.getRuntime().maxMemory(),"isolatedRoleHeapMiB",384,"mysqlAndBrokerCaps","no CPU/RAM caps for drill; comparison has distinct fixed caps; no comparative latency claim"));
            var observationHashes=new TreeMap<String,String>();for(String file:List.of("alerts.yml","tempo.yml","grafana/dashboards/slotq-product.json"))observationHashes.put(file,M5CanonicalEvidence.sha(Files.readAllBytes(Path.of("../infra/observability",file))));manifest.put("observationConfigurationSha256",observationHashes);
            manifest.put("timerAdapter","Product maintenance/local execution timers held by test adapter in both modes; isolated consumer timers are production. DB Product normally requires its local Waitlist scheduler, so producer-only DB deployment is not asserted.");
            write(output.resolve("manifest.json"),manifest);var dictionary=new TreeMap<String,Object>();var encoded=M5CanonicalEvidence.encode(Map.of("timeline",timeline),dictionary);
            M5CanonicalEvidence.writeRaw(output,Map.of("schemaVersion","slotq-m5-canonical/v1","manifestSha256",M5CanonicalEvidence.sha(Files.readAllBytes(output.resolve("manifest.json"))),"rows",dictionary,"data",encoded));
            write(output.resolve("summary.json"),M5OperationsDrillEvidence.summarize(Map.of("timeline",timeline)));M5OperationsDrillEvidence.verify(output);
        } finally {
            for(Role role:roles)role.kill();if(app!=null)app.close();if(admin!=null)admin.close();if(grafana!=null)grafana.close();if(prometheus!=null)prometheus.close();if(traceGateway!=null)traceGateway.stop(0);if(tempo!=null)tempo.close();
            if(!M5CanonicalEvidence.hasRaw(output))write(local.resolve("failed-timeline.json"),Map.of("timeline",timeline));
        }
    }
    private void tls() throws Exception {
        keyStore=local.resolve("tls.p12");certificate=local.resolve("tls.pem");String keytool=Path.of(System.getProperty("java.home"),"bin","keytool").toString();
        var generate=new ProcessBuilder(keytool,"-genkeypair","-alias","drill","-keyalg","RSA","-validity","2","-storetype","PKCS12","-keystore",keyStore.toString(),"-storepass:env","SLOTQ_TLS_PASSWORD","-dname","CN=localhost","-ext","SAN=dns:localhost,dns:host.testcontainers.internal,ip:127.0.0.1").redirectErrorStream(true).redirectOutput(local.resolve("keytool.log").toFile());generate.environment().put("SLOTQ_TLS_PASSWORD",tlsPassword);require(generate.start().waitFor()==0,"TLS fixture generation failed");
        var export=new ProcessBuilder(keytool,"-exportcert","-rfc","-alias","drill","-keystore",keyStore.toString(),"-storepass:env","SLOTQ_TLS_PASSWORD","-file",certificate.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(local.resolve("keytool.log").toFile()));export.environment().put("SLOTQ_TLS_PASSWORD",tlsPassword);require(export.start().waitFor()==0,"TLS fixture export failed");
        var ks=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(keyStore)){ks.load(in,tlsPassword.toCharArray());}var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(ks);var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);http=HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(3)).build();
    }
    private String[] baseArgs(String web,int port) {
        return new String[]{"--spring.main.web-application-type="+web,"--server.port="+port,"--spring.main.banner-mode=off","--logging.level.root=ERROR","--spring.datasource.url="+mysql.getJdbcUrl(),"--spring.datasource.username="+mysql.getUsername(),"--spring.datasource.password="+mysql.getPassword(),"--spring.datasource.hikari.maximum-pool-size=10","--spring.datasource.hikari.connection-timeout=3000","--spring.datasource.hikari.data-source-properties.socketTimeout=3000"};
    }
    private void observerCredential() throws Exception {
        try(var c=java.sql.DriverManager.getConnection(mysql.getJdbcUrl(),"root",mysql.getPassword());var s=c.createStatement()) {
            s.execute("CREATE USER 'm5_observer'@'%' IDENTIFIED BY '"+monitor+"'");s.execute("GRANT SELECT ON slotq_m5_drill.* TO 'm5_observer'@'%'");s.execute("GRANT SELECT ON performance_schema.* TO 'm5_observer'@'%'");s.execute("GRANT PROCESS ON *.* TO 'm5_observer'@'%'");
        }
    }
    private List<String> management() {
        return List.of("--server.ssl.enabled=true","--server.ssl.key-store="+keyStore.toUri(),"--server.ssl.key-store-password="+tlsPassword,"--server.ssl.key-store-type=PKCS12","--slotq.observability.scrape-token="+monitor,"--slotq.observability.database.enabled=true","--slotq.observability.database.jdbc-url="+mysql.getJdbcUrl(),"--slotq.observability.database.username=m5_observer","--slotq.observability.database.password="+monitor,"--slotq.observability.database.interval=PT1S","--slotq.observability.database.stale-after=PT3S","--slotq.telemetry.otlp-endpoint=http://localhost:"+traceGateway.getAddress().getPort()+"/v1/traces","--slotq.telemetry.otlp-authorization=Bearer "+ingest);
    }
    private void startProduct() {
        productToken=product;var args=new ArrayList<>(List.of(baseArgs("servlet",ports.get("product"))));args.addAll(management());args.addAll(List.of("--slotq.operations.recovery.enabled=true","--slotq.waitlist.promotion.enabled=true","--slotq.waitlist.promotion.maintenance-enabled=true","--slotq.events.kafka.business-enabled="+transport.equals("KAFKA"),"--slotq.events.delivery.scheduler-enabled="+transport.equals("DB_DIRECT"),"--slotq.events.delivery.authority-epoch="+epoch));
        app=new SpringApplicationBuilder(SlotqApplication.class,ProductConfiguration.class).run(args.toArray(String[]::new));db=app.getBean(JdbcTemplate.class);
        if(productPrincipal==null){productPrincipal=new AuthenticatedPrincipal(PrincipalId.newId());app.getBean(AccessControlProvisioning.class).registerPrincipal(productPrincipal.principalId());}
    }
    private String[] cutoverArgs(){var args=new ArrayList<>(List.of(baseArgs("none",0)));args.add("--slotq.events.runtime-role=cutover");return args.toArray(String[]::new);}
    private Role start(String role,Path fault) throws Exception {
        String label=role+"-"+(++serial);boolean relay=role.equals("relay"),w=role.equals("waitlist");
        var args=new ArrayList<>(List.of("-Xmx384m","-cp",System.getProperty("java.class.path"),M5DrillRole.class.getName()));
        args.addAll(List.of(baseArgs("servlet",ports.get(role))));args.addAll(management());
        args.addAll(List.of("--slotq.events.runtime-role="+(relay?"relay":"consumer"),"--slotq.events.management-only=true","--slotq.events.kafka.relay-enabled="+relay,"--slotq.events.kafka.consumer-enabled="+(!relay&&transport.equals("KAFKA")),"--slotq.events.delivery.scheduler-enabled="+!relay,"--slotq.events.delivery.consumer-id="+(w?WAITLIST:OBSERVER),"--slotq.events.delivery.transport="+transport,"--slotq.events.delivery.authority-epoch="+epoch,"--slotq.waitlist.promotion.enabled="+w,"--slotq.operations.observation.enabled="+(!w&&!relay),"--spring.kafka.bootstrap-servers="+kafka.getBootstrapServers(),"--slotq.events.delivery.batch-size=2"));
        // Secret-bearing properties go through child environment, never the persisted argument file.
        var builder=new ProcessBuilder();var filtered=new ArrayList<String>();
        int secretIndex=0;
        for(String arg:args) if(arg.startsWith("--")&&(arg.contains("password=")||arg.contains("scrape-token=")||arg.contains("otlp-authorization="))) {String[]pair=arg.substring(2).split("=",2);String env="SLOTQ_DRILL_SECRET_"+(++secretIndex);builder.environment().put(env,pair[1]);filtered.add("--"+pair[0]+"=${"+env+"}");}else filtered.add(arg);
        Path argfile=local.resolve(label+".args");Files.writeString(argfile,filtered.stream().map(a->"\""+a.replace('\\','/')+"\"").collect(java.util.stream.Collectors.joining("\n")));
        builder.command(Path.of(System.getProperty("java.home"),"bin","java").toString(),"@"+argfile);builder.redirectErrorStream(true).redirectOutput(local.resolve(label+".log").toFile());
        if(fault!=null)builder.environment().put(relay?"SLOTQ_DRILL_ACK_CRASH":"SLOTQ_DRILL_HANDLER_FAULT",fault.toString());
        Role result=new Role(label,role,builder.start());roles.add(result);record("process-start",Map.of("label",label,"role",role,"pid",result.process.pid(),"transport",transport,"authorityEpoch",epoch));
        await("scoped runtime startup "+label,90,()->{try{return result.process.isAlive()&&call("GET",ports.get(role),"/actuator/prometheus",monitor,null).statusCode()==200;}catch(Exception e){return false;}});return result;
    }
    private void startPrometheus() throws Exception {
        Testcontainers.exposeHostPorts(ports.values().stream().mapToInt(Integer::intValue).toArray());
        StringBuilder cfg=new StringBuilder("global:\n  scrape_interval: 1s\n  evaluation_interval: 1s\nrule_files: ['/etc/prometheus/alerts.yml']\nscrape_configs:\n");
        for(var p:ports.entrySet())cfg.append("  - job_name: slotq-").append(p.getKey()).append("\n    metrics_path: /actuator/prometheus\n    scheme: https\n    authorization:\n      credentials: '").append(monitor).append("'\n    tls_config:\n      ca_file: /etc/prometheus/tls.pem\n    static_configs:\n      - targets: ['host.testcontainers.internal:").append(p.getValue()).append("']\n");
        Path file=local.resolve("prometheus.yml");Files.writeString(file,cfg);
        prometheus=new GenericContainer<>("prom/prometheus:v3.5.0").withExposedPorts(9090).withCopyFileToContainer(MountableFile.forHostPath(file),"/etc/prometheus/prometheus.yml").withCopyFileToContainer(MountableFile.forHostPath(certificate),"/etc/prometheus/tls.pem").withCopyFileToContainer(MountableFile.forHostPath(Path.of("../infra/observability/alerts.yml")),"/etc/prometheus/alerts.yml");prometheus.start();
    }
    private void startTraceCollector() throws Exception {
        tempo=new GenericContainer<>("grafana/tempo:2.8.2").withExposedPorts(3200,4318).withCommand("-config.file=/etc/tempo.yml").withCopyFileToContainer(MountableFile.forHostPath(Path.of("../infra/observability/tempo.yml")),"/etc/tempo.yml");tempo.start();
        traceGateway=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        traceGateway.createContext("/v1/traces",exchange->{try {
            if(!exchange.getRequestMethod().equals("POST")||!("Bearer "+ingest).equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(401,-1);return;}
            var request=HttpRequest.newBuilder(URI.create("http://"+tempo.getHost()+":"+tempo.getMappedPort(4318)+"/v1/traces")).timeout(Duration.ofSeconds(2)).header("Content-Type","application/x-protobuf").POST(HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofByteArray());exchange.sendResponseHeaders(response.statusCode(),response.body().length);exchange.getResponseBody().write(response.body());
        }catch(Exception failed){exchange.sendResponseHeaders(503,-1);}finally{exchange.close();}});traceGateway.start();
    }
    private void startGrafana() throws Exception {
        Testcontainers.exposeHostPorts(prometheus.getMappedPort(9090),tempo.getMappedPort(3200));
        Path datasource=local.resolve("datasources.yml"),dashboard=local.resolve("dashboards.yml");
        Files.writeString(datasource,"apiVersion: 1\ndatasources:\n  - name: Prometheus\n    type: prometheus\n    uid: slotq-prometheus\n    access: proxy\n    url: http://host.testcontainers.internal:"+prometheus.getMappedPort(9090)+"\n");
        Files.writeString(dashboard,"apiVersion: 1\nproviders:\n  - name: SlotQ\n    type: file\n    options:\n      path: /var/lib/grafana/dashboards\n");
        grafana=new GenericContainer<>("grafana/grafana:12.1.1").withExposedPorts(3000).withEnv("GF_SECURITY_ADMIN_USER","operator").withEnv("GF_SECURITY_ADMIN_PASSWORD",grafanaPassword).withEnv("GF_AUTH_ANONYMOUS_ENABLED","false").withCopyFileToContainer(MountableFile.forHostPath(datasource),"/etc/grafana/provisioning/datasources/slotq.yml").withCopyFileToContainer(MountableFile.forHostPath(dashboard),"/etc/grafana/provisioning/dashboards/slotq.yml").withCopyFileToContainer(MountableFile.forHostPath(Path.of("../infra/observability/grafana/dashboards/slotq-product.json")),"/var/lib/grafana/dashboards/slotq-product.json");grafana.start();
    }
    private void dashboardEvidence() throws Exception {
        String authorization="Basic "+Base64.getEncoder().encodeToString(("operator:"+grafanaPassword).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://"+grafana.getHost()+":"+grafana.getMappedPort(3000)+"/api/search")).header("Authorization",authorization).GET().build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());require(response.statusCode()==200,"Grafana authenticated dashboard query failed");var dashboards=JSON.readValue(response.body(),new TypeReference<List<Map<String,Object>>>(){});require(!dashboards.isEmpty(),"Grafana dashboard not provisioned");
        String query="slotq_kafka_delivery_targets";var proxy=HttpRequest.newBuilder(URI.create("http://"+grafana.getHost()+":"+grafana.getMappedPort(3000)+"/api/datasources/proxy/uid/slotq-prometheus/api/v1/query?query="+URLEncoder.encode(query,java.nio.charset.StandardCharsets.UTF_8))).header("Authorization",authorization).GET().build();
        var sample=object(HttpClient.newHttpClient().send(proxy,HttpResponse.BodyHandlers.ofString()),200);require(!maps(map(sample.get("data")).get("result")).isEmpty(),"Grafana scoped DB panel has no real series");record("dashboard-query",Map.of("dashboards",dashboards.stream().map(d->Map.of("uid",d.get("uid"),"title",d.get("title"))).toList(),"query",query,"sample",sample));
    }
    private void traceEvidence(UUID event) throws Exception {
        String origin=db.queryForObject("SELECT origin_trace_id FROM event_records WHERE event_id=?",String.class,bytes(event));
        final Map<String,Object>[]trace=new Map[]{null};await("Tempo linked event trace",30,()->{try {var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://"+tempo.getHost()+":"+tempo.getMappedPort(3200)+"/api/traces/"+origin)).header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)return false;trace[0]=JSON.readValue(response.body(),new TypeReference<Map<String,Object>>(){});return true;}catch(Exception failed){return false;}});
        record("trace-query",Map.of("eventId",event.toString(),"originTraceId",origin,"trace",trace[0]));
        // Handler/publication attempts are separate traces with links, not a fabricated parent chain.
        final Map<String,Object>[]search=new Map[]{null};
        await("Tempo event attempt search",30,()->{try {var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://"+tempo.getHost()+":"+tempo.getMappedPort(3200)+"/api/search?q="+URLEncoder.encode("{ span.slotq.event.id = \""+event+"\" }",java.nio.charset.StandardCharsets.UTF_8))).GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)return false;search[0]=JSON.readValue(response.body(),new TypeReference<Map<String,Object>>(){});return maps(search[0].getOrDefault("traces",List.of())).size()>=3;}catch(Exception failed){return false;}});
        var attempts=new ArrayList<Map<String,Object>>();for(var item:maps(search[0].get("traces")))if(!origin.equals(item.get("traceID"))) {
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://"+tempo.getHost()+":"+tempo.getMappedPort(3200)+"/api/traces/"+item.get("traceID"))).header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString());attempts.add(object(response,200));
        }
        record("linked-attempt-search",Map.of("eventId",event.toString(),"query","span.slotq.event.id","attemptTraces",attempts));
    }
    private void alert(String name,String job,boolean firing) throws Exception {
        long start=System.nanoTime();String first=Instant.now().toString();final Map<String,Object>[]sample=new Map[]{null};
        await("alert "+name+" "+firing,75,()->{try {var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://"+prometheus.getHost()+":"+prometheus.getMappedPort(9090)+"/api/v1/alerts")).timeout(Duration.ofSeconds(4)).GET().build(),HttpResponse.BodyHandlers.ofString());var root=JSON.readValue(response.body(),new TypeReference<Map<String,Object>>(){});var data=M5TransportEvidence.map(root.get("data"));var alerts=M5TransportEvidence.maps(data.get("alerts"));var match=alerts.stream().filter(a->{var labels=M5TransportEvidence.map(a.get("labels"));return name.equals(labels.get("alertname"))&&job.equals(labels.get("job"))&&"firing".equals(a.get("state"));}).findFirst();sample[0]=match.orElse(Map.of("state","inactive"));return match.isPresent()==firing;}catch(Exception e){return false;}});
        record("alert-transition",Map.of("alert",name,"job",job,"firing",firing,"awaitStartedAt",first,"observedAfterMs",(System.nanoTime()-start)/1_000_000.0,"sample",sample[0]));
    }
    private Fixture fixture() {
        Tenant t=app.getBean(TenantUseCase.class).createTenant();tenants.add(t.id().value());var hours=new EnumMap<DayOfWeek,DailyOperatingHours>(DayOfWeek.class);for(var day:DayOfWeek.values())hours.put(day,new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(18,0)));
        Venue v=app.getBean(VenueConfigurationUseCase.class).createVenue(new VenueConfigurationUseCase.CreateVenue(t.id(),"Drill fixture","UTC",new WeeklyOperatingHours(hours),new BookingPolicyTerms(30,5,20,10)));
        var r=app.getBean(ResourceUseCase.class).createResource(new ResourceUseCase.CreateResource(t.id(),v.id(),"Drill table",4));
        var slot=app.getBean(SlotInventoryUseCase.class).createSlot(new SlotInventoryUseCase.CreateSlot(t.id(),v.id(),r.id(),LocalDate.now(ZoneOffset.UTC).plusDays(1).atTime(11,0).toInstant(ZoneOffset.UTC).toString()));
        var customer=new AuthenticatedPrincipal(PrincipalId.newId());app.getBean(AccessControlProvisioning.class).registerPrincipal(customer.principalId());
        app.getBean(WaitlistUseCase.class).register(new WaitlistUseCase.CreateRegistration(v.id(),slot.id(),2,new WaitlistRegistrationKey(UUID.randomUUID()),customer));
        return new Fixture(t,v,slot);
    }
    private UUID request(Fixture f) {
        app.getBean(WaitlistPromotionRequestUseCase.class).request(SystemPrincipal.INSTANCE,f.venue.id(),f.slot.id());
        return db.queryForObject("SELECT event_id FROM event_records WHERE tenant_id=? ORDER BY boundary_sequence DESC LIMIT 1",(r,n)->uuid(r.getBytes(1)),bytes(f.tenant.id().value()));
    }
    private void bookingHttp(Fixture f,String phase) throws Exception {
        var r=app.getBean(ResourceUseCase.class).createResource(new ResourceUseCase.CreateResource(f.tenant.id(),f.venue.id(),"API independent table",4));var slot=app.getBean(SlotInventoryUseCase.class).createSlot(new SlotInventoryUseCase.CreateSlot(f.tenant.id(),f.venue.id(),r.id(),f.slot.startsAt().toString()));
        var response=call("POST",ports.get("product"),"/api/v1/venues/"+f.venue.id().value()+"/reservations/holds",product,Map.of("slotInventoryId",slot.id().value(),"partySize",2));var body=object(response,201);record("booking-http",Map.of("during",phase,"status",201,"reservation",body));
    }
    private void provisionOperator(UUID tenant) {
        if(operatorId==null){operatorId=UUID.randomUUID();db.update("INSERT INTO operations_operators(operator_id,principal_reference) VALUES (?,?)",bytes(operatorId),"drill-operator-"+operatorId);db.update("INSERT INTO operations_credentials(credential_id,operator_id,token_hash,expires_at) VALUES (?,?,UNHEX(SHA2(?,256)),TIMESTAMPADD(DAY,1,UTC_TIMESTAMP(6)))",bytes(UUID.randomUUID()),bytes(operatorId),operator);}
        for(String action:List.of("READ","BUSINESS_REPLAY"))db.update("INSERT INTO operations_grants(operator_id,tenant_id,consumer_id,action) VALUES (?,?,?,?)",bytes(operatorId),bytes(tenant),WAITLIST,action);
    }
    private void recover(Fixture broken,UUID event,Path fault,String prefix,String alertName) throws Exception {
        provisionOperator(broken.tenant.id().value());
        UUID registration=db.queryForObject("SELECT r.registration_id FROM event_deliveries d JOIN event_registrations r ON r.registration_id=d.registration_id WHERE d.event_id=? AND r.consumer_id=?",(r,n)->uuid(r.getBytes(1)),bytes(event),WAITLIST);
        String exact="/internal/operations/tenants/"+broken.tenant.id().value()+"/consumers/"+WAITLIST+"/deliveries/"+event+"/"+registration;
        require(call("GET",ports.get("product"),exact,monitor,null).statusCode()==401,"monitor accepted as operator");
        require(call("GET",ports.get("product"),exact,null,null).statusCode()==401,"anonymous accepted as operator");
        var view=object(call("GET",ports.get("product"),exact,operator,null),200);
        Files.delete(fault);record(prefix+"handler-cause-corrected",Map.of("change","test-only failure advice disabled; production handler retained"));
        UUID operation=UUID.randomUUID();var command=Map.of("operationId",operation.toString(),"reason","compatible production handler restored after controlled drill failure","expectedState","DEAD","expectedFence",view.get("fencingToken"),"expectedTransport",transport,"expectedAuthorityEpoch",epoch);
        var admitted=object(call("POST",ports.get("product"),exact+"/replay",operator,command),200);
        var retry=object(call("POST",ports.get("product"),exact+"/replay",operator,command),200);require(admitted.equals(retry),"same operation created another recovery cycle");
        record(prefix+"human-recovery-admitted",Map.of("operationId",operation.toString(),"exactRead",view,"admission",admitted,"sameOperationRetry",retry,"transport",transport,"boundary","verified HTTPS; real credential hash/grants/security chain"));
        converge(event);alert(alertName,"slotq-waitlist",false);traceEvidence(event);
        object(call("GET",ports.get("product"),"/internal/operations/tenants/"+broken.tenant.id().value()+"/consumers/"+WAITLIST+"/operations/"+operation,operator,null),200);
        capture(prefix+"human-recovery-completed",event);
    }
    private HttpResponse<String> call(String method,int port,String path,String token,Object body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("https://localhost:"+port+path)).timeout(Duration.ofSeconds(5));if(token!=null)request.header("Authorization","Bearer "+token);
        if(body==null)request.method(method,HttpRequest.BodyPublishers.noBody());else request.header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).method(method,HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static Map<String,Object> object(HttpResponse<String> response,int expected) throws Exception {require(response.statusCode()==expected,"unexpected HTTP "+response.statusCode()+": "+response.body());return JSON.readValue(response.body(),new TypeReference<>(){});}
    private boolean done(UUID event,String consumer){return db.queryForObject("SELECT COUNT(*) FROM event_deliveries d JOIN event_registrations r ON r.registration_id=d.registration_id WHERE d.event_id=? AND r.consumer_id=? AND d.state='DONE'",Integer.class,bytes(event),consumer)==1;}
    private boolean dead(UUID event){return db.queryForObject("SELECT COUNT(*) FROM event_deliveries d JOIN event_registrations r ON r.registration_id=d.registration_id WHERE d.event_id=? AND r.consumer_id=? AND d.state='DEAD'",Integer.class,bytes(event),WAITLIST)==1;}
    private void converge(UUID event) throws Exception {await("two logical targets DONE "+event,90,()->done(event,WAITLIST)&&done(event,OBSERVER)&&(!transport.equals("KAFKA")||db.queryForObject("SELECT COUNT(*) FROM event_kafka_publications WHERE event_id=? AND state='PUBLISHED'",Integer.class,bytes(event))==1));}
    private void await(String reason,int seconds,BooleanSupplier condition) throws Exception {long end=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(500);}throw new IllegalStateException("Timed out: "+reason+"; local logs "+local);}
    private void capture(String phase,UUID event) {
        var state=new LinkedHashMap<String,Object>();state.put("eventId",event.toString());state.put("transport",transport);state.put("authorityEpoch",epoch);state.put("tenants",tenants.stream().map(t->new WaitlistRecoveryDatabase(db).snapshot(t,Instant.now())).toList());
        for(String table:List.of("event_kafka_publications","event_kafka_target_intakes","event_kafka_intake_records","event_observation_projections","operations_recovery_operations","operations_recovery_audit","event_transport_assignments","event_transport_cutover"))state.put(table,rows("SELECT * FROM "+table));
        if(!phase.equals("broker-unavailable"))try{var ends=admin.listOffsets(Map.of(new TopicPartition("slotq.waitlist.events.v1",0),OffsetSpec.latest(),new TopicPartition("slotq.waitlist.events.v1",1),OffsetSpec.latest(),new TopicPartition("slotq.waitlist.events.v1",2),OffsetSpec.latest())).all().get();var groups=new TreeMap<String,Object>();for(var definition:app!=null?app.getBean(KafkaConsumerCatalog.class).consumers():List.<KafkaConsumerCatalog.ConsumerDefinition>of()){var offsets=admin.listConsumerGroupOffsets(definition.groupId()).partitionsToOffsetAndMetadata().get();groups.put(definition.consumerId(),Map.of("committedNext",offsets.entrySet().stream().map(e->Map.of("partition",e.getKey().partition(),"offset",e.getValue().offset())).toList(),"ends",ends.entrySet().stream().map(e->Map.of("partition",e.getKey().partition(),"offset",e.getValue().offset())).toList()));}state.put("broker",groups);}catch(Exception failed){state.put("broker",Map.of("available",false));}
        record(phase,state);
    }
    private List<Map<String,Object>> rows(String sql){return db.query(sql,(r,n)->{var row=new LinkedHashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++){Object v=r.getObject(i);if(v instanceof byte[]b)v=HexFormat.of().formatHex(b);else if(v instanceof java.time.LocalDateTime t)v=t.toInstant(ZoneOffset.UTC).toString();else if(v instanceof java.sql.Timestamp t)v=t.toInstant().toString();row.put(r.getMetaData().getColumnLabel(i),v);}return(Map<String,Object>)row;});}
    private void record(String phase,Map<String,?> evidence){var item=new LinkedHashMap<String,Object>();item.put("phase",phase);item.put("at",Instant.now().toString());item.put("nanos",System.nanoTime());item.putAll(evidence);timeline.add(item);System.out.println("#111 drill "+phase);}
    private void docker(String action,String id) throws Exception {var p=new ProcessBuilder(System.getenv().getOrDefault("SLOTQ_DOCKER_EXE","docker"),action,id).redirectErrorStream(true).start();p.getInputStream().readAllBytes();require(p.waitFor()==0,"container "+action+" failed");}
    private static UUID uuid(byte[] bytes){var b=java.nio.ByteBuffer.wrap(bytes);return new UUID(b.getLong(),b.getLong());}
    private static String secret(String prefix){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return prefix+HexFormat.of().formatHex(b);}
    private static String gitRevision() throws Exception {var p=new ProcessBuilder("git","-c","safe.directory=C:/dev/slotq","rev-parse","HEAD").start();String value=new String(p.getInputStream().readAllBytes()).strip();require(p.waitFor()==0,"revision unavailable");return value;}
    private record Fixture(Tenant tenant,Venue venue,SlotInventory slot){}
    private final class Role {final String label,role;final Process process;Role(String label,String role,Process process){this.label=label;this.role=role;this.process=process;}void kill(){if(process.isAlive()){process.destroyForcibly();try{process.waitFor();}catch(InterruptedException e){Thread.currentThread().interrupt();}record("process-stop",Map.of("label",label,"role",role,"pid",process.pid(),"exitCode",process.exitValue()));}}}
    @TestConfiguration(proxyBeanMethods=false) static class ProductConfiguration {
        @Bean BearerCredentialResolver fixtureCredential(){return token->token.equals(productToken)?Optional.ofNullable(productPrincipal):Optional.empty();}
        @Bean ThreadPoolTaskScheduler taskScheduler(){return new ThreadPoolTaskScheduler(){@Override public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Duration delay){return inert();}@Override public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Instant start,Duration delay){return inert();}};}
        private static java.util.concurrent.ScheduledFuture<?> inert(){return new java.util.concurrent.ScheduledFuture<Object>(){public long getDelay(java.util.concurrent.TimeUnit unit){return Long.MAX_VALUE;}public int compareTo(java.util.concurrent.Delayed other){return 1;}public boolean cancel(boolean interrupt){return true;}public boolean isCancelled(){return false;}public boolean isDone(){return false;}public Object get(){return null;}public Object get(long t,java.util.concurrent.TimeUnit u){return null;}};}
    }
}
