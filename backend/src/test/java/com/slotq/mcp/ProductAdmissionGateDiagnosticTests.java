package com.slotq.mcp;

import com.slotq.auth.access.*;
import com.slotq.auth.domain.*;
import com.slotq.auth.persistence.ActorAccessService;
import com.slotq.booking.application.*;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import jakarta.servlet.http.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Repeats the original fault at the same post-security/pre-controller boundary. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "slotq.mcp.enabled=true", "server.ssl.enabled=true", "slotq.auth.dev-bootstrap-enabled=true",
    "spring.datasource.hikari.connection-timeout=2000",
    "spring.datasource.hikari.data-source-properties.socketTimeout=2000",
    "spring.datasource.hikari.data-source-properties.connectTimeout=2000",
    "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=2, lock_wait_timeout=2, wait_timeout=5, max_execution_time=2000",
    "spring.transaction.default-timeout=10s"})
@ActiveProfiles("test")
@Import(ProductAdmissionGateDiagnosticTests.PauseConfiguration.class)
class ProductAdmissionGateDiagnosticTests {
    @Container @ServiceConnection static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4")
        .withCommand("--log-bin-trust-function-creators=1").withDatabaseName("slotq");
    static final int PORT=McpHttpIntegrationTests.port();
    static final String ORIGIN="https://localhost:"+PORT;
    static final Path KEYSTORE=McpHttpIntegrationTests.KEYSTORE;
    static final JsonMapper JSON=JsonMapper.builder().build();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("server.port",()->PORT);p.add("slotq.mcp.origin",()->ORIGIN);p.add("slotq.mcp.product-origin",()->ORIGIN);
        p.add("server.ssl.key-store",()->KEYSTORE.toUri().toString());
        p.add("server.ssl.key-store-password",()->McpHttpIntegrationTests.PASSWORD);
        p.add("server.ssl.key-store-type",()->"PKCS12");
    }
    @Autowired JdbcTemplate db;
    @Autowired ActorAccessService access;
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired ReservationUseCase reservations;
    @MockitoSpyBean ReservationRepository productRepository;
    @Autowired Pause pause;
    @Autowired ToolRegistry registry;

    @Test void expiredProductRequestFailsAtApplicationAdmissionWhileOrdinaryProductSemanticsRemain() throws Exception {
        HttpClient http=HttpClient.newBuilder().sslContext(McpHttpIntegrationTests.tls())
            .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        var tenant=tenants.createTenant();
        LocalDate day=LocalDate.now(ZoneOffset.UTC).plusDays(1);
        var venue=venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(),"Admission diagnostic","UTC",
            new WeeklyOperatingHours(Map.of(day.getDayOfWeek(),new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(22,0)))),
            new BookingPolicyTerms(30,5,20,10)));
        var resource=resources.createResource(new ResourceUseCase.CreateResource(tenant.id(),venue.id(),"Diagnostic table",4));
        var slot=slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(),venue.id(),resource.id(),day+"T12:00:00Z"));
        UUID principal=UUID.randomUUID();
        db.update("INSERT INTO auth_principals(id) VALUES(?)",McpHttpIntegrationTests.bytes(principal));
        var existing=reservations.createHold(new ReservationUseCase.CreateHold(venue.id(),slot.id(),
            new AuthenticatedPrincipal(new PrincipalId(principal)),2)).reservation();
        var original=access.provisionOriginal(new PrincipalId(principal),Instant.now().plusSeconds(600));
        var delegated=access.approveDelegation(original.value(),venue.id(),AccessProfile.CUSTOMER,
            Set.of(AccessAction.RESERVATION_READ,AccessAction.RESERVATION_WRITE),Set.of("reservation.get","reservation.hold"),Duration.ofMinutes(5));
        var product=access.issueProduct(delegated.delegationId(),ProductOperation.RESERVATION_GET,
            existing.id().value(),Instant.now().plusSeconds(60));
        String readPath="/api/v1/venues/"+venue.id().value()+"/reservations/"+existing.id().value();
        String holdPath="/api/v1/venues/"+venue.id().value()+"/reservations/holds";
        var resourceHold=resources.createResource(new ResourceUseCase.CreateResource(tenant.id(),venue.id(),"Diagnostic HOLD",4));
        var slotHold=slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(),venue.id(),resourceHold.id(),day+"T12:00:00Z"));
        var login=http.send(HttpRequest.newBuilder(java.net.URI.create(ORIGIN+"/__dev/auth/session"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"fixtureKey\":\"customer-a\"}"))
            .build(),HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        String devToken=JSON.readTree(login.body()).path("accessToken").asString();
        // A dev Product credential exercises the existing HOLD transaction only. It is NOT an MCP write credential.
        assertThat(devToken).isNotBlank();
        String key=UUID.randomUUID().toString();
        var guardedResource=resources.createResource(new ResourceUseCase.CreateResource(tenant.id(),venue.id(),"Guarded HOLD",4));
        var guardedSlot=slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(),venue.id(),guardedResource.id(),day+"T12:00:00Z"));
        String guardedKey=UUID.randomUUID().toString();
        var guardedCredential=access.issueHold(delegated.delegationId(),guardedSlot.id().value(),2,guardedKey,Instant.now().plusSeconds(60));
        pause.arm(Set.of(readPath,holdPath));
        Instant sentAt=Instant.now();long start=System.nanoTime();
        var read=http.sendAsync(HttpRequest.newBuilder(java.net.URI.create(ORIGIN+readPath)).timeout(Duration.ofSeconds(80))
            .header("Authorization","Bearer "+product.value()).GET().build(),HttpResponse.BodyHandlers.ofString());
        var hold=http.sendAsync(HttpRequest.newBuilder(java.net.URI.create(ORIGIN+holdPath)).timeout(Duration.ofSeconds(15))
            .header("Authorization","Bearer "+devToken).header("Content-Type","application/json").header("Idempotency-Key",key)
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("slotInventoryId",slotHold.id().value(),"partySize",2))))
            .build(),HttpResponse.BodyHandlers.ofString());
        var guardedHold=http.sendAsync(HttpRequest.newBuilder(java.net.URI.create(ORIGIN+holdPath)).timeout(Duration.ofSeconds(80))
            .header("Authorization","Bearer "+guardedCredential.value()).header("Content-Type","application/json").header("Idempotency-Key",guardedKey)
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("slotInventoryId",guardedSlot.id().value(),"partySize",2))))
            .build(),HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(pause.entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(pause.authenticated).hasValue(3);
            assertThatThrownBy(()->hold.get(20,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(HttpTimeoutException.class);
            assertThat(count("reservations",slotHold.id().value())).isZero();
            assertThat(count("capacity_allocations",slotHold.id().value())).isZero();
            // Real wall-clock suspension beyond the configured activation bound, not an injected Clock.
            long remaining=TimeUnit.SECONDS.toNanos(61)-(System.nanoTime()-start);
            if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining);
            Instant releasedAt=Instant.now();pause.release.countDown();
            var response=read.get(15,TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(guardedHold.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(401);
            assertThat(count("reservations",guardedSlot.id().value())).isZero();
            assertThat(count("capacity_allocations",guardedSlot.id().value())).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM hold_idempotency_records WHERE idempotency_key=?",Integer.class,guardedKey)).isZero();
            org.mockito.Mockito.verify(productRepository, org.mockito.Mockito.never()).find(venue.id(), existing.id());
            assertThat(Instant.now()).isAfter(product.expiresAt());
            await().atMost(Duration.ofSeconds(10)).untilAsserted(()->{
                assertThat(count("reservations",slotHold.id().value())).isEqualTo(1);
                assertThat(count("capacity_allocations",slotHold.id().value())).isEqualTo(1);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM hold_idempotency_records WHERE idempotency_key=? AND state='COMPLETED'",
                    Integer.class,key)).isEqualTo(1);
            });
            var durable=db.queryForMap("SELECT HEX(r.id) reservationId,HEX(r.slot_inventory_id) slotId,HEX(r.customer_principal_id) customerId,"
                +"r.state,HEX(a.id) allocationId,h.state idempotencyState,h.completed_at completedAt "
                +"FROM reservations r JOIN capacity_allocations a ON a.reservation_id=r.id "
                +"JOIN hold_idempotency_records h ON h.reservation_id=r.id WHERE r.slot_inventory_id=?",McpHttpIntegrationTests.bytes(slotHold.id().value()));
            Map<String,Object> evidence=new LinkedHashMap<>();
            evidence.put("gate","PASS: expired PRODUCT credential rejected at application admission; ordinary HOLD unchanged");
            evidence.put("sentAt",sentAt.toString());evidence.put("releasedAt",releasedAt.toString());
            evidence.put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
            evidence.put("authenticatedPausedRequests",pause.authenticated.get());
            evidence.put("productCredentialExpiredAt",product.expiresAt().toString());evidence.put("lateProductGetStatus",response.statusCode());
            evidence.put("lateNarrowedHoldStatus",401);evidence.put("lateNarrowedHoldDurableRows",Map.of("reservations",0,"allocations",0,"idempotency",0));
            evidence.put("holdClientObservation","HttpTimeoutException at 15 seconds; subsequent MySQL COMMIT observed");
            evidence.put("ordinaryHoldAuthentication","existing local/test-only dev Product credential");
            evidence.put("guardedHoldAuthentication","Auth-issued material-bound PRODUCT audience credential");
            evidence.put("durableProductRows",durable);evidence.put("diagnosticRegistrySize",registry.all().size());
            evidence.put("fixtureOverridesProductionRegistry",true);
            evidence.put("pauseLayer","test-only HandlerInterceptor after Product security authentication, before controller/application/transaction");
            Path output=Path.of("build/mcp-product-admission/guard.json");Files.createDirectories(output.getParent());
            Files.writeString(output,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
            assertThat(registry.all()).isEmpty();
        } finally {pause.release.countDown();}
    }

    private int count(String table,UUID slot) {return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE slot_inventory_id=?",
        Integer.class,McpHttpIntegrationTests.bytes(slot));}
    static final class Pause implements HandlerInterceptor {
        volatile Set<String> paths=Set.of();
        volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
        final java.util.concurrent.atomic.AtomicInteger authenticated=new java.util.concurrent.atomic.AtomicInteger();
        void arm(Set<String> paths){this.paths=paths;entered=new CountDownLatch(3);release=new CountDownLatch(1);}
        @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws Exception {
            if(paths.contains(request.getRequestURI())){
                var identity=SecurityContextHolder.getContext().getAuthentication();
                if(identity==null || !identity.isAuthenticated() || !(identity.getPrincipal() instanceof AuthenticatedPrincipal))
                    throw new AssertionError("Pause must be strictly after authenticated Product security");
                authenticated.incrementAndGet();entered.countDown();
                if(!release.await(80,TimeUnit.SECONDS))throw new AssertionError("Diagnostic pause not released");
            }
            return true;
        }
    }
    @TestConfiguration static class PauseConfiguration {
        @Bean McpRegistrations diagnosticRoot(){return List::of;}
        @Bean Pause diagnosticPause(){return new Pause();}
        @Bean WebMvcConfigurer diagnosticInterceptor(Pause pause){return new WebMvcConfigurer(){
            @Override public void addInterceptors(InterceptorRegistry r){r.addInterceptor(pause);}
        };}
    }
}
