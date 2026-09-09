package com.slotq.experiments.events;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.testcontainers.mysql.MySQLContainer;

final class EventProbeWorkload {
    static List<Map<String, Object>> run(EventFixture f, MySQLContainer mysql) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var boundary : EventFixture.Boundary.values()) {
            f.db.update("DELETE FROM fixture_projection");
            f.db.update("DELETE FROM fixture_effect");
            f.db.update("DELETE FROM fixture_event");
            f.db.update("DELETE FROM fixture_owner");
            long seed = 81000;
            var e = f.event(seed, 1);
            f.produce(boundary, e, "NONE");
            for (int n = 0; n < 3; n++) f.tx.executeWithoutResult(s -> f.effect(e, "NONE"));
            record(rows, f, e, boundary + "_SEQUENTIAL_DUPLICATE", "SUCCESS");
            var concurrent = f.event(seed + 2, 1);
            f.tx.executeWithoutResult(s -> f.business(concurrent));
            var barrier = new CyclicBarrier(4);
            try (var executor = Executors.newFixedThreadPool(4)) {
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (int n = 0; n < 4; n++) futures.add(executor.submit(() -> {
                    try { barrier.await(10, TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                    f.tx.executeWithoutResult(s -> f.effect(concurrent, "NONE"));
                }));
                for (var future : futures) future.get(15, TimeUnit.SECONDS);
            }
            record(rows, f, concurrent, boundary + "_CONCURRENT_DUPLICATE", "SUCCESS");
            var newer = f.event(seed, 2);
            f.produce(boundary, newer, "NONE");
            f.tx.executeWithoutResult(s -> f.effect(newer, "NONE"));
            Thread.sleep(20);
            f.tx.executeWithoutResult(s -> f.effect(e, "NONE"));
            record(rows, f, e, boundary + "_DELAYED_DUPLICATE", "SUCCESS");

            var old = f.event(seed + 1, 1);
            var next = f.event(seed + 1, 2);
            f.tx.executeWithoutResult(s -> { f.business(old); f.business(next); });
            f.tx.executeWithoutResult(s -> f.effect(next, "NONE"));
            f.tx.executeWithoutResult(s -> f.effect(old, "NONE"));
            record(rows, f, old, boundary + "_REVERSED", "SUCCESS");
            var forged = new EventFixture.Event(e.eventId(), next.tenantId(), e.aggregateId(),
                e.eventType(), 1, e.occurredAt(), 1, e.payload());
            record(rows, f, forged, boundary + "_FORGED_TENANT", reject(() ->
                f.tx.executeWithoutResult(s -> f.effect(forged, "NONE"))));
            var changed = new EventFixture.Event(e.eventId(), e.tenantId(), e.aggregateId(),
                e.eventType(), 1, e.occurredAt(), 9, e.payload());
            record(rows, f, changed, boundary + "_IDENTITY_CORRUPTION", reject(() ->
                f.tx.executeWithoutResult(s -> f.effect(changed, "NONE"))));
        }
        var rollback = f.event(82001, 1);
        String rollbackOutcome = reject(() -> f.tx.executeWithoutResult(outer -> {
            f.produce(EventFixture.Boundary.DURABLE, rollback, "NONE");
            throw new IllegalStateException("OUTER_ROLLBACK");
        }));
        record(rows, f, rollback, "OUTER_ROLLBACK", rollbackOutcome);
        var invalid = f.event(82002, 1);
        var syntax = new EventFixture.Event(invalid.eventId(), invalid.tenantId(), invalid.aggregateId(),
            invalid.eventType(), 1, invalid.occurredAt(), 1, "{broken");
        record(rows, f, syntax, "INVALID_JSON", reject(() ->
            f.produce(EventFixture.Boundary.DURABLE, syntax, "NONE")));
        record(rows, f, invalid, "APPEND_WITHOUT_TRANSACTION", reject(() -> f.append(invalid)));

        var delivery = new DeliveryProbe(f);
        delivery.schema();
        for (String fault : List.of("TRANSIENT", "EXHAUSTION", "UNKNOWN_TYPE", "UNKNOWN_VERSION",
            "PAYLOAD_INVARIANT", "AFTER_CLAIM", "AFTER_EFFECT")) {
            long seed = 83000 + rows.size();
            var base = f.event(seed, 1);
            var e = new EventFixture.Event(base.eventId(), base.tenantId(), base.aggregateId(),
                fault.equals("UNKNOWN_TYPE") ? "Unknown" : base.eventType(),
                fault.equals("UNKNOWN_VERSION") ? 2 : 1, base.occurredAt(), 1,
                fault.equals("PAYLOAD_INVARIANT") ? "{\"valid\":false}" : base.payload());
            f.produce(EventFixture.Boundary.DURABLE, e, "NONE");
            if (fault.startsWith("AFTER_")) {
                int exit = EventExperimentRunner.child(mysql, "DELIVER", seed, fault, 80);
                record(rows, f, e, fault + "_CRASH_EXIT_" + exit, "CRASH");
                waitLease(f, e.eventId());
                Long newerToken = delivery.claim(e.eventId());
                record(rows, f, e, fault + "_STALE_OWNER", delivery.deliver(e, newerToken - 1, "NONE"));
                record(rows, f, e, fault + "_RECOVER", delivery.deliver(e, newerToken, "NONE"));
            } else {
                int attempts = fault.equals("EXHAUSTION") ? 3 : fault.equals("TRANSIENT") ? 2 : 1;
                for (int i = 0; i < attempts; i++) {
                    String injection = fault.equals("EXHAUSTION") || (fault.equals("TRANSIENT") && i == 0)
                        ? "HANDLER_FAILURE" : "NONE";
                    record(rows, f, e, fault + "_ATTEMPT_" + (i + 1),
                        delivery.deliver(e, delivery.claim(e.eventId()), injection));
                }
                if (fault.equals("EXHAUSTION")) {
                    record(rows, f, e, "EXHAUSTED_NO_AUTO_RETRY", delivery.claim(e.eventId()) == null ? "BLOCKED" : "ERROR");
                    record(rows, f, e, "REPLAY_UNTRUSTED", reject(() -> delivery.replay(e.tenantId(), e.eventId(), "test", false)));
                    record(rows, f, e, "REPLAY_CROSS_TENANT", reject(() -> delivery.replay(f.event(1, 1).tenantId(), e.eventId(), "test", true)));
                    delivery.replay(e.tenantId(), e.eventId(), "fixture transient dependency repaired", true);
                    record(rows, f, e, "REPLAY_AUDIT", "REPLAYED");
                    record(rows, f, e, "REPLAY_RECOVER", delivery.deliver(e, delivery.claim(e.eventId()), "NONE"));
                }
            }
        }
        var crashBudget = f.event(84999, 1);
        f.produce(EventFixture.Boundary.DURABLE, crashBudget, "NONE");
        for (int attempt = 1; attempt <= 3; attempt++) {
            EventExperimentRunner.child(mysql, "DELIVER", 84999, "AFTER_CLAIM", 80);
            waitLease(f, crashBudget.eventId());
            record(rows, f, crashBudget, "AFTER_CLAIM_BUDGET_" + attempt, "CRASH");
        }
        record(rows, f, crashBudget, "AFTER_CLAIM_EXHAUSTED",
            delivery.claim(crashBudget.eventId()) == null ? "BLOCKED" : "ERROR");
        return rows;
    }

    private static void waitLease(EventFixture f, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (f.db.queryForObject("SELECT lease_until > CURRENT_TIMESTAMP(6) FROM fixture_delivery WHERE event_id=?",
            Boolean.class, id)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("lease did not expire");
            Thread.sleep(10);
        }
    }

    private static String reject(Runnable action) {
        try { action.run(); return "UNEXPECTED_SUCCESS"; }
        catch (RuntimeException expected) {
            return expected instanceof IllegalArgumentException || expected instanceof IllegalStateException
                || expected instanceof SecurityException ? expected.getMessage() : expected.getClass().getSimpleName();
        }
    }

    private static void record(List<Map<String, Object>> rows, EventFixture f, EventFixture.Event e,
                               String scenario, String outcome) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenario", scenario);
        row.put("event", e);
        row.put("outcome", outcome);
        row.put("snapshot", f.snapshot(e));
        row.put("projection", f.db.queryForList("SELECT * FROM fixture_projection WHERE aggregate_id=?", e.aggregateId()));
        row.put("effects", f.db.queryForList("SELECT * FROM fixture_effect WHERE event_id=?", e.eventId()));
        if (scenario.contains("ATTEMPT") || scenario.contains("REPLAY") || scenario.startsWith("AFTER_")
            || scenario.equals("EXHAUSTED_NO_AUTO_RETRY")) {
            row.put("delivery", f.db.queryForList("SELECT * FROM fixture_delivery WHERE event_id=?", e.eventId()));
            row.put("audit", f.db.queryForList("SELECT * FROM fixture_replay WHERE event_id=?", e.eventId()));
        }
        rows.add(row);
    }
}
