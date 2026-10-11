package com.slotq.mcp;

import com.slotq.auth.access.*;
import com.slotq.auth.domain.*;
import com.slotq.auth.persistence.ActorAccessService;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.ai.runtime.AgentRuntime;
import com.slotq.ai.runtime.RuntimeContract;
import com.slotq.integration.mcp.product.HoldApprovals;
import com.slotq.events.application.*;
import com.slotq.integration.operations.PromotionRequestedObservedHandler;
import com.slotq.integration.operations.BookingCapacityObservedHandler;
import com.slotq.integration.operations.OperationsObservationReadService;
import com.slotq.integration.operations.recovery.OperatorCredentials;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.*;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.net.ssl.SSLContext;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Production registry and Auth, real MCP/Product HTTPS and MySQL; no handler or credential mocks. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "slotq.mcp.enabled=true", "server.ssl.enabled=true",
    "spring.datasource.hikari.connection-timeout=2000", "spring.datasource.hikari.maximum-pool-size=16",
    "spring.datasource.hikari.data-source-properties.socketTimeout=2000",
    "spring.datasource.hikari.data-source-properties.connectTimeout=2000",
    "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=2, lock_wait_timeout=2, wait_timeout=5, max_execution_time=2000",
    "spring.transaction.default-timeout=10s", "spring.jpa.properties.jakarta.persistence.query.timeout=2000",
    "slotq.mcp.quota.rate=200", "slotq.mcp.quota.burst=200", "slotq.mcp.handler-budget=PT5S",
    "slotq.events.delivery.scheduler-enabled=false", "slotq.operations.observation.enabled=false",
    "slotq.observability.scrape-token=synthetic-observation-scrape-0123456789"})
@ActiveProfiles("test")
@Import(EventObservationToolsIntegrationTests.Configuration.class)
class EventObservationToolsIntegrationTests {
    static final String TOOL = "management.event-observations.list";
    static final ProductOperation OPERATION = ProductOperation.EVENT_OBSERVATIONS_LIST;
    static final int PORT = McpHttpIntegrationTests.port();
    static final String ORIGIN = "https://localhost:" + PORT;
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
        .withCommand("--log-bin-trust-function-creators=1").withDatabaseName("slotq_observation_tools");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("server.port", () -> PORT);
        p.add("slotq.mcp.origin", () -> ORIGIN);
        p.add("slotq.mcp.product-origin", () -> ORIGIN);
        p.add("server.ssl.key-store", () -> McpHttpIntegrationTests.KEYSTORE.toUri().toString());
        p.add("server.ssl.key-store-password", () -> McpHttpIntegrationTests.PASSWORD);
        p.add("server.ssl.key-store-type", () -> "PKCS12");
    }
    @Autowired JdbcTemplate db;
    @Autowired ActorAccessService access;
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ToolRegistry registry;
    @Autowired McpEngine engine;
    @Autowired ProductHttpBinding binding;
    @Autowired OperationsObservationReadService observations;
    @Autowired OperatorCredentials operations;
    @Autowired EventRegistrationService registrations;
    @Autowired EventAppendService append;
    @Autowired EventDeliveryStore deliveries;
    @Autowired DeliveryTransactions deliveryTransactions;
    @Autowired DeliveryPolicy policy;
    @Autowired EventHandlers handlers;
    @Autowired EventCanonicalizer canonicalizer;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TestClock clock;
    @Autowired AdmissionFault fault;
    @Autowired HoldApprovals approvals;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired ReservationUseCase reservations;
    final JsonMapper json = JsonMapper.builder().build();
    HttpClient http;
    UUID tenant, observerRegistration, waitlistRegistration;
    VenueId venue;
    Instant from, to;
    LocalDate bookingDay;
    Identity owner;

    @BeforeEach void fixture() throws Exception {
        clock.now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        fault.action = null;
        http = HttpClient.newBuilder().sslContext(McpHttpIntegrationTests.tls())
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();
        tenant = tenants.createTenant().id().value();
        venue = venue(tenant);
        from = clock.instant().minusSeconds(60);
        to = clock.instant().plusSeconds(60);
        owner = identity("OWNER", true);
        bookingDay = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
            .with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY));
        var resource = resources.createResource(new ResourceUseCase.CreateResource(new TenantId(tenant), venue, "Synthetic table", 4));
        var slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(new TenantId(tenant), venue, resource.id(), bookingDay + "T12:00:00Z"));
        reservations.createHold(new ReservationUseCase.CreateHold(venue, slot.id(), new AuthenticatedPrincipal(new PrincipalId(owner.principal())), 2));
        observerRegistration = registrations.activate(PromotionRequestedObservedHandler.ROUTE);
        waitlistRegistration = registrations.activate(new ConsumerRoute("waitlist.promotion", "waitlist.promotion-requested", 1));
    }
    @AfterEach void cleanup() {
        fault.action = null;
        clock.now = Instant.now();
        registrations.deactivate(observerRegistration);
        registrations.deactivate(waitlistRegistration);
    }

    @Test void ownerAndAssignedManagerListAndCallReturnExactProductPageWithoutStateChanges() throws Exception {
        seed(2);
        var before = snapshot();
        assertThat(registry.all()).extracting(t -> t.wire().name()).contains(TOOL);
        assertThat(registry.require(TOOL).wire().description()).contains("projected_at", "occurredAt", "null", "truncated");
        for (Identity actor : List.of(owner, identity("MANAGER", true))) {
            String session = initialize(actor.delegation().value());
            assertThat(list(actor.delegation().value(), session).toString()).contains(TOOL);
            JsonNode result = call(actor.delegation().value(), session, TOOL, input(null));
            success(result);
            var product = access.issueProduct(actor.delegation().delegationId(), OPERATION, null, clock.instant().plusSeconds(30));
            var response = product(product.value(), "GET", path(venue) + query(null));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(result.path("structuredContent").path("data")).isEqualTo(json.readTree(response.body()));
            assertThat(result.path("structuredContent").path("data").path("items")).hasSize(2);
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void commonRuntimeCanInvokeTheRegisteredObservationReadWithoutProviderExecution() {
        seed(1);
        var before = snapshot();
        try (var runtime = new AgentRuntime(access, registry, engine, approvals,
                (model, classification, request, budget) -> { throw new AssertionError("Provider execution is outside this tool regression"); },
                List.of(), (actor, stage, facts) -> { throw new AssertionError("No provider context required for a read"); },
                new LocalAdmission(new LocalAdmission.Limit(100, 100, 4), 64, System::nanoTime), clock, 8, 4)) {
            var plan = new RuntimeContract.Plan("observation-read-v1", "ops", RuntimeContract.Limits.bounded(), null,
                Map.of(TOOL, input(null)));
            UUID run = runtime.start(owner.delegation().value(), plan);
            var result = runtime.read(run, owner.delegation().value(), TOOL, input(null));
            assertThat(result.tools()).hasSize(1);
            assertThat(result.tools().getFirst().invocation()).isEqualTo(RuntimeContract.Invocation.RESPONSE_OBSERVED);
            assertThat(result.tools().getFirst().structured()).containsEntry("outcome", "succeeded");
            assertThat(result.productEffect()).isEqualTo(RuntimeContract.Effect.NOT_APPLICABLE);
            assertThat(result.providers()).isEmpty();
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void deniedIssuanceAndToolVisibilityKeepStaffReservationRead() throws Exception {
        for (String role : List.of("STAFF", "CUSTOMER", "UNASSIGNED_MANAGER")) {
            Identity actor = identity(role, false);
            assertThatThrownBy(() -> delegate(actor.original(), venue, Set.of(AccessAction.EVENT_OBSERVATION_READ), Set.of(TOOL)))
                .isInstanceOf(AccessFailure.class);
            assertThatThrownBy(() -> delegate(actor.original(), venue, Set.of(AccessAction.MANAGEMENT_READ), Set.of(TOOL)))
                .isInstanceOf(AccessFailure.class);
            assertThatThrownBy(() -> access.approveDelegation(actor.original().value(), venue, AccessProfile.CUSTOMER,
                Set.of(AccessAction.EVENT_OBSERVATION_READ), Set.of(TOOL), Duration.ofMinutes(10)))
                .isInstanceOf(AccessFailure.class);
            if (role.equals("UNASSIGNED_MANAGER")) continue;
            AccessProfile profile = role.equals("CUSTOMER") ? AccessProfile.CUSTOMER : AccessProfile.MANAGEMENT;
            var ordinary = access.approveDelegation(actor.original().value(), venue, profile,
                Set.of(profile == AccessProfile.CUSTOMER ? AccessAction.RESERVATION_READ : AccessAction.MANAGEMENT_READ),
                Set.of(profile == AccessProfile.CUSTOMER ? "reservation.get" : "management.reservations.list"), Duration.ofMinutes(10));
            String session = initialize(ordinary.value());
            assertThat(list(ordinary.value(), session).toString()).doesNotContain(TOOL);
            assertThat(call(ordinary.value(), session, TOOL, input(null)).toString()).contains("forbidden", "not_dispatched");
            assertThatThrownBy(() -> access.issueProduct(ordinary.delegationId(), OPERATION, null, to)).isInstanceOf(AccessFailure.class);
            if (role.equals("STAFF")) {
                var read = call(ordinary.value(), session, "management.reservations.list", Map.of("date", bookingDay.toString()));
                success(read);
                assertThat(read.path("structuredContent").path("data")).hasSize(1);
                // Even a persisted action/tool claim cannot replace current Owner/Manager eligibility.
                db.update("UPDATE auth_access_delegations SET actions='MANAGEMENT_READ,EVENT_OBSERVATION_READ',tools=? WHERE id=?",
                    "management.reservations.list," + TOOL, bytes(ordinary.delegationId()));
                assertThatThrownBy(() -> access.revalidate(ordinary.delegationId())).isInstanceOf(AccessFailure.class);
            }
        }
        Identity manager = identity("MANAGER", false);
        assertThatThrownBy(() -> delegate(manager.original(), venue(tenant), Set.of(AccessAction.EVENT_OBSERVATION_READ), Set.of(TOOL)))
            .isInstanceOf(AccessFailure.class);
        VenueId foreign = venue(tenants.createTenant().id().value());
        assertThatThrownBy(() -> delegate(owner.original(), foreign, Set.of(AccessAction.EVENT_OBSERVATION_READ), Set.of(TOOL)))
            .isInstanceOf(AccessFailure.class);
    }

    @Test void currentValidationListCallAndIssuanceRejectDowngradeAssignmentLossAndDeadDelegations() throws Exception {
        for (String change : List.of("downgrade", "assignment", "delegation-revoke", "delegation-expiry", "original-revoke")) {
            clock.now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Identity actor = identity("MANAGER", true);
            String session = initialize(actor.delegation().value());
            var live = access.authenticateMcp(actor.delegation().value());
            var product = access.issueProduct(live.delegationId(), OPERATION, null, clock.instant().plusSeconds(30));
            if (change.equals("downgrade")) downgrade(actor);
            if (change.equals("assignment")) db.update("DELETE FROM venue_grants WHERE principal_id=?", bytes(actor.principal()));
            if (change.equals("delegation-revoke")) access.revokeDelegation(live.delegationId());
            if (change.equals("delegation-expiry")) clock.now = live.expiresAt();
            if (change.equals("original-revoke")) access.revokeCredential(actor.original().id());
            assertThatThrownBy(() -> access.revalidate(live.delegationId())).as(change).isInstanceOf(AccessFailure.class);
            assertThatThrownBy(() -> access.revalidateOwnerManager(live.delegationId())).as(change).isInstanceOf(AccessFailure.class);
            assertThatThrownBy(() -> access.issueProduct(live.delegationId(), OPERATION, null, clock.instant().plusSeconds(30)))
                .as(change).isInstanceOf(AccessFailure.class);
            assertThatThrownBy(() -> engine.list(engine.context(live, UUID.randomUUID()))).as(change).isInstanceOf(McpFailure.class);
            assertThat(engine.call(engine.context(live, UUID.randomUUID()), TOOL, input(null)).structuredContent().toString())
                .as(change).contains("forbidden", "not_dispatched");
            assertThat(rpc(actor.delegation().value(), session, "tools/list", Map.of(), 2).statusCode()).as(change).isIn(401, 403);
            assertThat(rpc(actor.delegation().value(), session, "tools/call", Map.of("name", TOOL, "arguments", input(null)), 3).statusCode())
                .as(change).isIn(401, 403);
            assertThat(product(product.value(), "GET", path(venue) + query(null)).statusCode()).as(change).isEqualTo(401);
        }
    }

    @Test void exactProductCredentialCannotBeSubstitutedAcrossOperationsMethodsRoutesTargetsOrScopes() throws Exception {
        var d = owner.delegation();
        var credential = access.issueProduct(d.delegationId(), OPERATION, null, clock.instant().plusSeconds(60));
        assertThat(credential.expiresAt()).isEqualTo(clock.instant().plusSeconds(60));
        var context = engine.context(access.authenticateMcp(d.value()), UUID.randomUUID());
        var prepared = binding.prepare(context, OPERATION, null);
        assertThat(prepared.uri()).isEqualTo(URI.create(ORIGIN + path(venue)));
        assertThat(prepared.method()).isEqualTo("GET");
        assertThat(prepared.maxResponseBytes()).isEqualTo(131072);
        assertThat(prepared.credential().expiresAt()).isEqualTo(context.deadline());
        assertThatThrownBy(() -> access.issueProduct(d.delegationId(), OPERATION, UUID.randomUUID(), to)).isInstanceOf(AccessFailure.class);
        for (String route : List.of(path(venue) + "/", path(venue) + "/extra",
                path(venue(tenant)), path(venue(tenants.createTenant().id().value())),
                "/api/v1/management/venues/" + venue.value() + "/reservations?date=2026-10-11",
                "/api/v1/venues/" + venue.value() + "/reservations/" + UUID.randomUUID(),
                "/api/v1/venues/" + venue.value() + "/reservations/holds")) {
            assertThat(product(credential.value(), "GET", route).statusCode()).as(route).isEqualTo(401);
        }
        for (String method : List.of("POST", "PUT", "DELETE", "HEAD")) {
            assertThat(product(credential.value(), method, path(venue) + query(null)).statusCode()).as(method).isEqualTo(401);
        }
        var reservationCredential = access.issueProduct(d.delegationId(), ProductOperation.MANAGEMENT_LIST, null, to);
        assertThat(product(reservationCredential.value(), "GET", path(venue) + query(null)).statusCode()).isEqualTo(401);
        var customer = access.approveDelegation(owner.original().value(), venue, AccessProfile.CUSTOMER,
            Set.of(AccessAction.RESERVATION_READ, AccessAction.RESERVATION_WRITE), Set.of("reservation.get", "reservation.hold"), Duration.ofMinutes(10));
        var getCredential = access.issueProduct(customer.delegationId(), ProductOperation.RESERVATION_GET, UUID.randomUUID(), to);
        var holdCredential = access.issueHold(customer.delegationId(), UUID.randomUUID(), 1, UUID.randomUUID().toString(), to);
        for (var wrong : List.of(getCredential, holdCredential, reservationCredential)) {
            assertThat(product(wrong.value(), "GET", path(venue) + query(null)).statusCode()).isEqualTo(401);
        }
        // Call the real application with a principal authenticated at its correct unrelated route.
        var principal = access.authenticateProduct(reservationCredential.value(), "GET",
            "/api/v1/management/venues/" + venue.value() + "/reservations").orElseThrow();
        assertThatThrownBy(() -> observations.list(venue, from, to, 10, principal)).isInstanceOf(AccessFailure.class);
        access.revokeCredential(credential.id());
        assertThat(product(credential.value(), "GET", path(venue) + query(null)).statusCode()).isEqualTo(401);
        var expiring = access.issueProduct(d.delegationId(), OPERATION, null, clock.instant().plusSeconds(1));
        clock.now = expiring.expiresAt();
        assertThat(product(expiring.value(), "GET", path(venue) + query(null)).statusCode()).isEqualTo(401);
    }

    @Test void finalApplicationAdmissionRechecksAfterSuccessfulHttpAuthentication() throws Exception {
        var before = snapshot();
        for (String change : List.of("operation", "target", "credential-revoke", "delegation-revoke", "original-revoke",
                "downgrade", "assignment", "credential-expiry", "delegation-expiry")) {
            clock.now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Identity actor = identity("MANAGER", true);
            var credential = access.issueProduct(actor.delegation().delegationId(), OPERATION, null, clock.instant().plusSeconds(30));
            fault.action = () -> {
                switch (change) {
                    case "operation" -> db.update("UPDATE auth_access_credentials SET operation='MANAGEMENT_LIST' WHERE id=?", bytes(credential.id()));
                    case "target" -> db.update("UPDATE auth_access_credentials SET target_id=? WHERE id=?", bytes(UUID.randomUUID()), bytes(credential.id()));
                    case "credential-revoke" -> access.revokeCredential(credential.id());
                    case "delegation-revoke" -> access.revokeDelegation(actor.delegation().delegationId());
                    case "original-revoke" -> access.revokeCredential(actor.original().id());
                    case "downgrade" -> downgrade(actor);
                    case "assignment" -> db.update("DELETE FROM venue_grants WHERE principal_id=?", bytes(actor.principal()));
                    case "credential-expiry" -> clock.now = credential.expiresAt();
                    case "delegation-expiry" -> clock.now = access.revalidate(actor.delegation().delegationId()).expiresAt();
                    default -> throw new AssertionError(change);
                }
            };
            int previous = fault.authenticated;
            assertThat(product(credential.value(), "GET", path(venue) + query(null)).statusCode()).as(change).isEqualTo(401);
            assertThat(fault.authenticated).as(change).isEqualTo(previous + 1);
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void originalMcpRecoveryAndMonitoringCredentialsCannotReplaceProductOrDelegation() throws Exception {
        String recovery = "sqop_" + HexFormat.of().formatHex(java.security.SecureRandom.getSeed(32));
        UUID operator = UUID.randomUUID();
        db.update("INSERT INTO operations_operators(operator_id,principal_reference,active) VALUES(?, ?,TRUE)", bytes(operator), "synthetic-observation-operator");
        db.update("INSERT INTO operations_credentials(credential_id,operator_id,token_hash,expires_at) VALUES(?,?,?,UTC_TIMESTAMP(6)+INTERVAL 1 HOUR)",
            bytes(UUID.randomUUID()), bytes(operator), java.security.MessageDigest.getInstance("SHA-256").digest(recovery.getBytes(StandardCharsets.UTF_8)));
        assertThat(operations.authenticate(recovery)).isPresent();
        String scrape = "synthetic-observation-scrape-0123456789";
        assertThat(product(scrape, "GET", "/actuator/prometheus").statusCode()).isEqualTo(200);
        var credential = access.issueProduct(owner.delegation().delegationId(), OPERATION, null, to);
        for (String token : List.of(recovery, scrape, owner.original().value(), credential.value())) {
            assertThat(rpc(token, null, "initialize", Map.of(), 1).statusCode()).isEqualTo(401);
            assertThat(rpc(token, null, "tools/call", Map.of("name", TOOL, "arguments", input(null)), 2).statusCode()).isEqualTo(401);
            if (!token.equals(owner.original().value())) {
                assertThatThrownBy(() -> delegateValue(token)).isInstanceOf(AccessFailure.class);
            }
        }
        for (String token : List.of(recovery, scrape, owner.original().value(), owner.delegation().value())) {
            assertThat(product(token, "GET", path(venue) + query(null)).statusCode()).isEqualTo(401);
        }
    }

    @Test void windowLimitsTruncationAndStrictInputPreserveProductBounds() throws Exception {
        seed(101);
        String session = initialize(owner.delegation().value());
        var before = snapshot();
        for (Integer limit : Arrays.asList(null, 1, 100)) {
            var result = call(owner.delegation().value(), session, TOOL, input(limit));
            success(result);
            var data = result.path("structuredContent").path("data");
            assertThat(data.path("items")).hasSize(limit == null ? 50 : limit);
            assertThat(data.path("truncated").asBoolean()).isTrue();
        }
        var exact = input(100);
        exact.put("from", to.minus(Duration.ofDays(31)).toString());
        success(call(owner.delegation().value(), session, TOOL, exact));
        var integralLimit = input(null);
        integralLimit.put("limit", 1.0);
        success(call(owner.delegation().value(), session, TOOL, integralLimit));
        for (Map<String, Object> changes : List.of(Map.<String, Object>of("from", to.minus(Duration.ofDays(31)).minusNanos(1).toString()),
                Map.<String, Object>of("from", to.toString()), Map.<String, Object>of("from", to.plusSeconds(1).toString()),
                Map.<String, Object>of("from", "invalid"), Map.<String, Object>of("to", "2026-02-31T00:00:00Z"),
                Map.<String, Object>of("from", 1), Map.<String, Object>of("limit", 0), Map.<String, Object>of("limit", 101),
                Map.<String, Object>of("limit", 1.5), Map.<String, Object>of("limit", "1"))) {
            var wrong = input(null);
            wrong.putAll(changes);
            assertThat(call(owner.delegation().value(), session, TOOL, wrong).toString()).contains("validation", "not_dispatched");
        }
        for (String field : List.of("tenant", "tenantId", "venue", "venueId", "principal", "role", "credential", "baseUrl", "productOrigin")) {
            var wrong = input(null);
            wrong.put(field, "untrusted");
            assertThat(call(owner.delegation().value(), session, TOOL, wrong).toString()).contains("validation", "not_dispatched");
        }
        var missing = input(null);
        missing.remove("to");
        assertThat(call(owner.delegation().value(), session, TOOL, missing).toString()).contains("validation");
        var nullLimit = input(null);
        nullLimit.put("limit", null);
        assertThat(call(owner.delegation().value(), session, TOOL, nullLimit).toString()).contains("validation");
        var product = access.issueProduct(owner.delegation().delegationId(), OPERATION, null, to);
        assertThat(product(product.value(), "GET", path(venue) + query(101)).statusCode()).isEqualTo(400);
        assertThat(product(product.value(), "GET", path(venue) + "?from=" + from.minus(Duration.ofDays(32)) + "&to=" + to).statusCode()).isEqualTo(400);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void projectedWindowPreservesPendingDoneDeadMissingReceiptAndEmptyPage() throws Exception {
        List<UUID> events = seed(5);
        db.update("DELETE FROM event_deliveries WHERE event_id=? AND registration_id=?", bytes(events.get(0)), bytes(waitlistRegistration));
        for (int index : List.of(2, 3)) {
            db.update("UPDATE event_deliveries SET state=?,next_attempt_at=NULL WHERE event_id=? AND registration_id=?",
                index == 2 ? "DONE" : "DEAD", bytes(events.get(index)), bytes(waitlistRegistration));
        }
        UUID slot = db.queryForObject("SELECT slot_inventory_id FROM event_observation_projections WHERE event_id=?", (rs, i) -> uuid(rs.getBytes(1)), bytes(events.get(2)));
        db.update("INSERT INTO waitlist_promotion_receipts (tenant_id,consumer_id,event_id,signal_type,source_id,occurred_at,venue_id,resource_id,slot_inventory_id,outcome) "
            + "VALUES(?,'waitlist.promotion',?,'PROMOTION_REQUESTED',?,?,?,?,?,'NO_CANDIDATE')", bytes(tenant), bytes(events.get(2)),
            bytes(slot), Timestamp.from(from.minus(Duration.ofDays(60))), bytes(venue.value()), bytes(UUID.randomUUID()), bytes(slot));
        // Real event delivery made each projection; controlled timestamps pin the [from,to) edge fixture.
        for (int index = 0; index < events.size(); index++) {
            db.update("UPDATE event_observation_projections SET projected_at=? WHERE event_id=?",
                Timestamp.from(index == 4 ? to : from), bytes(events.get(index)));
        }
        var before = snapshot();
        String session = initialize(owner.delegation().value());
        var result = call(owner.delegation().value(), session, TOOL, input(100));
        success(result);
        JsonNode page = result.path("structuredContent").path("data");
        assertThat(page.path("items")).hasSize(4);
        assertThat(page.path("truncated").asBoolean()).isFalse();
        Map<String, JsonNode> byId = new HashMap<>();
        page.path("items").forEach(item -> {
            byId.put(item.path("eventId").asString(), item);
            assertThat(Instant.parse(item.path("occurredAt").asString())).isBefore(from);
            assertThat(Instant.parse(item.path("projectedAt").asString())).isEqualTo(from);
            assertThat(item.path("fromState").isNull()).isTrue();
            assertThat(item.path("toState").isNull()).isTrue();
            assertThat(item.path("durableIntakeObservedAt").isNull()).isTrue();
            assertThat(item.path("waitlistOfferId").isNull()).isTrue();
            assertThat(item.has("healthy")).isFalse();
        });
        assertThat(byId.get(events.get(0).toString()).path("waitlistDeliveryState").isNull()).isTrue();
        assertThat(byId.get(events.get(1).toString()).path("waitlistDeliveryState").asString()).isEqualTo("PENDING");
        assertThat(byId.get(events.get(1).toString()).path("waitlistReceiptOutcome").isNull()).isTrue();
        assertThat(byId.get(events.get(2).toString()).path("waitlistDeliveryState").asString()).isEqualTo("DONE");
        assertThat(byId.get(events.get(2).toString()).path("waitlistReceiptOutcome").asString()).isEqualTo("NO_CANDIDATE");
        assertThat(byId.get(events.get(3).toString()).path("waitlistDeliveryState").asString()).isEqualTo("DEAD");
        assertThat(byId.get(events.get(3).toString()).path("waitlistReceiptOutcome").isNull()).isTrue();
        var empty = input(null);
        empty.put("from", from.minus(Duration.ofDays(1)).toString());
        empty.put("to", from.toString());
        var emptyResult = call(owner.delegation().value(), session, TOOL, empty);
        success(emptyResult);
        assertThat(emptyResult.path("structuredContent").path("data").path("items")).isEmpty();
        assertThat(emptyResult.path("structuredContent").path("data").path("truncated").asBoolean()).isFalse();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test void hundredPopulatedObservationsFitTheExplicitBoundWithoutChangingExistingToolBounds() throws Exception {
        UUID releaseObserver = registrations.activate(BookingCapacityObservedHandler.ROUTE);
        UUID releaseWaitlist = registrations.activate(new ConsumerRoute("waitlist.promotion", "booking.capacity-released", 1));
        try {
            seed(101, true, releaseObserver);
            // Fixture-only Kafka intake observation and completed no-candidate receipts; no Kafka transport claim.
            db.update("UPDATE event_observation_projections SET intaken_at=projected_at WHERE tenant_id=?", bytes(tenant));
            db.update("UPDATE event_deliveries SET state='DONE',next_attempt_at=NULL WHERE tenant_id=? AND registration_id=?",
                bytes(tenant), bytes(releaseWaitlist));
            db.update("INSERT INTO waitlist_promotion_receipts (tenant_id,consumer_id,event_id,signal_type,source_id,occurred_at,venue_id,resource_id,slot_inventory_id,from_state,to_state,outcome) "
                + "SELECT tenant_id,'waitlist.promotion',event_id,'CAPACITY_RELEASED',aggregate_id,occurred_at,venue_id,resource_id,slot_inventory_id,from_state,to_state,'NO_CANDIDATE' "
                + "FROM event_observation_projections WHERE tenant_id=?", bytes(tenant));
            var before = snapshot();
            var product = access.issueProduct(owner.delegation().delegationId(), OPERATION, null, to);
            var response = product(product.value(), "GET", path(venue) + query(100));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body().getBytes(StandardCharsets.UTF_8).length).isGreaterThan(65536).isLessThan(131072);
            var result = call(owner.delegation().value(), initialize(owner.delegation().value()), TOOL, input(100));
            success(result);
            assertThat(result.path("structuredContent").path("data")).isEqualTo(json.readTree(response.body()));
            assertThat(result.path("structuredContent").path("data").path("items")).hasSize(100);
            assertThat(result.path("structuredContent").path("data").path("truncated").asBoolean()).isTrue();
            var transport = HttpClientStreamableHttpTransport.builder(ORIGIN).endpoint("/mcp")
                .clientBuilder(HttpClient.newBuilder().sslContext(McpHttpIntegrationTests.tls()).followRedirects(HttpClient.Redirect.NEVER))
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + owner.delegation().value()))
                .supportedProtocolVersions(List.of("2025-11-25")).resumableStreams(false).openConnectionOnStartup(false)
                .maxResponseSize(262144).jsonMapper(new JacksonMcpJsonMapper(json)).build();
            try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(8)).build()) {
                client.initialize();
                var sdkResult = client.callTool(new McpSchema.CallToolRequest(TOOL, input(100)));
                assertThat(sdkResult.isError()).isFalse();
                assertThat(json.valueToTree(sdkResult.structuredContent()).path("data")).isEqualTo(json.readTree(response.body()));
            }
            assertThat(registry.require(TOOL).maxResultBytes()).isEqualTo(131072);
            for (var tool : registry.all()) {
                if (!tool.wire().name().equals(TOOL)) assertThat(tool.maxResultBytes()).isEqualTo(65536);
            }
            assertThat(snapshot()).isEqualTo(before);
        } finally {
            registrations.deactivate(releaseObserver);
            registrations.deactivate(releaseWaitlist);
        }
    }

    List<UUID> seed(int count) {
        return seed(count, false, observerRegistration);
    }
    List<UUID> seed(int count, boolean release, UUID registration) {
        List<UUID> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID slot = UUID.randomUUID();
            Map<String, Object> payload = new HashMap<>(Map.of("venueId", venue.value(), "resourceId", UUID.randomUUID(), "slotInventoryId", slot));
            if (release) {
                payload.put("fromState", "CONFIRMED");
                payload.put("toState", "CANCELLED");
            }
            EventEnvelope event = new EventEnvelope(EventId.newId(), new TenantId(tenant), release ? "Reservation" : "SlotInventory",
                release ? UUID.randomUUID() : slot, release ? "booking.capacity-released" : "waitlist.promotion-requested", 1,
                from.minus(Duration.ofDays(60)), json.writeValueAsString(payload));
            events.add(new TransactionTemplate(transactions).execute(status -> append.append(event)).envelope().eventId().value());
        }
        var worker = new EventDeliveryWorker(deliveries, deliveryTransactions, policy, handlers, canonicalizer, entityManagerFactory,
            new DeliveryExecutionScope("operations.event-observation", "DB_DIRECT", 1));
        while (worker.materialize() > 0) { }
        for (UUID event : events) {
            worker.process(worker.claim(new DeliveryKey(new TenantId(tenant), new EventId(event), registration)).orElseThrow());
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM event_observation_projections WHERE tenant_id=?", Integer.class, bytes(tenant))).isEqualTo(count);
        return events;
    }
    Identity identity(String role, boolean observation) {
        UUID principal = UUID.randomUUID();
        db.update("INSERT INTO auth_principals(id) VALUES(?)", bytes(principal));
        var original = access.provisionOriginal(new PrincipalId(principal), clock.instant().plus(Duration.ofDays(2)));
        if (!role.equals("CUSTOMER")) {
            String actualRole = role.equals("UNASSIGNED_MANAGER") ? "MANAGER" : role;
            db.update("INSERT INTO tenant_memberships(tenant_id,principal_id,role) VALUES(?,?,?)", bytes(tenant), bytes(principal), actualRole);
            if (!role.equals("OWNER") && !role.equals("UNASSIGNED_MANAGER")) {
                db.update("INSERT INTO venue_grants(tenant_id,venue_id,principal_id,role) VALUES(?,?,?,?)", bytes(tenant), bytes(venue.value()), bytes(principal), actualRole);
            }
        }
        var delegation = observation ? delegate(original, venue, Set.of(AccessAction.EVENT_OBSERVATION_READ, AccessAction.MANAGEMENT_READ),
            Set.of(TOOL, "management.reservations.list")) : null;
        return new Identity(principal, original, delegation);
    }
    record Identity(UUID principal, ActorAccessService.ProvisionedCredential original, ActorAccessService.ProvisionedCredential delegation) { }
    ActorAccessService.ProvisionedCredential delegate(ActorAccessService.ProvisionedCredential original, VenueId venue,
            Set<AccessAction> actions, Set<String> tools) {
        return access.approveDelegation(original.value(), venue, AccessProfile.MANAGEMENT, actions, tools, Duration.ofMinutes(10));
    }
    void delegateValue(String token) { access.approveDelegation(token, venue, AccessProfile.MANAGEMENT,
        Set.of(AccessAction.EVENT_OBSERVATION_READ), Set.of(TOOL), Duration.ofMinutes(10)); }
    void downgrade(Identity actor) {
        db.update("DELETE FROM venue_grants WHERE principal_id=?", bytes(actor.principal()));
        db.update("UPDATE tenant_memberships SET role='STAFF' WHERE principal_id=?", bytes(actor.principal()));
        db.update("INSERT INTO venue_grants(tenant_id,venue_id,principal_id,role) VALUES(?,?,?,'STAFF')", bytes(tenant), bytes(venue.value()), bytes(actor.principal()));
    }
    VenueId venue(UUID tenant) {
        return venues.createVenue(new VenueConfigurationUseCase.CreateVenue(new TenantId(tenant), "Synthetic observation", "UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.MONDAY, new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(22, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10))).id();
    }
    Map<String, String> snapshot() {
        Map<String, String> snapshot = new TreeMap<>();
        for (String table : List.of("event_records", "event_deliveries", "event_observation_projections", "waitlist_promotion_receipts",
                "event_replay_audit", "event_boundary", "event_discovery", "reservations", "capacity_allocations", "hold_idempotency_records")) {
            snapshot.put(table, json.writeValueAsString(db.queryForList("SELECT * FROM " + table)));
        }
        return snapshot;
    }
    Map<String, Object> input(Integer limit) {
        Map<String, Object> input = new HashMap<>(Map.of("from", from.toString(), "to", to.toString()));
        if (limit != null) input.put("limit", limit);
        return input;
    }
    static String path(VenueId venue) { return "/api/v1/management/venues/" + venue.value() + "/event-observations"; }
    String query(Integer limit) { return "?from=" + URLEncoder.encode(from.toString(), StandardCharsets.UTF_8)
        + "&to=" + URLEncoder.encode(to.toString(), StandardCharsets.UTF_8) + (limit == null ? "" : "&limit=" + limit); }
    HttpResponse<String> product(String token, String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(ORIGIN + path)).timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bearer " + token).method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
    String initialize(String token) throws Exception {
        var init = rpc(token, null, "initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
            "clientInfo", Map.of("name", "observation-tests", "version", "1")), 1);
        assertThat(init.statusCode()).isEqualTo(200);
        String session = init.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertThat(rpc(token, session, "notifications/initialized", Map.of(), null).statusCode()).isEqualTo(202);
        return session;
    }
    JsonNode list(String token, String session) throws Exception { return json.readTree(rpc(token, session, "tools/list", Map.of(), 2).body()).path("result"); }
    @SuppressWarnings("unchecked")
    JsonNode call(String token, String session, String tool, Object arguments) throws Exception {
        var response = rpc(token, session, "tools/call", Map.of("name", tool, "arguments", arguments), 3);
        assertThat(response.statusCode()).isEqualTo(200);
        var result = json.readTree(response.body()).path("result");
        registry.validateOutput(registry.require(tool), json.convertValue(result.path("structuredContent"), Map.class));
        return result;
    }
    HttpResponse<String> rpc(String token, String session, String method, Object params, Integer id) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("jsonrpc", "2.0", "method", method, "params", params));
        if (id != null) body.put("id", id);
        var request = HttpRequest.newBuilder(URI.create(ORIGIN + "/mcp")).timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25");
        if (session != null) request.header("Mcp-Session-Id", session);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    void success(JsonNode result) { assertThat(result.path("structuredContent").path("outcome").asString()).as(result.toString()).isEqualTo("succeeded"); }
    static byte[] bytes(UUID id) { return McpHttpIntegrationTests.bytes(id); }
    static UUID uuid(byte[] value) { var buffer = java.nio.ByteBuffer.wrap(value); return new UUID(buffer.getLong(), buffer.getLong()); }
    static class TestClock extends Clock {
        volatile Instant now = Instant.now();
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    static class AdmissionFault implements HandlerInterceptor {
        volatile Runnable action;
        int authenticated;
        @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (request.getRequestURI().endsWith("/event-observations") && action != null) {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                assertThat(authentication.getPrincipal()).isInstanceOf(AuthenticatedPrincipal.class);
                assertThat(((AuthenticatedPrincipal) authentication.getPrincipal()).restriction()).isNotNull();
                authenticated++;
                Runnable fault = action;
                action = null;
                fault.run();
            }
            return true;
        }
    }
    @TestConfiguration static class Configuration {
        @Bean @Primary TestClock observationClock() { return new TestClock(); }
        @Bean SSLContext observationTrust() throws Exception { return McpHttpIntegrationTests.tls(); }
        @Bean AdmissionFault observationAdmissionFault() { return new AdmissionFault(); }
        @Bean WebMvcConfigurer observationInterceptor(AdmissionFault fault) { return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(fault); }
        }; }
    }
}
