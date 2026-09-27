package com.slotq;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.*;
import com.slotq.auth.web.BearerCredentialResolver;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.events.application.EventDeliveryWorker;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.*;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, MySQL, registration and handlers; no seeded Offer, receipt or DONE. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "slotq.waitlist.promotion.enabled=true", "slotq.events.delivery.scheduler-enabled=true",
    "slotq.waitlist.promotion.maintenance-enabled=true"
})
@Import(ProductObservabilityIntegrationTests.Configuration.class)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ProductObservabilityIntegrationTests {
    static final Instant NOW = Instant.parse("2026-08-30T09:00:00Z");
    static final String MARKER = "private-email@example.invalid-sql-password-body";
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withDatabaseName("slotq_observability").withCommand("--log-bin-trust-function-creators=1");
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired AccessControlProvisioning access;
    @Autowired EventDeliveryWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired SdkTracerProvider provider;
    @Autowired SwitchableExporter exporter;
    @Autowired Credentials credentials;
    @LocalServerPort int port;
    final JsonMapper json = new JsonMapper();
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void resetExporter() { exporter.failed.set(false); provider.forceFlush().join(5, TimeUnit.SECONDS); exporter.delegate.reset(); }
    @AfterEach void cleanupFault() { jdbc.execute("DROP TRIGGER IF EXISTS fail_observation_append"); exporter.failed.set(false); }

    @Test void httpRequestAppendAndRealOfferCommitHaveLinkedTracesWithNoSensitiveData() throws Exception {
        Fixture fixture = fixture();
        String owner = customer(), waiter = customer();
        var hold = post(fixture.base() + "/reservations/holds", owner, fixture.body());
        assertThat(hold.statusCode()).isEqualTo(201);
        String reservation = json.readTree(hold.body()).get("id").asString();
        assertThat(post(fixture.base() + "/waitlist-entries", waiter, fixture.body()).statusCode()).isEqualTo(201);
        var conflict = post(fixture.base() + "/reservations/holds", waiter, fixture.body());
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("CAPACITY_UNAVAILABLE");
        var release = post(fixture.base() + "/reservations/" + reservation + "/cancel", owner, null);
        assertThat(release.statusCode()).isEqualTo(200);
        String requestId = release.headers().firstValue("X-Request-ID").orElseThrow();
        Map<String,Object> event = jdbc.queryForMap("SELECT HEX(event_id) event_id, origin_request_id, origin_trace_id, origin_span_id FROM event_records WHERE aggregate_id=?", bytes(UUID.fromString(reservation)));
        assertThat(event.get("origin_request_id")).isEqualTo(requestId);
        worker.runCycle();
        assertThat(jdbc.queryForObject("SELECT p.outcome FROM waitlist_promotion_receipts p JOIN event_records e ON e.event_id=p.event_id WHERE e.aggregate_id=?", String.class, bytes(UUID.fromString(reservation)))).isEqualTo("PROMOTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_offers WHERE venue_id=?", Integer.class, bytes(fixture.venue()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT d.state FROM event_deliveries d JOIN event_records e ON e.event_id=d.event_id WHERE e.aggregate_id=?", String.class, bytes(UUID.fromString(reservation)))).isEqualTo("DONE");
        provider.forceFlush().join(5, TimeUnit.SECONDS);
        var spans = exporter.delegate.getFinishedSpanItems();
        SpanData request = spans.stream().filter(s -> s.getName().equals("product.request") && requestId.equals(attr(s,"slotq.request.id"))).findFirst().orElseThrow();
        SpanData append = spans.stream().filter(s -> s.getName().equals("product.event.append") && requestId.equals(attr(s,"slotq.request.id"))).findFirst().orElseThrow();
        SpanData effect = spans.stream().filter(s -> s.getName().equals("product.event.effect") && requestId.equals(attr(s,"slotq.request.id"))).findFirst().orElseThrow();
        assertThat(append.getTraceId()).isEqualTo(request.getTraceId());
        assertThat(append.getParentSpanId()).isEqualTo(request.getSpanId());
        assertThat(attr(append,"slotq.outcome")).isEqualTo("committed");
        assertThat(effect.getTraceId()).isNotEqualTo(request.getTraceId());
        assertThat(effect.getLinks()).anySatisfy(link -> {
            assertThat(link.getSpanContext().getTraceId()).isEqualTo(event.get("origin_trace_id"));
            assertThat(link.getSpanContext().getSpanId()).isEqualTo(event.get("origin_span_id"));
        });
        assertThat(attr(effect,"slotq.outcome")).isEqualTo("committed");
        assertThat(attr(effect,"slotq.promotion.outcome")).isEqualTo("PROMOTED");
        assertThat(spans.toString()).doesNotContain(owner, waiter, MARKER, "db.statement", "url.full");
    }

    @Test void appendFailureRollsBackBusinessAndRetainsFailureRequestCorrelation(org.springframework.boot.test.system.CapturedOutput logs) throws Exception {
        Fixture fixture = fixture(); String owner = customer();
        var hold = post(fixture.base() + "/reservations/holds", owner, fixture.body());
        String reservation = json.readTree(hold.body()).get("id").asString();
        jdbc.execute("CREATE TRIGGER fail_observation_append BEFORE INSERT ON event_records FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='" + MARKER + "'");
        var failed = post(fixture.base() + "/reservations/" + reservation + "/cancel", owner, null);
        assertThat(failed.statusCode()).isEqualTo(500);
        String id = failed.headers().firstValue("X-Request-ID").orElseThrow();
        assertThat(jdbc.queryForObject("SELECT state FROM reservations WHERE id=?",String.class,bytes(UUID.fromString(reservation)))).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_records WHERE aggregate_id=?",Integer.class,bytes(UUID.fromString(reservation)))).isZero();
        provider.forceFlush().join(5, TimeUnit.SECONDS);
        assertThat(exporter.delegate.getFinishedSpanItems()).anySatisfy(span -> {
            assertThat(span.getName()).isEqualTo("product.event.append");
            assertThat(attr(span,"slotq.request.id")).isEqualTo(id);
            assertThat(attr(span,"slotq.outcome")).isEqualTo("rolled_back");
        });
        assertThat(exporter.delegate.getFinishedSpanItems().toString()).doesNotContain(MARKER, owner);
        assertThat(logs.getAll()).doesNotContain(MARKER, owner);
    }

    @Test void exporterOutageDoesNotChangeRequestOrConsumerCommit() throws Exception {
        exporter.failed.set(true); Fixture fixture = fixture(); String owner = customer(), waiter = customer();
        var held = post(fixture.base()+"/reservations/holds",owner,fixture.body());
        String id = json.readTree(held.body()).get("id").asString();
        assertThat(post(fixture.base()+"/waitlist-entries",waiter,fixture.body()).statusCode()).isEqualTo(201);
        assertThat(post(fixture.base()+"/reservations/"+id+"/cancel",owner,null).statusCode()).isEqualTo(200);
        worker.runCycle(); provider.forceFlush().join(5,TimeUnit.SECONDS);
        assertThat(exporter.failures).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_offers WHERE venue_id=?",Integer.class,bytes(fixture.venue()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT d.state FROM event_deliveries d JOIN event_records e ON e.event_id=d.event_id WHERE e.aggregate_id=?",String.class,bytes(UUID.fromString(id)))).isEqualTo("DONE");
    }

    Fixture fixture() {
        var tenant = tenants.createTenant();
        var venue = venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(),"Observation fixture","UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY,new DailyOperatingHours(LocalTime.of(9,0),LocalTime.of(14,0)))),new BookingPolicyTerms(30,5,20,10)));
        var resource = resources.createResource(new ResourceUseCase.CreateResource(tenant.id(),venue.id(),"Table",4));
        var slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(),venue.id(),resource.id(),NOW.plusSeconds(7200).toString()));
        return new Fixture(venue.id().value(),slot.id().value());
    }
    String customer() {
        var principal = new AuthenticatedPrincipal(PrincipalId.newId()); access.registerPrincipal(principal.principalId());
        String token = UUID.randomUUID()+"-"+MARKER; credentials.values.put(token,principal); return token;
    }
    HttpResponse<String> post(String path,String token,String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(15))
            .header("Authorization","Bearer "+token).header("X-Request-ID",MARKER)
            .header("traceparent","00-"+"1".repeat(32)+"-"+"2".repeat(16)+"-01");
        if(body!=null) builder.header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString());
        return http.send(builder.POST(body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    record Fixture(UUID venue,UUID slot) {
        String base(){return "/api/v1/venues/"+venue;}
        String body(){return "{\"slotInventoryId\":\""+slot+"\",\"partySize\":2}";}
    }
    static String attr(SpanData span,String key){return span.getAttributes().get(AttributeKey.stringKey(key));}
    static byte[] bytes(UUID id){return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();}
    static class Credentials implements BearerCredentialResolver {
        final Map<String,AuthenticatedPrincipal> values = new ConcurrentHashMap<>();
        public Optional<AuthenticatedPrincipal> resolve(String value){return Optional.ofNullable(values.get(value));}
    }
    static class SwitchableExporter implements SpanExporter {
        final InMemorySpanExporter delegate = InMemorySpanExporter.create();
        final AtomicBoolean failed = new AtomicBoolean(); volatile int failures;
        public CompletableResultCode export(Collection<SpanData> spans){if(failed.get()){failures++;return CompletableResultCode.ofFailure();}return delegate.export(spans);}
        public CompletableResultCode flush(){return delegate.flush();}
        public CompletableResultCode shutdown(){return delegate.shutdown();}
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class Configuration {
        @Bean @Primary Clock testClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}
        @Bean Credentials credentials(){return new Credentials();}
        @Bean @Primary SwitchableExporter spanExporter(){return new SwitchableExporter();}
        @Bean ThreadPoolTaskScheduler taskScheduler(){return new ThreadPoolTaskScheduler(){
            @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Duration delay){return new PausedTimer();}
            @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Instant start,Duration delay){return new PausedTimer();}
        };}
    }
    static class PausedTimer extends CompletableFuture<Void> implements ScheduledFuture<Void>{
        public long getDelay(TimeUnit unit){return Long.MAX_VALUE;} public int compareTo(Delayed other){return 1;}
    }
}
