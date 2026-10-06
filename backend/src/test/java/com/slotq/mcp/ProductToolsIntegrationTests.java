package com.slotq.mcp;

import com.slotq.auth.access.*;
import com.slotq.auth.domain.*;
import com.slotq.auth.persistence.ActorAccessService;
import com.slotq.booking.application.*;
import com.slotq.integration.mcp.product.HoldApprovals;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.test.system.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** The real production composition root, MCP HTTPS, authenticated Product HTTPS and MySQL state. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "slotq.mcp.enabled=true","server.ssl.enabled=true","slotq.auth.dev-bootstrap-enabled=true",
    "spring.datasource.hikari.connection-timeout=2000","spring.datasource.hikari.maximum-pool-size=16",
    "spring.datasource.hikari.data-source-properties.socketTimeout=2000","spring.datasource.hikari.data-source-properties.connectTimeout=2000",
    "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=2, lock_wait_timeout=2, wait_timeout=5, max_execution_time=2000",
    "spring.transaction.default-timeout=10s","spring.jpa.properties.jakarta.persistence.query.timeout=2000",
    "slotq.mcp.quota.rate=100","slotq.mcp.quota.burst=100","slotq.mcp.quota.concurrency=4","slotq.mcp.workers=4",
    "slotq.mcp.handler-budget=PT3S"})
@ActiveProfiles("test") @Import(ProductToolsIntegrationTests.FaultConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ProductToolsIntegrationTests {
    @Container @ServiceConnection static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
        .withCommand("--log-bin-trust-function-creators=1").withDatabaseName("slotq");
    static final int PORT=McpHttpIntegrationTests.port();static final String ORIGIN="https://localhost:"+PORT;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){
        p.add("server.port",()->PORT);p.add("slotq.mcp.origin",()->ORIGIN);p.add("slotq.mcp.product-origin",()->ORIGIN);
        p.add("server.ssl.key-store",()->McpHttpIntegrationTests.KEYSTORE.toUri().toString());
        p.add("server.ssl.key-store-password",()->McpHttpIntegrationTests.PASSWORD);p.add("server.ssl.key-store-type",()->"PKCS12");
    }
    @Autowired JdbcTemplate db;@MockitoSpyBean ActorAccessService access;@Autowired HoldApprovals approvals;
    @Autowired TenantUseCase tenants;@Autowired VenueConfigurationUseCase venues;@Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;@Autowired ReservationUseCase reservations;@Autowired ToolRegistry registry;
    @Autowired McpEngine engine;@Autowired ResponseFault fault;@Autowired TestClock clock;
    @Autowired com.slotq.knowledge.application.CorpusAuthoring corpus;
    final JsonMapper json=JsonMapper.builder().build();HttpClient http;
    VenueId venue;UUID tenant,subject,slot;LocalDate day;
    ActorAccessService.ProvisionedCredential original,delegated;String session;
    @BeforeEach void fixture() throws Exception {
        clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);fault.reset();
        http=HttpClient.newBuilder().sslContext(McpHttpIntegrationTests.tls()).connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        tenant=tenants.createTenant().id().value();day=LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC).plusDays(2);
        venue=venues.createVenue(new VenueConfigurationUseCase.CreateVenue(new com.slotq.tenancy.domain.TenantId(tenant),"Product tools","UTC",
            new WeeklyOperatingHours(Map.of(day.getDayOfWeek(),new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(22,0)))),
            new BookingPolicyTerms(30,5,20,10))).id();slot=newSlot(venue,tenant,4);
        subject=UUID.randomUUID();original=original(subject);
        delegated=customer(original,venue);session=initialize(delegated.value());
    }
    @AfterEach void release(){fault.release.countDown();clock.now=Instant.now();await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(engine.activeWorkers()).isZero());}
    @Test void productionRegistryOwnReadHoldManagementAndMetadataOnlyAudit(CapturedOutput output) throws Exception {
        assertThat(registry.all()).extracting(t->t.wire().name()).containsExactly("knowledge.search","management.reservations.list","reservation.get","reservation.hold");
        var approved=approved(slot,2);var hold=call("reservation.hold",arguments(approved));
        assertSuccess(hold);UUID id=UUID.fromString(hold.path("structuredContent").path("data").path("id").asString());
        assertThat(hold.path("structuredContent").path("productStatus").asInt()).isEqualTo(201);assertRows(slot,1);
        var read=call("reservation.get",Map.of("reservationId",id));assertSuccess(read);
        assertThat(read.path("structuredContent").path("data")).isEqualTo(hold.path("structuredContent").path("data"));
        for(String role:List.of("OWNER","MANAGER","STAFF")) {
            var manager=original(UUID.randomUUID());
            if(role.equals("OWNER")) db.update("INSERT INTO tenant_memberships(tenant_id,principal_id,role) VALUES(?,?,'OWNER')",bytes(tenant),bytes(principal(manager)));
            else {
                db.update("INSERT INTO tenant_memberships(tenant_id,principal_id,role) VALUES(?,?,?)",bytes(tenant),bytes(principal(manager)),role);
                db.update("INSERT INTO venue_grants(tenant_id,venue_id,principal_id,role) VALUES(?,?,?,?)",bytes(tenant),bytes(venue.value()),bytes(principal(manager)),role);
            }
            var delegation=access.approveDelegation(manager.value(),venue,AccessProfile.MANAGEMENT,Set.of(AccessAction.MANAGEMENT_READ),Set.of("management.reservations.list"),Duration.ofMinutes(15));
            String management=initialize(delegation.value());
            var list=call(delegation.value(),management,"management.reservations.list",Map.of("date",day.toString(),"status","HELD"));
            assertSuccess(list);assertThat(list.path("structuredContent").path("data").size()).isEqualTo(1);
            assertThat(list.path("structuredContent").path("data").get(0).path("id").asString()).isEqualTo(id.toString());
            assertThat(call(delegation.value(),management,"reservation.get",Map.of("reservationId",id)).toString()).contains("forbidden");
            for(String date:List.of("2026-02-31","2026-13-01","xxxxxxxxxx"))
                assertThat(call(delegation.value(),management,"management.reservations.list",Map.of("date",date)).toString()).contains("validation","not_dispatched");
        }
        // Customer has no operator membership. Even adding membership must not widen own-only MCP reads.
        var otherOriginal=original(UUID.randomUUID());var other=customer(otherOriginal,venue);String otherSession=initialize(other.value());
        assertThat(call(other.value(),otherSession,"reservation.get",Map.of("reservationId",id)).path("structuredContent").path("productStatus").asInt()).isEqualTo(404);
        db.update("INSERT INTO tenant_memberships(tenant_id,principal_id,role) VALUES(?,?,'OWNER')",bytes(tenant),bytes(principal(otherOriginal)));
        assertThat(call(other.value(),otherSession,"reservation.get",Map.of("reservationId",id)).path("structuredContent").path("productStatus").asInt()).isEqualTo(404);
        assertThat(call("management.reservations.list",Map.of("date",day.toString())).toString()).contains("forbidden");
        var owner=otherOriginal;
        String text="Synthetic venue information. Tea service.";
        var staged=corpus.stage(owner.value(),venue,new com.slotq.knowledge.domain.Corpus.VersionInput(UUID.randomUUID(),UUID.randomUUID(),
                new com.slotq.knowledge.domain.Corpus.Source(UUID.randomUUID(),"seed:product-and-knowledge","Synthetic"),
                com.slotq.knowledge.domain.Corpus.Visibility.VENUE_PUBLIC,text,com.slotq.knowledge.domain.Corpus.digest(text)),0);
        corpus.validate(owner.value(),staged.reference());corpus.publish(owner.value(),staged.reference());
        var knowledge=access.approveDelegation(original.value(),venue,AccessProfile.CUSTOMER,Set.of(AccessAction.KNOWLEDGE_PUBLIC),Set.of("knowledge.search"),Duration.ofMinutes(10));
        var retrieved=call(knowledge.value(),initialize(knowledge.value()),"knowledge.search",Map.of("query","tea service"));
        assertThat(retrieved.path("structuredContent").path("category").asString()).isEqualTo("evidence");
        assertThat(retrieved.path("structuredContent").path("results").get(0).path("versionId").asString()).isEqualTo(staged.reference().versionId().toString());
        assertThat(output.getAll()).doesNotContain(original.value(),delegated.value(),approved.review().idempotencyKey(),"Product tools");
        assertThat(approvals.review(original.value(),approved.review().intentId()).knownReservationId()).isEqualTo(id);
    }
    @Test void schemasRejectMalformedExtraWrongTypeBoundsAndCallerAuthority() throws Exception {
        var approval=approved(slot,2);Map<String,Object> valid=arguments(approval);
        for(Map<String,?> changes:List.of(Map.of("partySize",0),Map.of("partySize",2.5),Map.of("partySize","2"),
            Map.of("partySize",2147483648L),Map.of("slotInventoryId","invalid"),Map.of("idempotencyKey",""),
            Map.of("slotInventoryId","x".repeat(36)),Map.of("intentId","x".repeat(36)),Map.of("confirmationId","x".repeat(36)),
            Map.of("idempotencyKey","a".repeat(256)),Map.of("idempotencyKey","space key"),Map.of("confirmation",true),
            Map.of("tenant",tenant.toString()),Map.of("venue",venue.value().toString()),Map.of("role","OWNER"),Map.of("allowlist",List.of("all")),
            Map.of("tool","reservation.get"),Map.of("action","MANAGEMENT_READ"))) {
            Map<String,Object> wrong=new HashMap<>(valid);wrong.putAll(changes);
            assertThat(call("reservation.hold",wrong).toString()).contains("validation","not_dispatched");
        }
        Map<String,Object> missing=new HashMap<>(valid);missing.remove("idempotencyKey");
        assertThat(call("reservation.hold",missing).toString()).contains("validation","not_dispatched");
        assertThat(call("reservation.get",Map.of("reservationId",false)).toString()).contains("validation");assertRows(slot,0);
    }
    @Test void exactApprovalRejectsMaterialKeyIntentConfirmationDelegationActorVenueAndTenantSubstitution() throws Exception {
        var approval=approved(slot,2);var otherApproval=approved(newSlot(venue,tenant,4),2);
        Map<String,Object> valid=arguments(approval);
        for(Map<String,?> changes:List.of(Map.of("partySize",3),Map.of("slotInventoryId",otherApproval.review().slotInventoryId()),
            Map.of("idempotencyKey",UUID.randomUUID().toString()),Map.of("intentId",otherApproval.review().intentId()),
            Map.of("confirmationId",otherApproval.confirmation().id()))) {
            Map<String,Object> wrong=new HashMap<>(valid);wrong.putAll(changes);
            assertThat(call("reservation.hold",wrong).toString()).contains("forbidden","not_dispatched");
        }
        var another=customer(original,venue);assertThat(call(another.value(),initialize(another.value()),"reservation.hold",valid).toString()).contains("forbidden");
        var stranger=original(UUID.randomUUID());var other=customer(stranger,venue);
        assertThatThrownBy(()->approvals.approve(stranger.value(),approval.review().intentId())).isInstanceOf(McpFailure.class);
        assertThat(call(other.value(),initialize(other.value()),"reservation.hold",valid).toString()).contains("forbidden");
        for(boolean otherTenant:List.of(false,true)) {
            UUID t=otherTenant?tenants.createTenant().id().value():tenant;
            VenueId v=venues.createVenue(new VenueConfigurationUseCase.CreateVenue(new com.slotq.tenancy.domain.TenantId(t),"Other","UTC",
                new WeeklyOperatingHours(Map.of(day.getDayOfWeek(),new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(22,0)))),new BookingPolicyTerms(30,5,20,10))).id();
            var d=customer(original,v);
            assertThat(call(d.value(),initialize(d.value()),"reservation.hold",valid).toString()).contains("forbidden");
            var cross=approvals.prepare(original.value(),delegated.delegationId(),newSlot(v,t,4),2);
            var crossApproval=new Approved(cross,approvals.approve(original.value(),cross.intentId()));
            assertThat(call("reservation.hold",arguments(crossApproval)).path("structuredContent").path("productStatus").asInt()).isEqualTo(404);
        }
        assertRows(slot,0);
    }
    @Test void concurrentApprovedReplayHasOneProductIdentityAndDoesNotRenewCompletedAt() throws Exception {
        var approved=approved(slot,2);var args=arguments(approved);
        try(var executor=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            var a=executor.submit(()->{start.await();return call("reservation.hold",args);});
            var b=executor.submit(()->{start.await();return call("reservation.hold",args);});start.countDown();
            var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);assertSuccess(first);assertSuccess(second);
            assertThat(first.path("structuredContent").path("data").path("id")).isEqualTo(second.path("structuredContent").path("data").path("id"));
        }
        assertRows(slot,1);var completed=completedAt(approved);var first=approvals.review(original.value(),approved.review().intentId()).firstDispatchAt();
        clock.now=clock.instant().plusSeconds(240);
        var renewed=new Approved(approvals.review(original.value(),approved.review().intentId()),approvals.approve(original.value(),approved.review().intentId()));
        assertSuccess(call("reservation.hold",arguments(renewed)));assertThat(completedAt(approved)).isEqualTo(completed);
        assertThat(renewed.review().idempotencyKey()).isEqualTo(approved.review().idempotencyKey());assertThat(renewed.review().firstDispatchAt()).isEqualTo(first);
        clock.now=first.plusSeconds(900);
        assertThatThrownBy(()->approvals.approve(original.value(),approved.review().intentId())).isInstanceOf(RuntimeException.class);
        assertRows(slot,1);
    }
    @Test void confirmationExactExpiryAndFreshApprovalCannotExtendProductRetentionOrReuseAnOldKey() throws Exception {
        var expired=approved(slot,2);clock.now=expired.confirmation().expiresAt();
        assertThat(call("reservation.hold",arguments(expired)).toString()).contains("forbidden","not_dispatched");assertRows(slot,0);
        clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);var first=approved(slot,2);assertSuccess(call("reservation.hold",arguments(first)));Instant completed=completedAt(first);
        // A fresh intent owns a fresh immutable server key; it cannot adopt the completed Product key.
        var fresh=approved(newSlot(venue,tenant,4),2);var attack=arguments(fresh);attack.put("idempotencyKey",first.review().idempotencyKey());
        assertThat(call("reservation.hold",attack).toString()).contains("forbidden","not_dispatched");assertThat(completedAt(first)).isEqualTo(completed);
        clock.now=completed.plus(Duration.ofHours(24));
        assertThatThrownBy(()->approvals.approve(original.value(),first.review().intentId())).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM mcp_hold_intents WHERE id=?",Integer.class,bytes(first.review().intentId()))).isEqualTo(1);
        assertThat(completedAt(first)).isEqualTo(completed);
    }
    @Test void actualNarrowedProductMaterialOperationAndTargetCannotBeSubstituted() throws Exception {
        String key=UUID.randomUUID().toString();
        for(var change:List.of(Map.of("slotInventoryId",newSlot(venue,tenant,4),"partySize",2,"key",key),
            Map.of("slotInventoryId",slot,"partySize",3,"key",key),Map.of("slotInventoryId",slot,"partySize",2,"key","other-key"))) {
            var c=access.issueHold(delegated.delegationId(),slot,2,key,clock.instant().plusSeconds(30));
            var r=productHold(c.value(),venue,(UUID)change.get("slotInventoryId"),(int)change.get("partySize"),(String)change.get("key"));
            assertThat(r.statusCode()).isEqualTo(401);
        }
        var read=access.issueProduct(delegated.delegationId(),ProductOperation.RESERVATION_GET,UUID.randomUUID(),clock.instant().plusSeconds(30));
        assertThat(productHold(read.value(),venue,slot,2,key).statusCode()).isEqualTo(401);assertRows(slot,0);
        assertThat(productHold(delegated.value(),venue,slot,2,key).statusCode()).isEqualTo(401);
    }
    @Test void productConflictCapacityAndPartyPolicyRemainAuthoritative() throws Exception {
        var first=approved(slot,2);assertSuccess(call("reservation.hold",arguments(first)));
        var next=approved(slot,2);var full=call("reservation.hold",arguments(next));assertThat(full.path("structuredContent").path("productStatus").asInt()).isEqualTo(409);
        assertThat(full.toString()).contains("CAPACITY_UNAVAILABLE");assertRows(slot,1);
        var party=approved(newSlot(venue,tenant,1),2);var unsupported=call("reservation.hold",arguments(party));assertThat(unsupported.toString()).contains("PARTY_SIZE_NOT_SUPPORTED");
        var c=access.issueHold(delegated.delegationId(),slot,3,first.review().idempotencyKey(),clock.instant().plusSeconds(30));
        assertThat(productHold(c.value(),venue,slot,3,first.review().idempotencyKey()).body()).contains("IDEMPOTENCY_KEY_REUSED");
        assertRows(slot,1);assertThat(db.queryForObject("SELECT COUNT(*) FROM hold_idempotency_records WHERE idempotency_key=?",Integer.class,first.review().idempotencyKey())).isEqualTo(1);
    }
    @Test void mysqlCommitThenLostResponseKeepsUnknownAndExplicitRetryUsesSameIntentKey() throws Exception {
        var approved=approved(slot,2);fault.arm(venue,false,false);
        var unknown=call("reservation.hold",arguments(approved));
        assertThat(unknown.toString()).contains("outcome_unknown");assertThat(unknown.path("_meta").has("knownTarget")).isFalse();assertRows(slot,1);
        assertThat(approvals.review(original.value(),approved.review().intentId()).knownReservationId()).isNull();
        // A failed exact read of an unrelated ID does not invent an outcome for this unknown mutation.
        assertThat(call("reservation.get",Map.of("reservationId",UUID.randomUUID())).path("structuredContent").path("productStatus").asInt()).isEqualTo(404);
        assertThat(approvals.review(original.value(),approved.review().intentId()).knownReservationId()).isNull();
        Instant completed=completedAt(approved);fault.reset();var retried=call("reservation.hold",arguments(approved));assertSuccess(retried);
        assertRows(slot,1);assertThat(completedAt(approved)).isEqualTo(completed);
        save("response-loss-unknown.json",Map.of("firstOutcome",unknown,"retry",retried,"durableRows",Map.of("reservations",1,"allocations",1,"idempotency",1),"completedAt",completed.toString()));
    }
    @Test void knownLocationSurvivesLostBodyAndReconcilesThroughExactProductRead() throws Exception {
        var approved=approved(slot,2);fault.arm(venue,true,false);var unknown=call("reservation.hold",arguments(approved));
        assertThat(unknown.toString()).contains("outcome_unknown");assertRows(slot,1);
        UUID known=UUID.fromString(unknown.path("_meta").path("knownTarget").asString());fault.reset();
        assertThat(approvals.review(original.value(),approved.review().intentId()).knownReservationId()).isEqualTo(known);
        var exact=call("reservation.get",Map.of("reservationId",known));assertSuccess(exact);
        assertThat(exact.path("structuredContent").path("data").path("id").asString()).isEqualTo(known.toString());
        save("response-loss-known.json",Map.of("unknown",unknown,"exactReconciliation",exact,"durableReservation",known.toString()));
    }
    @Test void timeoutKeepsActualProductRequestAndAllMcpPermitsAccountedUntilExit() throws Exception {
        var approved=approved(slot,2);fault.arm(venue,false,true);fault.entered=new CountDownLatch(4);
        try(var executor=Executors.newFixedThreadPool(4)) {
            var calls=new ArrayList<Future<JsonNode>>();for(int i=0;i<4;i++)calls.add(executor.submit(()->call("reservation.hold",arguments(approved))));
            for(var call:calls)assertThat(call.get(8,TimeUnit.SECONDS).toString()).contains("unknown");
        }
        assertThat(fault.entered.await(1,TimeUnit.SECONDS)).isTrue();assertRows(slot,1);assertThat(engine.activeWorkers()).isEqualTo(4);
        assertThat(call("reservation.hold",arguments(approved)).toString()).contains("rate_limited","not_dispatched");
        assertThat(approvals.review(original.value(),approved.review().intentId()).knownReservationId()).isNull();
        fault.release.countDown();await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(engine.activeWorkers()).isZero());
        fault.reset();
        assertSuccess(call("reservation.hold",arguments(approved)));assertRows(slot,1);
    }
    @Test void currentAuthenticationWorkAlsoRetainsPermitAfterClientTimeoutAndExpiresBeforeApplication() throws Exception {
        var approval=approved(slot,2);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Auth pause not released");return invocation.callRealMethod();})
            .when(access).authenticateProduct(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq("POST"),org.mockito.ArgumentMatchers.endsWith("/holds"));
        try {
            assertThat(call("reservation.hold",arguments(approval)).toString()).contains("unknown");
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();assertThat(engine.activeWorkers()).isEqualTo(1);assertRows(slot,0);
            // The client timeout itself is not credential expiry or remote termination evidence.
            TimeUnit.SECONDS.sleep(1);assertThat(engine.activeWorkers()).isEqualTo(1);
        }finally{release.countDown();}
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(engine.activeWorkers()).isZero());assertRows(slot,0);
    }
    @Test void actualMcpHoldBlockedByProductSlotLockHasNoDurableEffect() throws Exception {
        var approval=approved(slot,2);
        try(var locker=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())) {
            locker.setAutoCommit(false);try(var query=locker.prepareStatement("SELECT id FROM slot_inventories WHERE id=? FOR UPDATE")) {
                query.setBytes(1,bytes(slot));query.executeQuery().close();
            }
            var blocked=call("reservation.hold",arguments(approval));assertThat(blocked.toString()).contains("unknown");assertRows(slot,0);locker.rollback();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(engine.activeWorkers()).isZero());
        assertSuccess(call("reservation.hold",arguments(approval)));assertRows(slot,1);
    }
    @Test void actualMcpHoldDuringProductDatabaseOutageRemainsUnknownAndRecoversOnlyByExplicitRetry() throws Exception {
        var approval=approved(slot,2);
        org.mockito.Mockito.doAnswer(invocation->{var credential=invocation.callRealMethod();MYSQL.getDockerClient().pauseContainerCmd(MYSQL.getContainerId()).exec();return credential;})
            .when(access).issueHold(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(slot),org.mockito.ArgumentMatchers.eq(2),
                org.mockito.ArgumentMatchers.eq(approval.review().idempotencyKey()),org.mockito.ArgumentMatchers.any());
        try{assertThat(call("reservation.hold",arguments(approval)).toString()).contains("unknown");}
        finally{MYSQL.getDockerClient().unpauseContainerCmd(MYSQL.getContainerId()).exec();org.mockito.Mockito.reset(access);}
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(engine.activeWorkers()).isZero());assertRows(slot,0);
        assertSuccess(call("reservation.hold",arguments(approval)));assertRows(slot,1);
    }
    Approved approved(UUID target,int party){var review=approvals.prepare(original.value(),delegated.delegationId(),target,party);return new Approved(review,approvals.approve(original.value(),review.intentId()));}
    record Approved(HoldApprovals.Review review,HoldApprovals.Confirmation confirmation){}
    Map<String,Object> arguments(Approved a){Map<String,Object> values=new HashMap<>();values.put("intentId",a.review().intentId());values.put("confirmationId",a.confirmation().id());
        values.put("slotInventoryId",a.review().slotInventoryId());values.put("partySize",a.review().partySize());values.put("idempotencyKey",a.review().idempotencyKey());return values;}
    UUID newSlot(VenueId v,UUID t,int capacity){var r=resources.createResource(new ResourceUseCase.CreateResource(new com.slotq.tenancy.domain.TenantId(t),v,"Table",capacity));
        return slots.createSlot(new SlotInventoryUseCase.CreateSlot(new com.slotq.tenancy.domain.TenantId(t),v,r.id(),day+"T12:00:00Z")).id().value();}
    ActorAccessService.ProvisionedCredential original(UUID id){db.update("INSERT INTO auth_principals(id) VALUES(?)",bytes(id));return access.provisionOriginal(new PrincipalId(id),clock.instant().plus(Duration.ofDays(3)));}
    UUID principal(ActorAccessService.ProvisionedCredential c){return db.queryForObject("SELECT principal_id FROM auth_access_credentials WHERE id=?",(rs,i)->HoldApprovalsUuid(rs.getBytes(1)),bytes(c.id()));}
    ActorAccessService.ProvisionedCredential customer(ActorAccessService.ProvisionedCredential c,VenueId v){return access.approveDelegation(c.value(),v,AccessProfile.CUSTOMER,
        Set.of(AccessAction.RESERVATION_READ,AccessAction.RESERVATION_WRITE),Set.of("reservation.get","reservation.hold"),Duration.ofMinutes(15));}
    void assertRows(UUID target,int expected){for(String table:List.of("reservations","capacity_allocations"))assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE slot_inventory_id=?",Integer.class,bytes(target))).isEqualTo(expected);}
    Instant completedAt(Approved a){return db.queryForObject("SELECT completed_at FROM hold_idempotency_records WHERE idempotency_key=?",(rs,i)->rs.getTimestamp(1).toInstant(),a.review().idempotencyKey());}
    void assertSuccess(JsonNode node){assertThat(node.path("isError").asBoolean()).as(node.toString()).isFalse();assertThat(node.path("structuredContent").path("outcome").asString()).isEqualTo("succeeded");}
    JsonNode call(String name,Object args) throws Exception{return call(delegated.value(),session,name,args);}
    @SuppressWarnings("unchecked")
    JsonNode call(String token,String session,String name,Object args) throws Exception {
        var body=json.readTree(rpc(token,session,"tools/call",Map.of("name",name,"arguments",args),2).body());
        var result=body.path("result");
        if(result.isMissingNode() && body.has("outcome"))return body; // Actual transport timeout response, not a fabricated RPC result.
        registry.validateOutput(registry.require(name),json.convertValue(result.path("structuredContent"),Map.class));
        return result;
    }
    String initialize(String token) throws Exception{var init=rpc(token,null,"initialize",Map.of("protocolVersion","2025-11-25","capabilities",Map.of(),"clientInfo",Map.of("name","product-tests","version","1")),1);
        String id=init.headers().firstValue("Mcp-Session-Id").orElseThrow();assertThat(rpc(token,id,"notifications/initialized",Map.of(),null).statusCode()).isEqualTo(202);return id;}
    HttpResponse<String> rpc(String token,String session,String method,Object params,Integer id) throws Exception{var body=new LinkedHashMap<String,Object>();body.put("jsonrpc","2.0");body.put("method",method);body.put("params",params);if(id!=null)body.put("id",id);
        var b=HttpRequest.newBuilder(URI.create(ORIGIN+"/mcp")).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version","2025-11-25");if(session!=null)b.header("Mcp-Session-Id",session);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> productHold(String token,VenueId v,UUID target,int party,String key) throws Exception{var b=HttpRequest.newBuilder(URI.create(ORIGIN+"/api/v1/venues/"+v.value()+"/reservations/holds"))
        .timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+token).header("Content-Type","application/json");if(key!=null)b.header("Idempotency-Key",key);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("slotInventoryId",target,"partySize",party)))).build(),HttpResponse.BodyHandlers.ofString());}
    void save(String name,Object data) throws Exception{Path p=Path.of("build/mcp-product-tools/"+name);Files.createDirectories(p.getParent());Files.writeString(p,json.writerWithDefaultPrettyPrinter().writeValueAsString(data));}
    static byte[] bytes(UUID id){return McpHttpIntegrationTests.bytes(id);}static UUID HoldApprovalsUuid(byte[] b){var x=java.nio.ByteBuffer.wrap(b);return new UUID(x.getLong(),x.getLong());}
    static class TestClock extends Clock {volatile Instant now=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}}
    static class ResponseFault implements Filter {
        volatile String path="";volatile boolean known,holdOpen;volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
        void reset(){release.countDown();path="";known=false;holdOpen=false;entered=new CountDownLatch(0);release=new CountDownLatch(0);}
        void arm(VenueId venue,boolean known,boolean holdOpen){this.path="/api/v1/venues/"+venue.value()+"/reservations/holds";this.known=known;this.holdOpen=holdOpen;entered=new CountDownLatch(1);release=new CountDownLatch(1);}
        @Override public void doFilter(ServletRequest r,ServletResponse s,FilterChain chain) throws IOException,ServletException {
            HttpServletRequest request=(HttpServletRequest)r;HttpServletResponse response=(HttpServletResponse)s;
            if(!request.getRequestURI().equals(path)){chain.doFilter(r,s);return;}
            var buffered=new ContentCachingResponseWrapper(response);chain.doFilter(r,buffered);
            // Controller/application transaction has returned and COMMIT is complete before this fault.
            if(buffered.getStatus()!=201){buffered.copyBodyToResponse();return;}
            entered.countDown();if(holdOpen)try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Fault not released");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            if(!known){response.setHeader("Location",null);response.setHeader("X-Request-ID",null);}
            response.setContentLength(1024);response.getOutputStream().write('{');response.flushBuffer();
            // Servlet OutputStream.close() can leave a keep-alive socket waiting for the declared body.
            // Abort the real Tomcat connection after flushed headers/partial body, strictly after COMMIT.
            ServletResponse nativeResponse=response;
            while(nativeResponse instanceof ServletResponseWrapper wrapper)nativeResponse=wrapper.getResponse();
            try {
                var field=org.apache.catalina.connector.ResponseFacade.class.getDeclaredField("response");field.setAccessible(true);
                var nativeTomcat=(org.apache.catalina.connector.Response)field.get(nativeResponse);
                nativeTomcat.getCoyoteResponse().action(org.apache.coyote.ActionCode.CLOSE_NOW,new IOException("test-only post-COMMIT response loss"));
            }catch(ReflectiveOperationException error){throw new ServletException("Test response fault unavailable",error);}
        }
    }
    @TestConfiguration static class FaultConfiguration {
        @Bean @Primary TestClock testClock(){return new TestClock();}
        @Bean SSLContext productTestTrust() throws Exception{return McpHttpIntegrationTests.tls();}
        @Bean ResponseFault fault(){return new ResponseFault();}
        @Bean FilterRegistrationBean<ResponseFault> productResponseFault(ResponseFault f){var b=new FilterRegistrationBean<>(f);b.setOrder(-99);return b;}
    }
}
