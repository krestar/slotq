package com.slotq.mcp;

import com.slotq.auth.access.ProductOperation;
import com.slotq.auth.domain.*;
import com.slotq.booking.application.*;
import com.slotq.booking.domain.*;
import com.slotq.events.application.*;
import com.slotq.integration.waitlist.BookingCapacityReleasedHandler;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.slotq.mcp.RepresentativeFlowIntegrationTests.bytes;
import static org.awaitility.Awaitility.await;
import static com.slotq.mcp.M8CapacityEvidence.*;

/** Existing public Product commands under mixed traffic, with narrow gates and independent physical MySQL reads. */
final class M8CapacityProbes {
    @FunctionalInterface interface Exchange { M8CapacityRunner.Observation send(HttpRequest request) throws Exception; }
    final List<Map<String, Object>> results = Collections.synchronizedList(new ArrayList<>());
    private final RepresentativeFlowIntegrationTests f;
    private final JdbcTemplate db;
    private final Exchange http;
    private VenueId venue;
    private RepresentativeFlowIntegrationTests.Identity actor;
    private static final Map<UUID, Gate> SLOT_GATES = new ConcurrentHashMap<>();
    private static final Map<UUID, Gate> APPEND_GATES = new ConcurrentHashMap<>();
    private static volatile UUID retryEvent;
    private static final Gate RETRY = new Gate(false);
    private static final AtomicInteger retryInvocations = new AtomicInteger();

    M8CapacityProbes(RepresentativeFlowIntegrationTests f, JdbcTemplate db, Exchange http, org.springframework.context.ApplicationContext application) {
        this.f = f; this.db = db; this.http = http; this.application = application;
    }

    void seed() {
        venue = f.createVenue(f.tenant);
        actor = f.identity("CUSTOMER", f.tenant, venue);
    }

    void run() {
        record("current-authority", this::authority);
        record("tenant-customer-scope", this::scope);
        record("event-atomicity", this::atomicity);
        record("response-loss-reconciliation", this::responseLoss);
    }

    private void record(String name, Callable<Map<String, Object>> action) {
        var trace = new M8CapacityObservation.Trace(UUID.randomUUID().toString());
        M8CapacityObservation.CURRENT.set(trace);
        var row = new LinkedHashMap<String, Object>();
        row.put("name", name); row.put("trace", trace.id); row.put("startedNanos", System.nanoTime());
        try { row.putAll(action.call()); row.put("observed", true); }
        catch (Exception failure) { row.put("observed", false); row.put("failureClass", failure.getClass().getSimpleName()); }
        finally {
            row.put("endedNanos", System.nanoTime()); results.add(row);
            M8CapacityObservation.CURRENT.remove();
        }
    }

    private UUID slot(UUID tenant, VenueId target, String time) {
        var resource = f.resources.createResource(new ResourceUseCase.CreateResource(new TenantId(tenant), target, "Capacity oracle fixture", 4));
        return f.slots.createSlot(new SlotInventoryUseCase.CreateSlot(new TenantId(tenant), target, resource.id(), f.day + "T" + time)).id().value();
    }

    private Map<String, Object> authority() throws Exception {
        var revocable = f.identity("CUSTOMER", f.tenant, venue);
        UUID target = slot(f.tenant, venue, "16:00:00Z");
        String key = UUID.randomUUID().toString();
        var credential = f.access.issueHold(revocable.delegation().delegationId(), target, 2, key, Instant.now().plusSeconds(30));
        var gate = new Gate(false);
        SLOT_GATES.put(target, gate);
        var request = hold(venue, target, key, credential.value(), false);
        var pending = f.http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            require(gate.entered.await(6, TimeUnit.SECONDS), "Authority lock gate not entered");
            f.access.revokeDelegation(revocable.delegation().delegationId());
            long revokedAt = System.nanoTime();
            gate.release.countDown();
            var response = pending.get(10, TimeUnit.SECONDS);
            var rows = targetRows(target);
            return Map.of("httpStatus", response.statusCode(), "revokedAtNanos", revokedAt, "gateEnteredNanos", gate.at,
                "database", rows, "target", target.toString());
        } finally { gate.release.countDown(); SLOT_GATES.remove(target); }
    }

    private Map<String, Object> scope() throws Exception {
        UUID tenant = f.tenants.createTenant().id().value();
        var foreignVenue = f.createVenue(tenant);
        var foreign = f.identity("CUSTOMER", tenant, foreignVenue);
        UUID target = slot(tenant, foreignVenue, "19:00:00Z");
        var hold = f.reservations.createHold(new ReservationUseCase.CreateHold(foreignVenue, new SlotInventoryId(target),
            new AuthenticatedPrincipal(new PrincipalId(foreign.principal())), 2)).reservation();
        var before = reservation(hold.id().value());
        var credential = f.access.issueProduct(actor.delegation().delegationId(), ProductOperation.RESERVATION_GET,
            hold.id().value(), Instant.now().plusSeconds(30));
        var response = f.http.send(HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN
            + "/api/v1/venues/" + venue.value() + "/reservations/" + hold.id().value()))
            .header("Authorization", "Bearer " + credential.value()).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
        var payload = JSON.readTree(response.body());
        // Problem.instance echoes the requested URI, which already contains the caller-supplied ID.
        boolean disclosed = payload.path("id").asString().equals(hold.id().value().toString()) || payload.has("customerPrincipalId") || payload.has("state");
        return Map.of("httpStatus", response.statusCode(), "disclosedForeignId", disclosed,
            "before", before, "after", reservation(hold.id().value()));
    }

    private Map<String, Object> atomicity() throws Exception {
        var committed = cancelProbe("17:00:00Z", false);
        var rolledBack = cancelProbe("18:00:00Z", true);
        return Map.of("commit", committed, "rollback", rolledBack);
    }

    private Map<String, Object> cancelProbe(String time, boolean rollback) throws Exception {
        UUID target = slot(f.tenant, venue, time);
        var principal = new AuthenticatedPrincipal(new PrincipalId(actor.principal()));
        var held = f.reservations.createHold(new ReservationUseCase.CreateHold(venue, new SlotInventoryId(target), principal, 2)).reservation();
        f.reservations.transition(venue, held.id(), principal, ReservationCommand.CONFIRM);
        if (!rollback) {
            var waiting = f.identity("CUSTOMER", f.tenant, venue);
            // One occupied time group prevents discovery from pre-empting this exact cancellation.
            var waitlist = (WaitlistUseCase) applicationBean(WaitlistUseCase.class);
            waitlist.register(new WaitlistUseCase.CreateRegistration(venue, new SlotInventoryId(target), 2,
                new WaitlistRegistrationKey(UUID.randomUUID()), new AuthenticatedPrincipal(new PrincipalId(waiting.principal()))));
        }
        var gate = new Gate(rollback);
        APPEND_GATES.put(held.id().value(), gate);
        var before = reservation(held.id().value());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> f.reservations.transition(venue, held.id(), principal, ReservationCommand.CANCEL));
            try {
                require(gate.entered.await(6, TimeUnit.SECONDS), "Append gate not entered");
                var uncommitted = reservation(held.id().value());
                if (!rollback) retryEvent = gate.event;
                gate.release.countDown();
                String terminal = "COMMITTED";
                try { pending.get(10, TimeUnit.SECONDS); }
                catch (ExecutionException failure) {
                    if (!rollback || !(failure.getCause() instanceof InjectedRollback)) throw failure;
                    terminal = "INJECTED_ROLLBACK";
                }
                var result = new LinkedHashMap<String, Object>();
                result.put("before", before); result.put("uncommitted", uncommitted);
                result.put("after", reservation(held.id().value())); result.put("terminal", terminal);
                if (!rollback) {
                    require(RETRY.entered.await(6, TimeUnit.SECONDS), "Effect rollback gate not entered");
                    result.put("receiptWhileUncommitted", receipt(gate.event));
                    RETRY.release.countDown();
                    await().atMost(Duration.ofSeconds(12)).pollInterval(Duration.ofMillis(50)).until(() -> receipt(gate.event).get("done").intValue() == 1);
                    result.put("receiptAfterRetry", receipt(gate.event));
                    result.put("handlerInvocations", retryInvocations.get());
                }
                return result;
            } finally { gate.release.countDown(); RETRY.release.countDown(); APPEND_GATES.remove(held.id().value()); }
        }
    }

    private org.springframework.context.ApplicationContext application;
    private Object applicationBean(Class<?> type) { return application.getBean(type); }

    private Map<String, Object> responseLoss() throws Exception {
        UUID target = slot(f.tenant, venue, "20:00:00Z");
        String key = UUID.randomUUID().toString();
        var credential = f.access.issueHold(actor.delegation().delegationId(), target, 2, key, Instant.now().plusSeconds(30));
        var trace = M8CapacityObservation.CURRENT.get();
        trace.dropResponse = true;
        var original = http.send(hold(venue, target, key, credential.value(), true));
        trace.dropResponse = false;
        require(trace.knownTarget != null, "Response loss target was not observed in Location");
        UUID known = UUID.fromString(trace.knownTarget);
        var read = f.access.issueProduct(actor.delegation().delegationId(), ProductOperation.RESERVATION_GET, known, Instant.now().plusSeconds(30));
        var reconciled = http.send(HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN + "/api/v1/venues/"
            + venue.value() + "/reservations/" + known)).header("Authorization", "Bearer " + read.value()).timeout(Duration.ofSeconds(8)).GET().build());
        return Map.of("originalOutcome", original.outcome(), "reconciledOutcome", reconciled.outcome(), "knownTarget", known.toString(),
            "database", reservation(known), "targetRows", targetRows(target));
    }

    private HttpRequest hold(VenueId targetVenue, UUID target, String key, String credential, boolean drop) {
        var request = HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN + "/api/v1/venues/" + targetVenue.value() + "/reservations/holds"))
            .header("Authorization", "Bearer " + credential).header("Content-Type", "application/json").header("Idempotency-Key", key)
            .timeout(Duration.ofSeconds(8)).POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("slotInventoryId", target, "partySize", 2))));
        if (drop) request.header("X-Capacity-Drop", "true");
        return request.build();
    }

    private Map<String, Object> reservation(UUID id) {
        return db.queryForMap("SELECT r.state,(SELECT COUNT(*) FROM capacity_allocations a WHERE a.reservation_id=r.id AND a.active=TRUE) active,"
            + "(SELECT COUNT(*) FROM event_records e WHERE e.aggregate_id=r.id AND e.event_type='booking.capacity-released') events FROM reservations r WHERE r.id=?", bytes(id));
    }

    private Map<String, Long> targetRows(UUID target) {
        var result = new TreeMap<String, Long>();
        for (String table : List.of("reservations", "capacity_allocations", "hold_idempotency_records"))
            result.put(table, db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE slot_inventory_id=?", Long.class, bytes(target)));
        return result;
    }

    private Map<String, Number> receipt(UUID event) {
        var row = db.queryForMap("SELECT (SELECT COUNT(*) FROM waitlist_promotion_receipts WHERE event_id=? AND outcome IS NOT NULL) receipt,"
            + "(SELECT COUNT(*) FROM event_deliveries d JOIN event_registrations g ON g.registration_id=d.registration_id WHERE d.event_id=? AND g.consumer_id='waitlist.promotion' AND d.state='DONE') done,"
            + "(SELECT COALESCE(MAX(cycle_attempts),0) FROM event_deliveries d JOIN event_registrations g ON g.registration_id=d.registration_id WHERE d.event_id=? AND g.consumer_id='waitlist.promotion') attempts", bytes(event), bytes(event), bytes(event));
        var result = new TreeMap<String, Number>();
        row.forEach((key, value) -> result.put(key, (Number) value));
        return result;
    }

    static Object slotProxy(SlotInventoryRepository target) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("findForUpdate")) {
                var id = ((SlotInventoryId) invocation.getArguments()[1]).value();
                Gate gate = SLOT_GATES.get(id);
                if (gate != null) gate.await();
            }
            return result;
        });
        return proxy.getProxy();
    }

    static Object appendProxy(EventAppendService target) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().startsWith("append") && invocation.getArguments()[0] instanceof EventEnvelope input) {
                Gate gate = APPEND_GATES.get(input.aggregateId());
                if (gate != null) { gate.event = input.eventId().value(); gate.await(); }
            }
            return result;
        });
        return proxy.getProxy();
    }

    static Object handlerProxy(BookingCapacityReleasedHandler target) {
        MethodInterceptor probe = invocation -> {
            Object result = invocation.proceed();
            if (invocation.getMethod().getName().equals("handle") && invocation.getArguments()[0] instanceof StoredEvent stored
                    && stored.envelope().eventId().value().equals(retryEvent)) {
                if (retryInvocations.incrementAndGet() == 1) {
                    RETRY.await();
                    throw new org.springframework.dao.TransientDataAccessResourceException("Controlled effect rollback");
                }
            }
            return result;
        };
        if (target instanceof org.springframework.aop.framework.Advised advised) {
            advised.addAdvice(probe);
            return target;
        }
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(probe);
        return proxy.getProxy();
    }

    static final class InjectedRollback extends RuntimeException { }
    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean first = new AtomicBoolean();
        final boolean rollback;
        volatile long at;
        volatile UUID event;
        Gate(boolean rollback) { this.rollback = rollback; }
        void await() throws InterruptedException {
            if (!first.compareAndSet(false, true)) return;
            at = System.nanoTime(); entered.countDown();
            require(release.await(8, TimeUnit.SECONDS), "Unreleased narrow probe gate");
            if (rollback) throw new InjectedRollback();
        }
    }
}
