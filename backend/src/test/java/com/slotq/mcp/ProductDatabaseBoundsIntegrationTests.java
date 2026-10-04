package com.slotq.mcp;

import com.slotq.auth.access.*;
import com.slotq.auth.domain.*;
import com.slotq.auth.persistence.ActorAccessService;
import com.slotq.booking.application.*;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Server/database termination is observed separately from the client response budget. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "slotq.mcp.enabled=true","server.ssl.enabled=true","slotq.auth.dev-bootstrap-enabled=true",
    "spring.datasource.hikari.connection-timeout=2000","spring.datasource.hikari.maximum-pool-size=12",
    "spring.datasource.hikari.data-source-properties.socketTimeout=2000",
    "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=2, lock_wait_timeout=2, wait_timeout=5, max_execution_time=2000",
    "spring.transaction.default-timeout=10s","spring.jpa.properties.jakarta.persistence.query.timeout=2000"})
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(ProductDatabaseBoundsIntegrationTests.IsolatedRoot.class)
class ProductDatabaseBoundsIntegrationTests {
    @org.springframework.boot.test.context.TestConfiguration static class IsolatedRoot {
        @org.springframework.context.annotation.Bean McpRegistrations diagnosticRoot(){return List::of;}
    }
    @Container @ServiceConnection static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
        .withCommand("--log-bin-trust-function-creators=1").withDatabaseName("slotq").withEnv("MYSQL_ROOT_HOST","%");
    static final int PORT=McpHttpIntegrationTests.port();static final String ORIGIN="https://localhost:"+PORT;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("server.port",()->PORT);p.add("slotq.mcp.origin",()->ORIGIN);p.add("slotq.mcp.product-origin",()->ORIGIN);
        p.add("server.ssl.key-store",()->McpHttpIntegrationTests.KEYSTORE.toUri().toString());
        p.add("server.ssl.key-store-password",()->McpHttpIntegrationTests.PASSWORD);p.add("server.ssl.key-store-type",()->"PKCS12");
    }
    @Autowired HikariDataSource pool;
    @Autowired JdbcTemplate db;
    @Autowired ActorAccessService access;
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @MockitoSpyBean HoldIdempotencyStore idempotency;
    HttpClient http;VenueId venue;UUID slot;String token,key;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void fixture() throws Exception {
        http=HttpClient.newBuilder().sslContext(McpHttpIntegrationTests.tls()).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2)).build();
        var tenant=tenants.createTenant();LocalDate day=LocalDate.now(ZoneOffset.UTC).plusDays(1);
        var stored=venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(),"Bound fixture","UTC",
            new WeeklyOperatingHours(Map.of(day.getDayOfWeek(),new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(22,0)))),
            new BookingPolicyTerms(30,5,20,10)));venue=stored.id();
        var resource=resources.createResource(new ResourceUseCase.CreateResource(tenant.id(),venue,"Bound table",4));
        slot=slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(),venue,resource.id(),day+"T12:00:00Z")).id().value();
        var login=http.send(HttpRequest.newBuilder(URI.create(ORIGIN+"/__dev/auth/session")).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"fixtureKey\":\"customer-a\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);token=json.readTree(login.body()).path("accessToken").asString();key=UUID.randomUUID().toString();
    }
    @Test void poolExhaustionRejectsAtAuthorityAcquisitionThenRecoversWithoutBusinessEffect() throws Exception {
        UUID subject=UUID.fromString("10000000-0000-0000-0000-000000000001");
        var original=access.provisionOriginal(new PrincipalId(subject),Instant.now().plusSeconds(600));
        var delegate=access.approveDelegation(original.value(),venue,AccessProfile.CUSTOMER,
            Set.of(AccessAction.RESERVATION_READ),Set.of("reservation.get"),Duration.ofMinutes(5));
        var credential=access.issueProduct(delegate.delegationId(),ProductOperation.RESERVATION_GET,UUID.randomUUID(),Instant.now().plusSeconds(30));
        List<Connection> held=new ArrayList<>();long start;
        try {
            for(int i=0;i<pool.getMaximumPoolSize();i++)held.add(pool.getConnection());
            start=System.nanoTime();
            var response=http.send(HttpRequest.newBuilder(URI.create(ORIGIN+"/api/v1/venues/"+venue.value()+"/reservations/"+UUID.randomUUID()))
                .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+credential.value()).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);assertElapsed(start,6);
        } finally {for(Connection connection:held)connection.close();}
        assertNoEffects();
        assertThat(hold().statusCode()).isEqualTo(201);assertEffects(1);
    }
    @Test void actualSlotLockWaitTerminatesOnServerAndRollsBackBeforeLockOwnerRelease() throws Exception {
        try(Connection locker=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword())) {
            locker.setAutoCommit(false);
            try(var statement=locker.prepareStatement("SELECT id FROM slot_inventories WHERE id=? FOR UPDATE")) {
                statement.setBytes(1,McpHttpIntegrationTests.bytes(slot));statement.executeQuery().close();
            }
            long start=System.nanoTime();var response=hold();
            assertThat(response.statusCode()).isEqualTo(500);assertElapsed(start,8);
            await().atMost(Duration.ofSeconds(6)).untilAsserted(()->assertThat(observer().queryForObject(
                "SELECT COUNT(*) FROM performance_schema.data_lock_waits",Integer.class)).isZero());
            assertNoEffects();locker.rollback();
        }
        assertThat(hold().statusCode()).isEqualTo(201);assertEffects(1);
    }
    @Test void jdbcQueryTimeoutAndSocketFailureAreNotMistakenForBusinessCommit() throws Exception {
        doAnswer(call->{
            if(((HoldIdempotencyKey)call.getArgument(2)).value().equals(key)) {
                var bounded=new JdbcTemplate(pool);bounded.setQueryTimeout(2);
                bounded.queryForObject("SELECT 1 FROM venues WHERE SLEEP(20)=0 /* product-bound-query */",Integer.class);
            }
            return call.callRealMethod();
        }).when(idempotency).complete(any(),any(),any(),any(),any());
        long start=System.nanoTime();assertThat(hold().statusCode()).isEqualTo(500);assertElapsed(start,8);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(observer().queryForObject(
            "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE INFO LIKE 'SELECT 1 FROM venues WHERE SLEEP(20)%'",Integer.class)).isZero());
        assertNoEffects();
    }
    @Test void serverIdleTransactionTerminationRollsBackAllThreeRowsWhileJavaWorkIsStillAlive() throws Exception {
        CountDownLatch wrote=new CountDownLatch(1),release=new CountDownLatch(1);AtomicLong connection=new AtomicLong();
        doAnswer(call->{
            call.callRealMethod();
            if(((HoldIdempotencyKey)call.getArgument(2)).value().equals(key)) {
                connection.set(db.queryForObject("SELECT CONNECTION_ID()",Long.class));
                assertEffects(1);wrote.countDown();
                if(!release.await(20,TimeUnit.SECONDS))throw new AssertionError("Unreleased test transaction");
            }
            return null;
        }).when(idempotency).complete(any(),any(),any(),any(),any());
        var pending=http.sendAsync(request(),HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(wrote.await(5,TimeUnit.SECONDS)).isTrue();
            assertNoEffects();long start=System.nanoTime();
            await().atMost(Duration.ofSeconds(12)).untilAsserted(()->assertThat(observer().queryForObject(
                "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=?",Integer.class,connection.get())).isZero());
            assertElapsed(start,12);assertThat(pending.isDone()).isFalse();assertNoEffects();
            release.countDown();assertThat(pending.get(5,TimeUnit.SECONDS).statusCode()).isEqualTo(500);assertNoEffects();
        } finally {release.countDown();}
    }
    @Test void realDatabasePauseReturnsUnavailableThenRecoveryHasNoUnobservedEffect() throws Exception {
        UUID subject=UUID.fromString("10000000-0000-0000-0000-000000000001");
        var original=access.provisionOriginal(new PrincipalId(subject),Instant.now().plusSeconds(600));
        var delegate=access.approveDelegation(original.value(),venue,AccessProfile.CUSTOMER,
            Set.of(AccessAction.RESERVATION_READ),Set.of("reservation.get"),Duration.ofMinutes(5));
        UUID target=UUID.randomUUID();
        var product=access.issueProduct(delegate.delegationId(),ProductOperation.RESERVATION_GET,target,Instant.now().plusSeconds(30));
        MYSQL.getDockerClient().pauseContainerCmd(MYSQL.getContainerId()).exec();
        try {
            long start=System.nanoTime();
            var response=http.send(HttpRequest.newBuilder(URI.create(ORIGIN+"/api/v1/venues/"+venue.value()+"/reservations/"+target))
                .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+product.value()).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);assertElapsed(start,8);
        } finally {MYSQL.getDockerClient().unpauseContainerCmd(MYSQL.getContainerId()).exec();}
        await().atMost(Duration.ofSeconds(10)).untilAsserted(this::assertNoEffects);
        assertThat(hold().statusCode()).isEqualTo(201);assertEffects(1);
    }
    private HttpRequest request(){return HttpRequest.newBuilder(URI.create(ORIGIN+"/api/v1/venues/"+venue.value()+"/reservations/holds"))
        .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
        .header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("slotInventoryId",slot,"partySize",2)))).build();}
    private HttpResponse<String> hold() throws Exception{return http.send(request(),HttpResponse.BodyHandlers.ofString());}
    private void assertNoEffects(){assertEffects(0);}
    private void assertEffects(int expected){
        for(String table:List.of("reservations","capacity_allocations","hold_idempotency_records"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE slot_inventory_id=?",Integer.class,McpHttpIntegrationTests.bytes(slot))).isEqualTo(expected);
    }
    private static void assertElapsed(long start,int seconds){assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(seconds));}
    private static JdbcTemplate observer(){
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword()));
    }
}
