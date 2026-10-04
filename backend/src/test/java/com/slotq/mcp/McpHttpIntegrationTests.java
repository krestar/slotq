package com.slotq.mcp;

import com.slotq.auth.access.*;
import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.application.ReservationAccessTarget;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import com.slotq.booking.application.*;
import com.slotq.auth.domain.*;
import com.slotq.auth.persistence.ActorAccessService;
import com.slotq.integration.operations.recovery.OperatorCredentials;
import com.slotq.venue.domain.VenueId;
import com.slotq.tenancy.domain.TenantId;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.system.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"slotq.mcp.enabled=true",
    "spring.datasource.hikari.connection-timeout=2000","spring.datasource.hikari.data-source-properties.socketTimeout=2000",
    "server.ssl.enabled=true","slotq.observability.scrape-token=scrape-SENTINEL-01234567890123456789"})
@Import(McpHttpIntegrationTests.Registrations.class)
@ExtendWith(OutputCaptureExtension.class)
class McpHttpIntegrationTests {
    @Container @ServiceConnection static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
        .withCommand("--log-bin-trust-function-creators=1").withDatabaseName("slotq");
    static final int PORT=port();static final String ORIGIN="https://localhost:"+PORT;
    static final String PASSWORD=UUID.randomUUID().toString();static final Path KEYSTORE=keystore();
    static volatile java.util.concurrent.CountDownLatch CONTINUING_ENTERED=new java.util.concurrent.CountDownLatch(0),
        CONTINUING_EXIT=new java.util.concurrent.CountDownLatch(0);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("server.port",()->PORT);r.add("slotq.mcp.origin",()->ORIGIN);
        r.add("slotq.mcp.product-origin",()->ORIGIN);
        r.add("server.ssl.key-store",()->KEYSTORE.toUri().toString());r.add("server.ssl.key-store-password",()->PASSWORD);
        r.add("server.ssl.key-store-type",()->"PKCS12");
        r.add("slotq.mcp.quota.rate",()->100);r.add("slotq.mcp.quota.burst",()->100);
        r.add("slotq.mcp.quota.concurrency",()->1);r.add("slotq.mcp.handler-budget",()->"PT1S");
    }
    @Autowired JdbcTemplate db;
    @Autowired ActorAccessService access;
    @Autowired AuthorizationUseCase authorization;
    @Autowired OperatorCredentials operations;
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired ReservationUseCase reservations;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired ProductHttpBinding binding;
    @Autowired McpEngine engine;
    @Autowired org.springframework.context.ApplicationContext context;
    HttpClient http;UUID principal,tenant,venue;
    ActorAccessService.ProvisionedCredential original,customer,management;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void fixture() throws Exception {
        http=HttpClient.newBuilder().sslContext(tls()).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).build();
        principal=UUID.randomUUID();tenant=UUID.randomUUID();venue=UUID.randomUUID();
        db.update("INSERT INTO auth_principals(id) VALUES(?)",bytes(principal));
        db.update("INSERT INTO tenants(id,status) VALUES(?,'ACTIVE')",bytes(tenant));
        db.update("INSERT INTO venues(id,tenant_id,status,timezone,name) VALUES(?,?,'ACTIVE','UTC','Synthetic')",bytes(venue),bytes(tenant));
        db.update("INSERT INTO tenant_memberships(tenant_id,principal_id,role) VALUES(?,?,'OWNER')",bytes(tenant),bytes(principal));
        original=access.provisionOriginal(new PrincipalId(principal),Instant.now().plusSeconds(3600));
        customer=access.approveDelegation(original.value(),new VenueId(venue),AccessProfile.CUSTOMER,
            Set.of(AccessAction.KNOWLEDGE_PUBLIC,AccessAction.RESERVATION_READ),Set.of("test.customer","test.management","reservation.get"),Duration.ofMinutes(10));
        management=access.approveDelegation(original.value(),new VenueId(venue),AccessProfile.MANAGEMENT,
            Set.of(AccessAction.MANAGEMENT_READ),Set.of("test.customer","test.management"),Duration.ofMinutes(10));
    }
    @Test void stableJavaClientNegotiatesInitializesListsAndCallsActualHttpsEndpoint(CapturedOutput output) throws Exception {
        var transport=HttpClientStreamableHttpTransport.builder(ORIGIN).endpoint("/mcp")
            .clientBuilder(HttpClient.newBuilder().sslContext(tls()).followRedirects(HttpClient.Redirect.NEVER))
            .requestBuilder(HttpRequest.newBuilder().header("Authorization","Bearer "+customer.value()))
            .supportedProtocolVersions(List.of("2025-11-25")).resumableStreams(false).openConnectionOnStartup(false)
            .maxResponseSize(131072).jsonMapper(new JacksonMcpJsonMapper(json)).build();
        try(var client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(3)).build()) {
            assertThat(client.initialize().protocolVersion()).isEqualTo("2025-11-25");
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).containsExactly("test.customer");
            var result=client.callTool(new McpSchema.CallToolRequest("test.customer",Map.of("query","synthetic","count",1)));
            assertThat(result.isError()).isFalse();assertThat(result.structuredContent().toString()).contains(tenant.toString());
            assertThat(client.callTool(new McpSchema.CallToolRequest("test.customer",Map.of("query","x","count",0))).isError()).isTrue();
            assertThat(client.callTool(new McpSchema.CallToolRequest("test.management",Map.of())).isError()).isTrue();
            assertThatThrownBy(()->client.callTool(new McpSchema.CallToolRequest("unknown",Map.of()))).isInstanceOf(RuntimeException.class);
        }
        assertThat(output.getAll()).doesNotContain(original.value(),customer.value(),management.value());
    }
    @Test void deterministicWireLifecycleVersionErrorsSchemasAndProfileTranscript() throws Exception {
        List<Object> transcript=new ArrayList<>();
        var init=rpc(customer.value(),null,"initialize",Map.of("protocolVersion","2025-11-25","capabilities",Map.of(),
            "clientInfo",Map.of("name","deterministic","version","1")),null,1);
        transcript.add(json.readTree(init.body()));
        String session=init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(rpc(customer.value(),session,"tools/list",Map.of(),"2025-11-25",2).body()).contains("PROTOCOL");
        assertThat(rpc(customer.value(),session,"notifications/initialized",Map.of(),"2025-11-25",null).statusCode()).isEqualTo(202);
        var list=rpc(customer.value(),session,"tools/list",Map.of(),"2025-11-25",3);transcript.add(json.readTree(list.body()));
        assertThat(list.body()).contains("test.customer").doesNotContain("test.management");
        var success=rpc(customer.value(),session,"tools/call",Map.of("name","test.customer","arguments",Map.of("query","x","count",1)),"2025-11-25",4);
        transcript.add(json.readTree(success.body()));assertThat(json.readTree(success.body()).path("result").path("isError").asBoolean()).isFalse();
        var deny=rpc(customer.value(),session,"tools/call",Map.of("name","test.management","arguments",Map.of()),"2025-11-25",5);
        transcript.add(json.readTree(deny.body()));assertThat(deny.body()).contains("forbidden","not_dispatched");
        var unknown=rpc(customer.value(),session,"tools/call",Map.of("name","unknown","arguments",Map.of()),"2025-11-25",6);
        transcript.add(json.readTree(unknown.body()));assertThat(json.readTree(unknown.body()).path("id").asInt()).isEqualTo(6);
        assertThat(unknown.body()).contains("-32602");
        for(Object arguments:List.of(Map.of("query","x","count",0),Map.of("query",false,"count",1),
                Map.of("query","x","count",1,"principal","injected","tenant",tenant.toString(),"role","OWNER","allowlist",List.of("all")),List.of("bad"))) {
            assertThat(rpc(customer.value(),session,"tools/call",Map.of("name","test.customer","arguments",arguments),"2025-11-25",7).body())
                .contains("validation","not_dispatched");
        }
        assertThat(rpc(customer.value(),session,"tools/list",Map.of(),"2026-07-28",8).statusCode()).isEqualTo(400);
        assertThat(rpc(customer.value(),null,"initialize",Map.of("protocolVersion","1999-01-01","capabilities",Map.of(),"clientInfo",Map.of()),null,9).body()).contains("PROTOCOL");
        assertThat(rpc(customer.value(),null,"initialize",Map.of("protocolVersion","2025-11-25","capabilities",Map.of("tasks",Map.of()),"clientInfo",Map.of()),null,10).body()).contains("PROTOCOL");
        assertThat(rpc(management.value(),session,"tools/list",Map.of(),"2025-11-25",11).statusCode()).isEqualTo(404);
        String m=initialized(management.value());
        assertThat(rpc(management.value(),m,"tools/list",Map.of(),"2025-11-25",12).body()).contains("test.management").doesNotContain("test.customer");
        assertThat(rpc(management.value(),m,"tools/call",Map.of("name","test.customer","arguments",Map.of()),"2025-11-25",13).body()).contains("forbidden");
        Path output=Path.of("build/mcp/interoperability.json");Files.createDirectories(output.getParent());
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(transcript));
    }
    @Test void streamableHttpSessionTerminationHeadersAndReinitializationHaveProtocolStatusSemantics() throws Exception {
        List<Object> statuses=new ArrayList<>();
        var missing=rpc(customer.value(),null,"tools/list",Map.of(),"2025-11-25",1);
        assertThat(missing.statusCode()).isEqualTo(400);
        statuses.add(Map.of("stage","missing_session","status",missing.statusCode()));
        String session=initialized(customer.value());
        var wrongRevision=http.send(builder(customer.value(),session,"1999-01-01").DELETE().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(wrongRevision.statusCode()).isEqualTo(400);
        statuses.add(Map.of("stage","unsupported_delete_revision","status",wrongRevision.statusCode()));
        assertThat(http.send(builder(customer.value(),session,"1999-01-01").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(http.send(builder(customer.value(),session,"2025-11-25").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
        var invalidNotification=rpc(customer.value(),session,"notifications/initialized",Map.of("unexpected",true),"2025-11-25",null);
        assertThat(invalidNotification.statusCode()).isEqualTo(400);
        var deleted=http.send(builder(customer.value(),session,"2025-11-25").DELETE().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).isEqualTo(200);
        statuses.add(Map.of("stage","deleted_session","status",deleted.statusCode()));
        var terminated=rpc(customer.value(),session,"tools/list",Map.of(),"2025-11-25",2);
        assertThat(terminated.statusCode()).isEqualTo(404);
        statuses.add(Map.of("stage","terminated_session","status",terminated.statusCode()));
        assertThat(http.send(builder(customer.value(),session,"2025-11-25").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        String renewed=initialized(customer.value());assertThat(renewed).isNotEqualTo(session);
        var live=rpc(customer.value(),renewed,"tools/list",Map.of(),"2025-11-25",3);
        assertThat(live.statusCode()).isEqualTo(200);
        statuses.add(Map.of("stage","reinitialized_session","status",live.statusCode()));
        assertThat(http.send(builder(customer.value(),renewed,"2025-11-25").POST(HttpRequest.BodyPublishers.ofString("null")).build(),
            HttpResponse.BodyHandlers.ofString()).body()).contains("-32600");
        var unsupportedResponse=http.send(builder(customer.value(),renewed,"2025-11-25")
            .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{}}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(unsupportedResponse.statusCode()).isEqualTo(400);
        Path output=Path.of("build/mcp/session-lifecycle.json");Files.createDirectories(output.getParent());
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(statuses));
    }

    @Test void currentAuthorityExpiryRevocationAndAudienceSubstitutionAreEnforcedAtEveryRequest() throws Exception {
        String c=initialized(customer.value()),m=initialized(management.value());
        db.update("DELETE FROM tenant_memberships WHERE tenant_id=? AND principal_id=?",bytes(tenant),bytes(principal));
        assertThat(rpc(management.value(),m,"tools/list",Map.of(),"2025-11-25",1).statusCode()).isEqualTo(403);
        assertThat(rpc(customer.value(),c,"tools/list",Map.of(),"2025-11-25",2).statusCode()).isEqualTo(200);
        access.revokeDelegation(customer.delegationId());
        assertThat(rpc(customer.value(),c,"tools/list",Map.of(),"2025-11-25",3).statusCode()).isEqualTo(401);
        var expired=access.approveDelegation(original.value(),new VenueId(venue),AccessProfile.CUSTOMER,
            Set.of(AccessAction.KNOWLEDGE_PUBLIC),Set.of("test.customer"),Duration.ofMinutes(1));
        db.update("UPDATE auth_access_delegations SET issued_at=UTC_TIMESTAMP(6)-INTERVAL 2 MINUTE,expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE WHERE id=?",bytes(expired.delegationId()));
        assertThat(rpc(expired.value(),null,"initialize",Map.of(),null,4).statusCode()).isEqualTo(401);
        var active=access.approveDelegation(original.value(),new VenueId(venue),AccessProfile.CUSTOMER,
            Set.of(AccessAction.KNOWLEDGE_PUBLIC),Set.of("test.customer"),Duration.ofMinutes(1));
        access.revokeCredential(original.id());
        assertThat(rpc(active.value(),null,"initialize",Map.of(),null,5).statusCode()).isEqualTo(401);
        for(String token:List.of(original.value(),"scrape-SENTINEL-01234567890123456789",operationsToken())) {
            assertThat(rpc(token,null,"initialize",Map.of(),null,6).statusCode()).isEqualTo(401);
            assertThatThrownBy(()->access.validateOriginal(token)).isInstanceOf(AccessFailure.class);
        }
    }
    @Test void productCredentialHasDistinctAudienceExactRouteShortExpiryAndOwnOnlyDespiteOwnerMembership() throws Exception {
        UUID target=UUID.randomUUID();
        var prepared=binding.prepare(new RequestContext(access.authenticateMcp(customer.value()),UUID.randomUUID(),Instant.now(),Instant.now().plusSeconds(30)),
            ProductOperation.RESERVATION_GET,target);
        assertThat(prepared.uri().toString()).isEqualTo(ORIGIN+"/api/v1/venues/"+venue+"/reservations/"+target);
        assertThat(prepared.connectTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(prepared.responseTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(15));
        assertThat(prepared.toString()).doesNotContain(prepared.credential().value());
        var product=access.issueProduct(customer.delegationId(),ProductOperation.RESERVATION_GET,target,Instant.now().plusSeconds(120));
        assertThat(product.value()).isNotEqualTo(customer.value()).isNotEqualTo(original.value());
        assertThat(product.expiresAt()).isBeforeOrEqualTo(Instant.now().plusSeconds(60));
        String path="/api/v1/venues/"+venue+"/reservations/"+target;
        var p=access.authenticateProduct(product.value(),"GET",path).orElseThrow();
        assertThat(p.principalId().value()).isEqualTo(principal);
        var other=new ReservationAccessTarget(new TenantId(tenant),new VenueId(venue),new PrincipalId(UUID.randomUUID()));
        assertThat(authorization.authorizeReservationRead(new AuthenticatedPrincipal(new PrincipalId(principal)),other).isCustomer()).isFalse();
        assertThatThrownBy(()->authorization.authorizeReservationRead(p,other)).isInstanceOf(com.slotq.auth.application.ResourceNotFoundException.class);
        assertThat(authorization.authorizeReservationRead(p,new ReservationAccessTarget(new TenantId(tenant),new VenueId(venue),p.principalId())).isCustomer()).isTrue();
        assertThatThrownBy(()->access.authenticateProduct(product.value(),"POST",path)).isInstanceOf(AccessFailure.class);
        assertThatThrownBy(()->access.authenticateProduct(product.value(),"GET",path+"-other")).isInstanceOf(AccessFailure.class);
        assertThat(rpc(product.value(),null,"initialize",Map.of(),null,1).statusCode()).isEqualTo(401);
        assertThat(get(path,customer.value()).statusCode()).isEqualTo(401);
        assertThat(get(path,"scrape-SENTINEL-01234567890123456789").statusCode()).isEqualTo(401);
        assertThat(get(path,operationsToken()).statusCode()).isEqualTo(401);
        access.revokeDelegation(customer.delegationId());
        assertThat(get(path,product.value()).statusCode()).isEqualTo(401);
    }
    @Test void originMalformedDuplicateAndCorrelationInputsCannotBecomeAuthority() throws Exception {
        String c=initialized(customer.value());
        var injected=HttpRequest.newBuilder(URI.create(ORIGIN+"/mcp")).header("Authorization","Bearer "+customer.value())
            .header("Origin","https://evil.example").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThat(http.send(injected,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        for(String body:List.of("{bad","{\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\"}")) {
            var response=http.send(builder(customer.value(),c,"2025-11-25").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.body()).contains("-32700").doesNotContain(body);
        }
        var request=builder(customer.value(),c,"2025-11-25").header("X-Request-ID","CORRELATION_SENTINEL")
            .header("traceparent","00-11111111111111111111111111111111-2222222222222222-01")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("jsonrpc","2.0","id",1,"method","tools/list")))).build();
        assertThat(http.send(request,HttpResponse.BodyHandlers.ofString()).headers().firstValue("X-Request-ID").orElseThrow()).isNotEqualTo("CORRELATION_SENTINEL");
    }
    @Test void ownOnlyRestrictionUsesPersistedReservationOwnerAtRealProductHttpBoundary() throws Exception {
        var tenant=tenants.createTenant();
        LocalDate day=LocalDate.now(ZoneOffset.UTC).plusDays(1);
        var venue=venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(),"Synthetic Product","UTC",
            new WeeklyOperatingHours(Map.of(day.getDayOfWeek(),new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(22,0)))),
            new BookingPolicyTerms(30,5,20,10)));
        db.update("INSERT INTO tenant_memberships(tenant_id,principal_id,role) VALUES(?,?,'OWNER')",bytes(tenant.id().value()),bytes(principal));
        UUID other=UUID.randomUUID();db.update("INSERT INTO auth_principals(id) VALUES(?)",bytes(other));
        var resource=resources.createResource(new ResourceUseCase.CreateResource(tenant.id(),venue.id(),"Table A",4));
        var slot=slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(),venue.id(),resource.id(),day+"T12:00:00Z"));
        var stored=reservations.createHold(new ReservationUseCase.CreateHold(venue.id(),slot.id(),
            new AuthenticatedPrincipal(new PrincipalId(other)),2)).reservation();
        var delegated=access.approveDelegation(original.value(),venue.id(),AccessProfile.CUSTOMER,
            Set.of(AccessAction.RESERVATION_READ),Set.of("reservation.get"),Duration.ofMinutes(2));
        var credential=access.issueProduct(delegated.delegationId(),ProductOperation.RESERVATION_GET,
            stored.id().value(),Instant.now().plusSeconds(30));
        assertThat(get("/api/v1/venues/"+venue.id().value()+"/reservations/"+stored.id().value(),credential.value()).statusCode()).isEqualTo(404);
        var resourceOwn=resources.createResource(new ResourceUseCase.CreateResource(tenant.id(),venue.id(),"Table B",4));
        var slotOwn=slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(),venue.id(),resourceOwn.id(),day+"T12:00:00Z"));
        var own=reservations.createHold(new ReservationUseCase.CreateHold(venue.id(),slotOwn.id(),
            new AuthenticatedPrincipal(new PrincipalId(principal)),2)).reservation();
        var ownCredential=access.issueProduct(delegated.delegationId(),ProductOperation.RESERVATION_GET,
            own.id().value(),Instant.now().plusSeconds(30));
        assertThat(get("/api/v1/venues/"+venue.id().value()+"/reservations/"+own.id().value(),ownCredential.value()).statusCode()).isEqualTo(200);
    }
    @Test void authorityStoreUnavailableFailsClosedWithSafeResponseAndOriginalAuthoringCannotUseDelegate() throws Exception {
        String session=initialized(customer.value());
        db.execute("RENAME TABLE auth_access_delegations TO auth_access_delegations_unavailable");
        try {
            var response=rpc(customer.value(),session,"tools/list",Map.of(),"2025-11-25",1);
            assertThat(response.statusCode()).isEqualTo(503);assertThat(response.body()).doesNotContain("SQL","auth_access",customer.value());
        }finally{db.execute("RENAME TABLE auth_access_delegations_unavailable TO auth_access_delegations");}
        assertThat(access.requireOriginalConfigurationAccess(original.value(),new VenueId(venue)).principalId().value()).isEqualTo(principal);
        assertThatThrownBy(()->access.requireOriginalConfigurationAccess(customer.value(),new VenueId(venue))).isInstanceOf(AccessFailure.class);
        assertThatThrownBy(()->access.requireOriginalConfigurationAccess(management.value(),new VenueId(venue))).isInstanceOf(AccessFailure.class);
        assertThatThrownBy(()->access.approveDelegation(original.value(),new VenueId(venue),AccessProfile.CUSTOMER,
            Set.of(AccessAction.MANAGEMENT_READ),Set.of("test.management"),Duration.ofMinutes(1))).isInstanceOf(AccessFailure.class);
        assertThatThrownBy(()->access.approveDelegation(original.value(),new VenueId(venue),AccessProfile.CUSTOMER,
            Set.of(AccessAction.KNOWLEDGE_PUBLIC),Set.of("test.customer"),Duration.ofMinutes(16))).isInstanceOf(AccessFailure.class);
    }
    @Test void exactExpiryAndNewCurrentReadIgnorePriorRepeatableReadSnapshot() {
        var observed=access.authenticateMcp(customer.value());
        var atExpiry=new ActorAccessService(db,authorization,Clock.fixed(observed.expiresAt(),ZoneOffset.UTC),transactions);
        assertThatThrownBy(()->atExpiry.authenticateMcp(customer.value())).isInstanceOf(AccessFailure.class);
        var outer=new org.springframework.transaction.support.TransactionTemplate(transactions);
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.execute(status->{
            db.queryForObject("SELECT revoked_at IS NULL FROM auth_access_delegations WHERE id=?",Boolean.class,bytes(customer.delegationId()));
            access.revokeDelegation(customer.delegationId());
            assertThat(db.queryForObject("SELECT revoked_at IS NULL FROM auth_access_delegations WHERE id=?",Boolean.class,bytes(customer.delegationId()))).isTrue();
            assertThatThrownBy(()->access.revalidate(customer.delegationId())).isInstanceOf(AccessFailure.class);
            return null;
        });
    }
    @Test void managementHistoryAuthorityDoesNotInventActiveVenueRequirementWhileCustomerScopeDoes() {
        db.update("UPDATE venues SET status='INACTIVE' WHERE id=?",bytes(venue));
        assertThat(access.authenticateMcp(management.value()).profile()).isEqualTo(AccessProfile.MANAGEMENT);
        assertThatThrownBy(()->access.authenticateMcp(customer.value())).isInstanceOf(AccessFailure.class);
        assertThatThrownBy(()->access.requireOriginalConfigurationAccess(original.value(),new VenueId(venue))).isInstanceOf(AccessFailure.class);
    }
    @Test void actualServletConnectorPreservesProductThreadReserveAndFiniteIngress() throws Exception {
        Object server=context.getClass().getMethod("getWebServer").invoke(context);
        var tomcat=(org.apache.catalina.startup.Tomcat)server.getClass().getMethod("getTomcat").invoke(server);
        var connector=tomcat.getConnector();
        assertThat(connector.getProperty("maxThreads").toString()).isEqualTo("64");
        assertThat(connector.getProperty("maxConnections").toString()).isEqualTo("128");
        assertThat(connector.getProperty("acceptCount").toString()).isEqualTo("16");
        assertThat(connector.getProperty("connectionUploadTimeout").toString()).isEqualTo("2000");
    }
    @Test void realHttpTimeoutLeavesContinuingHandlerAccountedAndRejectsAnotherAttempt() throws Exception {
        CONTINUING_ENTERED=new java.util.concurrent.CountDownLatch(1);CONTINUING_EXIT=new java.util.concurrent.CountDownLatch(1);
        String session=initialized(customer.value());
        try {
            var timedOut=rpc(customer.value(),session,"tools/call",Map.of("name","test.customer",
                "arguments",Map.of("query","continue","count",1)),"2025-11-25",1);
            assertThat(CONTINUING_ENTERED.await(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(timedOut.body()).contains("unknown");
            assertThat(engine.activeWorkers()).isEqualTo(1);
            var denied=rpc(customer.value(),session,"tools/call",Map.of("name","test.customer",
                "arguments",Map.of("query","x","count",1)),"2025-11-25",2);
            assertThat(denied.body()).contains("rate_limited","not_dispatched");
        }finally{CONTINUING_EXIT.countDown();}
        org.awaitility.Awaitility.await().untilAsserted(()->assertThat(engine.activeWorkers()).isZero());
    }
    String initialized(String token) throws Exception {
        String id=rpc(token,null,"initialize",Map.of("protocolVersion","2025-11-25","capabilities",Map.of(),
            "clientInfo",Map.of("name","test","version","1")),null,1).headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(rpc(token,id,"notifications/initialized",Map.of(),"2025-11-25",null).statusCode()).isEqualTo(202);return id;
    }
    HttpResponse<String> rpc(String token,String session,String method,Object params,String revision,Object id) throws Exception {
        Map<String,Object> rpc=new LinkedHashMap<>();rpc.put("jsonrpc","2.0");rpc.put("method",method);rpc.put("params",params);
        if(id!=null)rpc.put("id",id);
        return http.send(builder(token,session,revision).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(rpc))).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpRequest.Builder builder(String token,String session,String revision) {
        var b=HttpRequest.newBuilder(URI.create(ORIGIN+"/mcp")).timeout(Duration.ofSeconds(4))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Accept","application/json, text/event-stream");
        if(session!=null)b.header("Mcp-Session-Id",session);if(revision!=null)b.header("MCP-Protocol-Version",revision);return b;
    }
    HttpResponse<String> get(String path,String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(ORIGIN+path)).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    String operationsToken() throws Exception {
        String token="sqop_"+HexFormat.of().formatHex(SecureRandom.getSeed(32));UUID op=UUID.randomUUID();
        db.update("INSERT INTO operations_operators(operator_id,principal_reference,active) VALUES(?, ?,TRUE)",bytes(op),"synthetic-operator-"+op);
        db.update("INSERT INTO operations_credentials(credential_id,operator_id,token_hash,expires_at) VALUES(?,?,?,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)",
            bytes(UUID.randomUUID()),bytes(op),MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        assertThat(operations.authenticate(token)).isPresent();return token;
    }
    static byte[] bytes(UUID u){return ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();}
    static int port(){try(var socket=new java.net.ServerSocket(0)){return socket.getLocalPort();}catch(Exception e){throw new IllegalStateException(e);}}
    static Path keystore() {
        try {
            Path path=Files.createTempDirectory("slotq-mcp-test-").resolve("test.p12");
            Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),"-genkeypair",
                "-alias","test","-keyalg","RSA","-storetype","PKCS12","-keystore",path.toString(),"-storepass",PASSWORD,
                "-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","2").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();if(p.waitFor()!=0)throw new IllegalStateException("test TLS fixture");return path;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    static SSLContext tls() throws Exception {
        var context=SSLContext.getInstance("TLS");context.init(null,new TrustManager[]{new X509TrustManager(){
            public java.security.cert.X509Certificate[] getAcceptedIssuers(){return new java.security.cert.X509Certificate[0];}
            public void checkClientTrusted(java.security.cert.X509Certificate[] c,String a){}
            public void checkServerTrusted(java.security.cert.X509Certificate[] c,String a){}
        }},new SecureRandom());return context;
    }
    @TestConfiguration static class Registrations {
        @Bean McpRegistrations syntheticTools(){return ()->List.of(
            McpFoundationTests.tool("test.customer",AccessProfile.CUSTOMER,(c,i)->{
                if("continue".equals(i.get("query"))){CONTINUING_ENTERED.countDown();CONTINUING_EXIT.await();}
                return ToolOutcome.success(Map.of("scope",c.actor().tenantId().value().toString()));
            }),
            McpFoundationTests.tool("test.management",AccessProfile.MANAGEMENT,(c,i)->ToolOutcome.success(Map.of("scope",c.actor().tenantId().value().toString()))));}
    }
}
