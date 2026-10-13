package com.slotq.ai.flow;

import com.slotq.ai.runtime.AgentRuntime;
import com.slotq.ai.runtime.RuntimeContract.*;
import java.time.*;
import java.util.*;

/** Controlled server-side client of the same Runtime. No public API, approval automation or mutation loop. */
public final class RepresentativeFlows {
    public static final String REVISION = "m7-flow-v1";
    public record Flow(UUID id, Plan plan) { }
    private final AgentRuntime runtime;
    private final Limits limits;

    public RepresentativeFlows(AgentRuntime runtime, Limits limits) {
        this.runtime = Objects.requireNonNull(runtime);
        this.limits = Objects.requireNonNull(limits);
    }

    public Flow customerHold(String credential, UUID slot, int partySize, String knowledgeQuery) {
        return start(credential, "customer", new HoldMaterial(slot, partySize), knowledge(knowledgeQuery));
    }

    public Flow customerRead(String credential, UUID reservation, String knowledgeQuery) {
        var reads = knowledge(knowledgeQuery);
        reads.put("reservation.get", Map.of("reservationId", Objects.requireNonNull(reservation).toString()));
        return start(credential, "customer", null, reads);
    }

    /** A single Venue-local day is the bounded date range supported by the existing Product operation. */
    public Flow management(String credential, LocalDate date, String knowledgeQuery) {
        var reads = knowledge(knowledgeQuery);
        reads.put("management.reservations.list", Map.of("date", Objects.requireNonNull(date).toString()));
        return start(credential, "management", null, reads);
    }

    public Flow ops(String credential, Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw new IllegalArgumentException("Invalid observation window");
        }
        // Scope reduction keeps both the stored result and model context finite; truncation remains explicit.
        return start(credential, "ops", null, Map.of("management.event-observations.list",
            Map.of("from", from.toString(), "to", to.toString(), "limit", 3)));
    }

    public Result prepareReads(Flow flow, String credential) {
        Result result = runtime.result(flow.id(), credential);
        for (var read : flow.plan().reads().entrySet()) {
            result = runtime.read(flow.id(), credential, read.getKey(), read.getValue());
        }
        return result;
    }

    public Result generate(Flow flow, String credential) {
        // A new model transmission observes current publication metadata through the existing scoped Knowledge tool.
        // Retrieval I/O stays outside Runtime context assembly and consumes the original live tool budget.
        runtime.readPlanned(flow.id(), credential, "knowledge.search");
        return runtime.generate(flow.id(), credential);
    }

    private Flow start(String credential, String workload, HoldMaterial hold, Map<String,Map<String,Object>> reads) {
        var plan = new Plan(REVISION, workload, limits, hold, reads);
        return new Flow(runtime.start(credential, plan), plan);
    }

    private static Map<String,Map<String,Object>> knowledge(String query) {
        var reads = new TreeMap<String,Map<String,Object>>();
        if (query != null && !query.isBlank()) {
            if (query.length() > 200) throw new IllegalArgumentException("Knowledge query bound");
            reads.put("knowledge.search", Map.of("query", query, "limit", 1));
        }
        return reads;
    }
}
