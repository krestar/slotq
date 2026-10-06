package com.slotq.mcp;

import com.slotq.auth.access.*;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.persistence.ActorAccessService;
import com.slotq.venue.domain.VenueId;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** A separate live instance with finite, slowly refilling quotas; no limiter mocks/reset API. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "slotq.mcp.enabled=true","server.ssl.enabled=true",
    "spring.datasource.hikari.connection-timeout=2000",
    "spring.datasource.hikari.data-source-properties.socketTimeout=2000","spring.datasource.hikari.data-source-properties.connectTimeout=2000",
    "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=2, lock_wait_timeout=2, wait_timeout=5, max_execution_time=2000",
    "spring.transaction.default-timeout=10s","spring.jpa.properties.jakarta.persistence.query.timeout=2000",
    "slotq.mcp.quota.rate=0.0001","slotq.mcp.quota.burst=4"})
@Import(ProductToolsIntegrationTests.FaultConfiguration.class)
class M6RateLimitIntegrationTests {
    @Container @ServiceConnection static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
        .withCommand("--log-bin-trust-function-creators=1").withDatabaseName("m6rate");
    static final int PORT=McpHttpIntegrationTests.port();static final String ORIGIN="https://localhost:"+PORT;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("server.port",()->PORT);p.add("slotq.mcp.origin",()->ORIGIN);p.add("slotq.mcp.product-origin",()->ORIGIN);
        p.add("server.ssl.key-store",()->McpHttpIntegrationTests.KEYSTORE.toUri().toString());
        p.add("server.ssl.key-store-password",()->McpHttpIntegrationTests.PASSWORD);p.add("server.ssl.key-store-type",()->"PKCS12");
    }
    @Autowired ActorAccessService access;@Autowired JdbcTemplate db;
    final JsonMapper json=JsonMapper.builder().build();HttpClient http;int id;
    @Test void denialsAndSchemaFailuresConsumeQuotaAndNewSessionDelegationPrincipalOrToolCannotBypassIt() throws Exception {
        http=HttpClient.newBuilder().sslContext(McpHttpIntegrationTests.tls()).followRedirects(HttpClient.Redirect.NEVER).build();
        UUID tenant=UUID.randomUUID();VenueId venue=new VenueId(UUID.randomUUID());
        db.update("INSERT INTO tenants(id,status) VALUES(?,'ACTIVE')",McpHttpIntegrationTests.bytes(tenant));
        db.update("INSERT INTO venues(id,tenant_id,status,timezone,name) VALUES(?,?,'ACTIVE','UTC','Synthetic')",McpHttpIntegrationTests.bytes(venue.value()),McpHttpIntegrationTests.bytes(tenant));
        String original=original();var first=delegate(original,venue);String session=initialize(first);
        assertThat(rpc(first,session,"tools/list",Map.of()).path("result").path("tools").size()).isEqualTo(3);
        assertThat(call(first,session,"knowledge.search",Map.of("query","missing")).path("category").asString()).isEqualTo("no_answer");
        assertThat(call(first,session,"management.reservations.list",Map.of("date","2026-10-06")).path("category").asString()).isEqualTo("forbidden");
        assertThat(call(first,session,"reservation.get",Map.of("reservationId","malformed")).path("category").asString()).isEqualTo("validation");
        for(String tool:List.of("knowledge.search","reservation.get","reservation.hold","unknown.rotated"))
            limited(call(first,session,tool,Map.of("query","missing")));
        // Reinitialization does not reset the original principal's debt.
        limited(call(first,initialize(first),"knowledge.search",Map.of("query","missing")));
        var renewed=delegate(original,venue);limited(call(renewed,initialize(renewed),"knowledge.search",Map.of("query","missing")));
        // New principal/delegation still shares the exhausted Tenant aggregate.
        var stranger=delegate(original(),venue);limited(call(stranger,initialize(stranger),"reservation.get",Map.of("reservationId",UUID.randomUUID())));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM reservations",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM hold_idempotency_records",Integer.class)).isZero();
    }
    String original() {UUID principal=UUID.randomUUID();db.update("INSERT INTO auth_principals(id) VALUES(?)",McpHttpIntegrationTests.bytes(principal));return access.provisionOriginal(new PrincipalId(principal),Instant.now().plusSeconds(3600)).value();}
    String delegate(String original,VenueId venue) {return access.approveDelegation(original,venue,AccessProfile.CUSTOMER,Set.of(AccessAction.KNOWLEDGE_PUBLIC,AccessAction.RESERVATION_READ,AccessAction.RESERVATION_WRITE),Set.of("knowledge.search","reservation.get","reservation.hold"),Duration.ofMinutes(15)).value();}
    String initialize(String token) throws Exception {
        var response=send(token,null,"initialize",Map.of("protocolVersion","2025-11-25","capabilities",Map.of(),"clientInfo",Map.of("name","m6rate","version","1")));
        assertThat(response.statusCode()).isEqualTo(200);String session=response.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(send(token,session,"notifications/initialized",Map.of()).statusCode()).isEqualTo(202);return session;
    }
    tools.jackson.databind.JsonNode call(String token,String session,String tool,Map<String,?> args) throws Exception {return rpc(token,session,"tools/call",Map.of("name",tool,"arguments",args)).path("result").path("structuredContent");}
    tools.jackson.databind.JsonNode rpc(String token,String session,String method,Map<String,?> params) throws Exception {var response=send(token,session,method,params);assertThat(response.statusCode()).isEqualTo(200);return json.readTree(response.body());}
    HttpResponse<String> send(String token,String session,String method,Map<String,?> params) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("jsonrpc","2.0");body.put("method",method);body.put("params",params);if(!method.startsWith("notifications/"))body.put("id",++id);
        var request=HttpRequest.newBuilder(URI.create(ORIGIN+"/mcp")).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version","2025-11-25");if(session!=null)request.header("Mcp-Session-Id",session);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    void limited(tools.jackson.databind.JsonNode content) {assertThat(content.path("category").asString()).isEqualTo("rate_limited");assertThat(content.path("outcome").asString()).isEqualTo("not_dispatched");}
}
