package com.slotq.mcp;

import com.slotq.ai.flow.RepresentativeFlows;
import com.slotq.ai.router.*;
import com.slotq.ai.runtime.*;
import com.slotq.ai.runtime.RuntimeContract.*;
import com.slotq.evaluation.*;
import com.slotq.integration.mcp.product.ProductHttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.mcp.RepresentativeFlowIntegrationTests.bytes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Connects the existing integration fixture to the evaluator; the provider remains deterministic. */
final class M8RuntimeEvaluation {
    private final RepresentativeFlowIntegrationTests f;
    private final List<ObjectNode> wire = new ArrayList<>();
    private boolean approved;
    private record Fixture(UUID tenant, com.slotq.venue.domain.VenueId venue, UUID slot,
                           RepresentativeFlowIntegrationTests.Identity customer, RepresentativeFlowIntegrationTests.Identity owner,
                           RepresentativeFlowIntegrationTests.Identity manager, LocalDate day, Instant base) { }
    M8RuntimeEvaluation(RepresentativeFlowIntegrationTests f) { this.f = f; }

    @SuppressWarnings("unchecked")
    void run() throws Exception {
        var target = M8MutationTarget.disposable(f.MYSQL, f.ORIGIN);
        var ids = List.of("m7-flow/customer-hold-proposal", "m7-flow/customer-hold-answer", "m7-flow/management-owner", "m7-flow/ops-manager", "m8/customer-business-rejected", "m8/customer-not-sent", "m8/unknown-committed");
        var cells = M8Evaluation.cells(ids, Set.of("deterministic-runtime"), Set.of("DETERMINISTIC"), 1);
        String runId = "runtime-" + UUID.randomUUID(); var fixtures = new HashMap<String, Fixture>(); var bindings = new HashMap<String, JsonNode>();
        for (var cell : cells) {
            String group = cell.caseId().startsWith("m7-flow/customer-hold-") ? "customer" : cell.caseId();
            if (!fixtures.containsKey(group)) {
                f.registrations.deactivate(f.observationRegistration); f.registrations.deactivate(f.waitlistRegistration);
                f.fixtureAt(Instant.parse("2026-10-20T12:00:00Z"));
                f.db.update("UPDATE venues SET timezone='Asia/Seoul' WHERE id=?", bytes(f.venue.value()));
                if (cell.caseId().equals("m7-flow/ops-manager")) {
                    f.db.update("UPDATE event_observation_projections SET projected_at=? WHERE tenant_id=?",
                        java.sql.Timestamp.from(f.clock.instant()), bytes(f.tenant));
                }
                if (cell.caseId().equals("m8/customer-business-rejected")) {
                    f.db.update("UPDATE slot_inventories SET starts_at=?,ends_at=? WHERE id=?", java.sql.Timestamp.from(f.clock.instant().minusSeconds(3600)),
                        java.sql.Timestamp.from(f.clock.instant().minusSeconds(1800)), bytes(f.slot));
                }
                fixtures.put(group, new Fixture(f.tenant, f.venue, f.slot, f.customer, f.owner, f.manager, f.day, f.clock.instant()));
            }
            Fixture fixture = fixtures.get(group);
            var actual = new HashMap<>(Map.of(
                "tenant-a", fixture.tenant().toString(), "venue-a", fixture.venue().value().toString(), "slot-demo-A", fixture.slot().toString(),
                "customer-a", fixture.customer().principal().toString(), "owner-a", fixture.owner().principal().toString(), "manager-a", fixture.manager().principal().toString()));
            if (group.equals("customer") || cell.caseId().equals("m8/unknown-committed")) actual.put("reservation-a", "UNBOUND");
            var slotTimes = f.db.queryForMap("SELECT starts_at,ends_at FROM slot_inventories WHERE id=?", bytes(fixture.slot()));
            actual.put("time:slotStartsAt", ((java.sql.Timestamp) slotTimes.get("starts_at")).toInstant().toString());
            actual.put("time:slotEndsAt", ((java.sql.Timestamp) slotTimes.get("ends_at")).toInstant().toString());
            bindings.computeIfAbsent(group, g -> M8Bindings.create(cell, runId, fixture.base(), actual));
        }
        var manifest = target.manifest(fixtures.get("customer").base(), true, f.db);
        var session = new M8Evaluation(runId, cells, manifest, new M8Evaluation.Budget(cells.size(), 20, 100000, Instant.now().plusSeconds(180), true),
            c -> bindings.get(c.caseId().startsWith("m7-flow/customer-hold-") ? "customer" : c.caseId()), target);
        doAnswer(invocation -> {
            ProductHttpBinding.Prepared prepared = invocation.getArgument(0); String query = invocation.getArgument(1); Map<String, Object> body = invocation.getArgument(2);
            var row = f.json.createObjectNode(); row.put("tool", prepared.method().equals("POST") ? "reservation.hold"
                : prepared.uri().getPath().endsWith("event-observations") ? "management.event-observations.list"
                : prepared.uri().getPath().contains("management") ? "management.reservations.list" : "reservation.get");
            row.put("sent", true).put("responseObserved", false);
            row.putNull("authorized");
            var args = row.putObject("arguments");
            if (body != null) { args.set("slotInventoryId", f.json.valueToTree(body.get("slotInventoryId"))); args.set("partySize", f.json.valueToTree(body.get("partySize"))); }
            if (query != null) for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2); String value = java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
                if (parts[0].equals("limit")) args.put(parts[0], Integer.parseInt(value)); else args.put(parts[0], value);
            }
            if (row.path("tool").asString().equals("reservation.get")) args.put("reservationId", prepared.uri().getPath().substring(prepared.uri().getPath().lastIndexOf('/') + 1));
            wire.add(row);
            ProductHttpClient.Result response = (ProductHttpClient.Result) invocation.callRealMethod();
            row.put("responseObserved", !response.unknown()).put("unknown", response.unknown());
            if (response.status() != null) row.put("status", response.status());
            if (response.knownTarget() != null) row.put("target", response.knownTarget().toString());
            if (response.data() != null) row.set("response", f.json.valueToTree(response.data()));
            return response;
        }).when(f.productClient).exchange(any(), nullable(String.class), nullable(Map.class));
        var live = new HashMap<String, AgentRuntime>(); var flows = new HashMap<String, RepresentativeFlows.Flow>();
        var result = session.execute((cell, input, recorder) -> {
            String group = cell.caseId().startsWith("m7-flow/customer-hold-") ? "customer" : cell.caseId(); Fixture fixture = fixtures.get(group);
            f.tenant = fixture.tenant(); f.venue = fixture.venue(); f.slot = fixture.slot(); f.customer = fixture.customer(); f.owner = fixture.owner(); f.manager = fixture.manager();
            f.day = fixture.day(); f.clock.now = fixture.base(); f.fault.reset(); wire.clear(); approved = false;
            var runtime = live.computeIfAbsent(group, g -> f.runtime((m, c, q, b) -> {
                var response = f.fakeProvider(m, c, q, b);
                if (response.output().outcome().equals("UNKNOWN") || g.startsWith("m8/")) {
                    var original = response.output(); var output = new ProviderProtocol.Output(original.outcome().equals("UNKNOWN") ? "INSUFFICIENT" : original.disposition(),
                        original.tool(), original.target(), original.partySize(), original.outcome(), g.startsWith("m8/") ? List.of() : original.sourceRefs(),
                        g.startsWith("m8/") ? List.of() : original.claimRefs(), original.answer());
                    response = RepresentativeFlowIntegrationTests.response(output, null, true); f.providerResults.set(f.providerResults.size() - 1, response);
                }
                return response;
            }, Duration.ofSeconds(5)));
            var actor = cell.caseId().contains("management") ? f.owner : cell.caseId().contains("ops-manager") ? f.manager : f.customer;
            var composition = new RepresentativeFlows(runtime, f.limits(Duration.ofSeconds(5)));
            var expectedPlan = M8Evaluation.item(cell.caseId()).path("serverPlan");
            var flow = flows.computeIfAbsent(group, g -> cell.caseId().contains("management") ? composition.management(actor.delegation().value(), LocalDate.parse(expectedPlan.path("management.reservations.list").path("date").asString()), expectedPlan.path("knowledge.search").path("query").asString())
                : cell.caseId().contains("ops-manager") ? composition.ops(actor.delegation().value(), Instant.parse(expectedPlan.path("management.event-observations.list").path("from").asString()), Instant.parse(expectedPlan.path("management.event-observations.list").path("to").asString()))
                : composition.customerHold(actor.delegation().value(), f.slot, 2, "guide"));
            var before = database(); recorder.put("independent-db-before", before);
            var actualArguments = new TreeMap<String, Map<String, Object>>();
            boolean answerStage = cell.caseId().equals("m7-flow/customer-hold-answer");
            if (!answerStage) for (var read : flow.plan().reads().entrySet()) {
                actualArguments.put(read.getKey(), read.getValue()); runtime.read(flow.id(), actor.delegation().value(), read.getKey(), read.getValue());
            }
            recorder.put("server-plan", flow.plan().reads());
            recorder.put("flow-plan", flow.plan());
            recorder.put("actor", f.access.revalidate(actor.delegation().delegationId()));
            int callsBefore = f.providerResults.size();
            boolean notSent = cell.caseId().equals("m8/customer-not-sent");
            if (!answerStage) {
                composition.generate(flow, actor.delegation().value());
                if (flow.plan().hold() != null) {
                    recorder.put("proposal-stage", Map.of("output", f.providerResults.getLast().output(), "request", M8Bindings.request(f.requests.getLast())));
                }
            }
            if (notSent) assertThatThrownBy(() -> runtime.dispatchHold(flow.id(), actor.delegation().value())).hasMessage("APPROVAL");
            boolean mutationStage = answerStage || cell.caseId().startsWith("m8/") && !notSent;
            if (mutationStage) {
                f.approve(runtime, flow, actor); approved = true;
                if (cell.caseId().equals("m8/unknown-committed")) f.fault.arm(f.venue, true, false);
                var mutationResult = runtime.dispatchHold(flow.id(), actor.delegation().value());
                if (mutationResult.knownTarget() != null) recorder.bindCreatedReservation("reservation-a", mutationResult.knownTarget());
                var mutationObservation = f.json.createObjectNode();
                mutationObservation.put("observationKind", "PRODUCTION_RUNTIME_MUTATION_BEFORE_ANSWER");
                var initialExecution = execution(mutationResult); initialExecution.remove("reconciliation");
                mutationObservation.set("execution", initialExecution); mutationObservation.set("runtime", f.json.valueToTree(mutationResult));
                recorder.put("mutation-observation", mutationObservation);
                recorder.put("mutation-transmissions", transmissions());
                recorder.put("independent-db-after-mutation", databaseObservation(before));
                f.fault.reset();
                if (cell.caseId().equals("m8/unknown-committed")) runtime.reconcile(flow.id(), actor.delegation().value());
                if (answerStage) for (var read : flow.plan().reads().entrySet()) {
                    actualArguments.put(read.getKey(), read.getValue()); runtime.read(flow.id(), actor.delegation().value(), read.getKey(), read.getValue());
                }
                composition.generate(flow, actor.delegation().value());
            }
            recorder.put("tool-arguments", actualArguments);
            Result observed = runtime.result(flow.id(), actor.delegation().value());
            if (observed.knownTarget() != null) recorder.bindCreatedReservation("reservation-a", observed.knownTarget());
            var request = M8Bindings.request(f.requests.getLast()); recorder.put("request", request);
            var provider = f.providerResults.getLast(); var raw = f.json.createObjectNode();
            raw.put("observationKind", "PRODUCTION_RUNTIME_DETERMINISTIC_PROVIDER").put("providerCalls", f.providerResults.size() - callsBefore)
                .put("tokens", (f.providerResults.size() - callsBefore) * 100).put("failure", provider.failure() == null ? "" : provider.failure().name());
            if (notSent) {
                raw.putNull("proposal");
                raw.putObject("boundaryRejection").put("observed", true).put("layer", "RUNTIME").put("stage", "AFTER_PROPOSAL")
                    .put("reason", "APPROVAL").put("disposition", "REJECT").put("tool", "none");
            } else raw.set("proposal", f.json.valueToTree(provider.output()));
            raw.set("execution", execution(observed)); raw.set("runtime", f.json.valueToTree(observed));
            recorder.put("result", raw);
            if (!notSent) recorder.put("answer", provider.output());
            recorder.put("tool-transmissions", transmissions());
            recorder.put("independent-db", databaseObservation(before));
            var disclosure = f.json.createObjectNode(); disclosure.put("observed", true); disclosure.set("content", f.json.valueToTree(List.of(request, raw.path("proposal"))));
            disclosure.putArray("references"); disclosure.putArray("forbiddenValues").add("synthetic-private-sentinel"); recorder.put("disclosure", disclosure);
            for (var transmission : wire) if (actualArguments.containsKey(transmission.path("tool").asString())) {
                assertThat(transmission.path("arguments")).isEqualTo(f.json.valueToTree(actualArguments.get(transmission.path("tool").asString())));
            }
            RepresentativeFlowIntegrationTests.requireNoDisclosure(request.toString(), actor.original().value(), actor.delegation().value(), "idempotencyKey");
        });
        assertThat(result.path("rows")).hasSize(cells.size());
        for (var row : result.path("rows")) {
            String caseId = row.path("cell").path("caseId").asString();
            boolean notSent = caseId.equals("m8/customer-not-sent");
            boolean sent = !Set.of("m7-flow/customer-hold-proposal", "m8/customer-not-sent").contains(caseId);
            assertThat(row.path("automaticStatus").asString()).as(row.path("cell").toString() + row.path("reasons")).isEqualTo(notSent || sent ? "INCOMPLETE" : "PASS");
            assertThat(row.path("hardGateViolations")).isEmpty();
            assertThat(row.path("checks").path("forbidden_dispatch").path("status").asString()).isEqualTo(sent ? "INCOMPLETE" : "PASS");
            if (sent && !Set.of("m7-flow/management-owner", "m7-flow/ops-manager").contains(caseId)) {
                assertThat(row.path("checks").path("execution_approval").path("status").asString()).isEqualTo("INCOMPLETE");
                for (String check : List.of("execution_invocation", "execution_effect", "execution_db")) {
                    assertThat(row.path("checks").path(check).path("status").asString()).as(check).isEqualTo("PASS");
                }
            }
            if (notSent) {
                assertThat(row.path("axisResults").path("tool_selection").path("status").asString()).isEqualTo("INCOMPLETE");
                assertThat(row.path("modelProposalObserved").asBoolean()).isTrue();
                assertThat(row.path("finalModelProposalObserved").asBoolean()).isFalse();
                assertThat(row.path("checks").path("answer_sources").path("status").asString()).isEqualTo("INCOMPLETE");
                for (String check : List.of("runtime_boundary_rejection", "proposal_stage_quality", "execution_invocation", "execution_db", "forbidden_dispatch")) {
                    assertThat(row.path("checks").path(check).path("status").asString()).as(check).isEqualTo("PASS");
                }
            }
        }
        assertThat(result.path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest())).isEqualTo(result);
        Files.writeString(Path.of("../build/m8-evidence/latest-runtime-run.txt"), runId + "\n" + session.evidenceDigest());
    }

    private ObjectNode execution(Result observed) {
        var execution = f.json.createObjectNode();
        var mutation = wire.stream().filter(w -> w.path("tool").asString().equals("reservation.hold")).findFirst();
        execution.put("invocation", mutation.map(w -> w.path("unknown").asBoolean() ? "UNKNOWN" : "RESPONSE_OBSERVED").orElse("NOT_DISPATCHED"));
        execution.put("effect", observed.productEffect().name()).put("knownTarget", observed.knownTarget() == null ? "" : observed.knownTarget().toString());
        execution.put("reconciliation", observed.reconciliationResult().isEmpty() ? "NOT_REQUESTED" : "EXACT_GET_HELD");
        execution.set("runtimeTools", f.json.valueToTree(observed.tools()));
        return execution;
    }

    private ObjectNode transmissions() {
        var transmissions = f.json.createObjectNode(); transmissions.set("attempts", f.json.valueToTree(wire));
        transmissions.putObject("approval").put("approved", approved).putNull("exactActor");
        for (var entry : wire) if (entry.path("tool").asString().equals("reservation.get")) {
            var get = transmissions.putObject("exactGet"); get.put("sent", true).set("target", entry.path("arguments").path("reservationId"));
            get.set("status", entry.path("status")); get.set("state", entry.path("response").path("state"));
        }
        return transmissions;
    }

    private ObjectNode databaseObservation(JsonNode before) {
        var db = f.json.createObjectNode(); db.put("observed", true); db.set("before", before); db.set("after", database());
        return db;
    }

    private ObjectNode database() {
        var state = f.json.createObjectNode();
        for (var entry : Map.of("reservations", "reservations", "allocations", "capacity_allocations", "idempotency", "hold_idempotency_records").entrySet()) {
            state.put(entry.getKey(), f.db.queryForObject("SELECT COUNT(*) FROM " + entry.getValue() + " WHERE slot_inventory_id=?", Integer.class, bytes(f.slot)));
        }
        state.put("events", f.db.queryForObject("SELECT COUNT(*) FROM event_records WHERE tenant_id=?", Integer.class, bytes(f.tenant)));
        state.put("activeUnits", f.db.queryForObject("SELECT COALESCE(SUM(units),0) FROM capacity_allocations WHERE slot_inventory_id=? AND active=TRUE", Integer.class, bytes(f.slot)));
        state.put("capacity", f.db.queryForObject("SELECT capacity FROM slot_inventories WHERE id=?", Integer.class, bytes(f.slot)));
        state.put("seatingCapacity", f.db.queryForObject("SELECT r.seating_capacity FROM resources r JOIN slot_inventories s ON r.id=s.resource_id WHERE s.id=?", Integer.class, bytes(f.slot)));
        var rows = f.db.queryForList("SELECT BIN_TO_UUID(id) AS id, state, party_size FROM reservations WHERE slot_inventory_id=?", bytes(f.slot));
        if (rows.size() == 1) {
            state.put("reservationId", rows.getFirst().get("id").toString().toLowerCase(Locale.ROOT)).put("state", rows.getFirst().get("state").toString())
                .put("partySize", ((Number) rows.getFirst().get("party_size")).intValue());
            state.put("allocationReservationId", Objects.toString(f.db.queryForObject("SELECT BIN_TO_UUID(reservation_id) FROM capacity_allocations WHERE slot_inventory_id=?", String.class, bytes(f.slot)), "").toLowerCase(Locale.ROOT));
            var keys = f.db.queryForList("SELECT BIN_TO_UUID(reservation_id) AS id,state,CASE WHEN completed_at IS NOT NULL THEN 1 ELSE 0 END AS completed FROM hold_idempotency_records WHERE slot_inventory_id=?", bytes(f.slot));
            state.put("idempotencyReservationId", keys.isEmpty() ? "" : Objects.toString(keys.getFirst().get("id"), "").toLowerCase(Locale.ROOT));
            if (!keys.isEmpty()) state.put("idempotencyState", keys.getFirst().get("state").toString()).put("idempotencyCompleted", ((Number) keys.getFirst().get("completed")).intValue() == 1);
        }
        return state;
    }
}
