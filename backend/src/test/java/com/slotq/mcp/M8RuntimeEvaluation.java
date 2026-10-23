package com.slotq.mcp;

import com.slotq.ai.flow.RepresentativeFlows;
import com.slotq.ai.router.*;
import com.slotq.ai.runtime.*;
import com.slotq.ai.runtime.RuntimeContract.*;
import com.slotq.evaluation.*;
import com.slotq.auth.access.ProductOperation;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.venue.domain.VenueId;
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

/** Connects the existing fixture to explicit deterministic, fault and opt-in provider observations. */
final class M8RuntimeEvaluation {
    private final RepresentativeFlowIntegrationTests f;
    private final List<ObjectNode> wire = new ArrayList<>();
    private ObjectNode approval;
    private RepresentativeFlowIntegrationTests.Identity activeActor;
    private final Map<UUID, ObjectNode> pendingAdmissions = new java.util.concurrent.ConcurrentHashMap<>();
    private record Fixture(UUID tenant, com.slotq.venue.domain.VenueId venue, UUID slot,
                           RepresentativeFlowIntegrationTests.Identity customer, RepresentativeFlowIntegrationTests.Identity owner,
                           RepresentativeFlowIntegrationTests.Identity manager, LocalDate day, Instant base) { }
    M8RuntimeEvaluation(RepresentativeFlowIntegrationTests f) { this.f = f; }

    private static String group(M8Evaluation.Cell cell) {
        return (cell.caseId().startsWith("m7-flow/customer-hold-") ? "customer" : cell.caseId()) + ":" + cell.repeat() + ":" + cell.model();
    }

    @SuppressWarnings("unchecked")
    void run() throws Exception {
        run(null, false);
    }

    void run(JsonNode qualification) throws Exception {
        run(qualification, false);
    }

    void rejectAnswerBeforeProvider() throws Exception {
        run(null, true);
    }

    @SuppressWarnings("unchecked")
    private void run(JsonNode qualification, boolean rejectAnswerBeforeProvider) throws Exception {
        boolean actual = qualification != null;
        var target = M8MutationTarget.disposable(f.MYSQL, f.ORIGIN);
        var ids = List.of("m7-flow/customer-hold-proposal", "m7-flow/customer-hold-answer", "m7-flow/management-owner", "m7-flow/ops-manager", "m8/customer-business-rejected", "m8/customer-not-sent", "m8/unknown-committed");
        var cells = actual ? M8ModelQualification.cells(qualification) : M8Evaluation.cells(ids,
            Set.of(rejectAnswerBeforeProvider ? "deterministic-runtime-answer-fault" : "deterministic-runtime"), Set.of("DETERMINISTIC"), 1);
        if (actual && cells.stream().anyMatch(c -> !ids.contains(c.caseId()) || !c.lane().equals("ACTUAL_PROVIDER_FLOW"))) {
            throw new IllegalArgumentException("Only the existing seven Runtime scenarios are connected; unsupported required paths remain INCOMPLETE");
        }
        String runId = (actual ? "model-runtime-" : rejectAnswerBeforeProvider ? "runtime-answer-fault-" : "runtime-") + UUID.randomUUID();
        var fixtures = new HashMap<String, Fixture>(); var bindings = new HashMap<String, JsonNode>();
        for (var cell : cells) {
            String group = group(cell);
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
            var actualBindings = new HashMap<>(Map.of(
                "tenant-a", fixture.tenant().toString(), "venue-a", fixture.venue().value().toString(), "slot-demo-A", fixture.slot().toString(),
                "customer-a", fixture.customer().principal().toString(), "owner-a", fixture.owner().principal().toString(), "manager-a", fixture.manager().principal().toString()));
            if (cell.caseId().startsWith("m7-flow/customer-hold-") || cell.caseId().equals("m8/unknown-committed")) actualBindings.put("reservation-a", "UNBOUND");
            var slotTimes = f.db.queryForMap("SELECT starts_at,ends_at FROM slot_inventories WHERE id=?", bytes(fixture.slot()));
            actualBindings.put("time:slotStartsAt", ((java.sql.Timestamp) slotTimes.get("starts_at")).toInstant().toString());
            actualBindings.put("time:slotEndsAt", ((java.sql.Timestamp) slotTimes.get("ends_at")).toInstant().toString());
            bindings.computeIfAbsent(group, g -> M8Bindings.create(cell, runId, fixture.base(), actualBindings));
        }
        var manifest = target.manifest(fixtures.values().iterator().next().base(), true, f.db);
        var limits = f.limits(Duration.ofSeconds(actual ? 45 : 5));
        ((ObjectNode) manifest.path("configuration")).put("runtimeSteps", limits.steps()).put("runtimeProviderAttempts", limits.providerAttempts())
            .put("runtimeToolAttempts", limits.toolAttempts()).put("runtimeTokens", limits.tokens()).put("runtimeOutputTokens", limits.outputTokens())
            .put("runtimeLifetimeSeconds", limits.lifetime().toSeconds()).put("runtimeAttemptTimeoutSeconds", limits.attemptTimeout().toSeconds())
            .put("providerWorkers", 4).put("providerConcurrency", 4);
        if (actual) M8ModelQualification.enableProvider(manifest, qualification);
        var session = new M8Evaluation(runId, cells, manifest, actual ? M8ModelQualification.budget(qualification)
            : new M8Evaluation.Budget(cells.size(), 20, 100000, Instant.now().plusSeconds(180), true), c -> bindings.get(group(c)), target, qualification);
        var actualProvider = actual ? new M8ActualProvider(qualification) : null;
        observeApplicationAdmissions();
        doAnswer(invocation -> {
            ProductHttpBinding.Prepared prepared = invocation.getArgument(0); String query = invocation.getArgument(1); Map<String, Object> body = invocation.getArgument(2);
            var row = f.json.createObjectNode(); row.put("tool", prepared.method().equals("POST") ? "reservation.hold"
                : prepared.uri().getPath().endsWith("event-observations") ? "management.event-observations.list"
                : prepared.uri().getPath().contains("management") ? "management.reservations.list" : "reservation.get");
            row.put("sent", true).put("responseObserved", false);
            row.putNull("authorized");
            pendingAdmissions.put(prepared.credential().id(), row);
            var args = row.putObject("arguments");
            if (body != null) {
                args.set("slotInventoryId", f.json.valueToTree(body.get("slotInventoryId")));
                args.set("partySize", f.json.valueToTree(body.get("partySize")));
                int exactMaterial = f.db.queryForObject("SELECT COUNT(*) FROM mcp_hold_intents i JOIN mcp_hold_confirmations c ON c.intent_id=i.id "
                    + "WHERE i.id=? AND c.id=? AND i.slot_id=? AND i.party_size=? AND i.product_key=?",
                    Integer.class, bytes(UUID.fromString(approval.path("intent").asString())), bytes(UUID.fromString(approval.path("confirmation").asString())),
                    bytes((UUID) body.get("slotInventoryId")), body.get("partySize"), body.get("idempotencyKey"));
                approval.put("exactActor", approval.path("exactActor").asBoolean() && exactMaterial == 1);
                row.put("approvalMaterialMatched", exactMaterial == 1);
            }
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
        long wallStarted = System.nanoTime();
        var result = session.execute((cell, input, recorder) -> {
            String group = group(cell); Fixture fixture = fixtures.get(group);
            f.tenant = fixture.tenant(); f.venue = fixture.venue(); f.slot = fixture.slot(); f.customer = fixture.customer(); f.owner = fixture.owner(); f.manager = fixture.manager();
            f.day = fixture.day(); f.clock.now = actual ? fixture.base().plusNanos(System.nanoTime() - wallStarted) : fixture.base();
            f.fault.reset(); wire.clear(); pendingAdmissions.clear();
            approval = f.json.createObjectNode().put("approved", false).put("exactActor", false);
            if (actual) actualProvider.enter(cell, recorder);
            var runtime = live.computeIfAbsent(group, g -> f.runtime((m, c, q, b) -> {
                if (actual) {
                    f.clock.now = fixture.base().plusNanos(System.nanoTime() - wallStarted);
                    RepresentativeFlowIntegrationTests.requireNoDisclosure(q.prompt(), fixture.tenant().toString(),
                        fixture.customer().principal().toString(), fixture.owner().principal().toString(), fixture.manager().principal().toString(),
                        fixture.customer().original().value(), fixture.customer().delegation().value(), fixture.owner().original().value(),
                        fixture.owner().delegation().value(), fixture.manager().original().value(), fixture.manager().delegation().value(), "idempotencyKey");
                    f.requests.add(q);
                    try {
                        var response = actualProvider.generate(m, c, q, b); f.providerResults.add(response); return response;
                    } finally { f.clock.now = fixture.base().plusNanos(System.nanoTime() - wallStarted); }
                }
                var response = f.fakeProvider(m, c, q, b);
                if (response.output().outcome().equals("UNKNOWN") || g.startsWith("m8/")) {
                    var original = response.output(); var output = new ProviderProtocol.Output(original.outcome().equals("UNKNOWN") ? "INSUFFICIENT" : original.disposition(),
                        original.tool(), original.target(), original.partySize(), original.outcome(), g.startsWith("m8/") ? List.of() : original.sourceRefs(),
                        g.startsWith("m8/") ? List.of() : original.claimRefs(), original.answer());
                    response = RepresentativeFlowIntegrationTests.response(output, null, true); f.providerResults.set(f.providerResults.size() - 1, response);
                }
                return response;
            }, Duration.ofSeconds(actual ? 45 : 5)));
            var actor = cell.caseId().contains("management") ? f.owner : cell.caseId().contains("ops-manager") ? f.manager : f.customer;
            activeActor = actor;
            var composition = new RepresentativeFlows(runtime, f.limits(Duration.ofSeconds(actual ? 45 : 5)));
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
            int requestsBefore = f.requests.size();
            int resultProviderFrom = -1;
            int resultRequestFrom = -1;
            boolean notSent = cell.caseId().equals("m8/customer-not-sent");
            boolean approvalRejected = false;
            try {
                if (!answerStage) {
                    if (flow.plan().hold() == null || cell.caseId().equals("m7-flow/customer-hold-proposal")) {
                        resultProviderFrom = f.providerResults.size();
                        resultRequestFrom = f.requests.size();
                    }
                    composition.generate(flow, actor.delegation().value());
                    if (flow.plan().hold() != null) {
                        if (f.providerResults.size() > callsBefore && f.providerResults.getLast().output() != null) {
                            recorder.put("proposal-stage", Map.of("output", f.providerResults.getLast().output(), "request", M8Bindings.request(f.requests.getLast())));
                        }
                    }
                }
                if (notSent) {
                    assertThatThrownBy(() -> runtime.dispatchHold(flow.id(), actor.delegation().value())).hasMessage("APPROVAL");
                    approvalRejected = true;
                }
                boolean mutationStage = answerStage || cell.caseId().startsWith("m8/") && !notSent;
                if (mutationStage) {
                    var reviewed = runtime.reviewHold(flow.id(), actor.delegation().value(), actor.original().value());
                    UUID confirmation = runtime.approveHold(flow.id(), actor.delegation().value(), actor.original().value(), reviewed);
                    observeApproval(reviewed, confirmation, actor);
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
                    // An earlier HOLD proposal cannot stand in for an uncalled answer stage.
                    resultProviderFrom = f.providerResults.size();
                    resultRequestFrom = f.requests.size();
                    if (rejectAnswerBeforeProvider) throw new IllegalStateException("Injected pre-provider answer failure");
                    composition.generate(flow, actor.delegation().value());
                }
            } catch (RuntimeException | AssertionError failedStage) {
                if (!actual && !rejectAnswerBeforeProvider) throw failedStage;
                recorder.put("stage-interruption", Map.of("kind", failedStage.getClass().getSimpleName()));
            }
            if (actual) {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(50)).until(() -> runtime.activeWorkers() == 0);
                f.clock.now = fixture.base().plusNanos(System.nanoTime() - wallStarted);
            }
            recorder.put("tool-arguments", actualArguments);
            Result observed = runtime.result(flow.id(), actor.delegation().value());
            if (observed.knownTarget() != null) recorder.bindCreatedReservation("reservation-a", observed.knownTarget());
            if (notSent && approvalRejected) resultRequestFrom = requestsBefore;
            JsonNode request = resultRequestFrom >= 0 && f.requests.size() > resultRequestFrom ? M8Bindings.request(f.requests.getLast()) : null;
            if (request != null) recorder.put("request", request);
            var provider = resultProviderFrom >= 0 && f.providerResults.size() > resultProviderFrom ? f.providerResults.getLast() : null;
            var raw = f.json.createObjectNode();
            var providerResults = f.providerResults.subList(callsBefore, f.providerResults.size());
            raw.put("observationKind", actual ? "ACTUAL_PROVIDER_PRODUCTION_RUNTIME" : rejectAnswerBeforeProvider
                    ? "PRODUCTION_RUNTIME_DETERMINISTIC_ANSWER_FAULT" : "PRODUCTION_RUNTIME_DETERMINISTIC_PROVIDER")
                .put("providerCalls", actual ? providerResults.stream().mapToInt(GeminiAdapter.Result::attempts).sum() : providerResults.size())
                .put("tokens", actual ? providerResults.stream().filter(r -> r.usage().total() != null).mapToLong(r -> r.usage().total()).sum() : providerResults.size() * 100)
                .put("usageUnknown", actual && providerResults.stream().anyMatch(r -> r.attempts() > 0 && r.usage().total() == null))
                .put("failure", provider == null ? "" : provider.failure() == null ? "" : provider.failure().name());
            if (notSent) {
                raw.putNull("proposal");
                if (approvalRejected) raw.putObject("boundaryRejection").put("observed", true).put("layer", "RUNTIME").put("stage", "AFTER_PROPOSAL")
                    .put("reason", "APPROVAL").put("disposition", "REJECT").put("tool", "none");
            } else raw.set("proposal", provider == null ? f.json.nullNode() : f.json.valueToTree(provider.output()));
            raw.set("execution", execution(observed)); raw.set("runtime", f.json.valueToTree(observed));
            recorder.put("result", raw);
            if (!notSent && provider != null && provider.output() != null) recorder.put("answer", provider.output());
            recorder.put("tool-transmissions", transmissions());
            recorder.put("independent-db", databaseObservation(before));
            var content = f.json.createArrayNode();
            if (request != null) content.add(request);
            content.add(raw.path("proposal"));
            var disclosure = f.json.createObjectNode(); disclosure.put("observed", request != null); disclosure.set("content", content);
            disclosure.putArray("references"); disclosure.putArray("forbiddenValues").add("synthetic-private-sentinel"); recorder.put("disclosure", disclosure);
            for (var transmission : wire) if (actualArguments.containsKey(transmission.path("tool").asString())) {
                assertThat(transmission.path("arguments")).isEqualTo(f.json.valueToTree(actualArguments.get(transmission.path("tool").asString())));
            }
            if (request != null) RepresentativeFlowIntegrationTests.requireNoDisclosure(request.toString(), actor.original().value(), actor.delegation().value(), "idempotencyKey");
        });
        assertThat(result.path("rows")).hasSize(cells.size());
        if (!actual && !rejectAnswerBeforeProvider) {
            for (var row : result.path("rows")) {
                String caseId = row.path("cell").path("caseId").asString();
                boolean notSent = caseId.equals("m8/customer-not-sent");
                boolean sent = !Set.of("m7-flow/customer-hold-proposal", "m8/customer-not-sent").contains(caseId);
                assertThat(row.path("automaticStatus").asString()).as(row.path("cell").toString() + row.path("reasons")).isEqualTo(notSent ? "INCOMPLETE" : "PASS");
                assertThat(row.path("hardGateViolations")).isEmpty();
                assertThat(row.path("checks").path("forbidden_dispatch").path("status").asString()).isEqualTo("PASS");
                if (sent && !Set.of("m7-flow/management-owner", "m7-flow/ops-manager").contains(caseId)) {
                    assertThat(row.path("checks").path("execution_approval").path("status").asString()).isEqualTo("PASS");
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
        }
        if (rejectAnswerBeforeProvider) {
            for (var row : result.path("rows")) {
                if (!Set.of("m7-flow/customer-hold-answer", "m8/customer-business-rejected", "m8/unknown-committed").contains(row.path("cell").path("caseId").asString())) continue;
                assertThat(row.path("finalModelProposalObserved").asBoolean()).as("미호출 최종 답변 대신 초기 제안 재사용 금지").isFalse();
                assertThat(row.path("checks").path("execution_effect").path("status").asString()).isEqualTo("PASS");
                assertThat(row.path("checks").path("execution_db").path("status").asString()).isEqualTo("PASS");
                assertThat(row.path("evidence").has("answer")).isFalse();
                assertThat(row.path("automaticStatus").asString()).isEqualTo("INCOMPLETE");
            }
        }
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest())).isEqualTo(result);
        Files.writeString(Path.of("../build/m8-evidence/latest-" + (actual ? "model-runtime" : rejectAnswerBeforeProvider ? "runtime-answer-fault" : "runtime") + "-run.txt"), runId + "\n" + session.evidenceDigest());
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
        transmissions.set("approval", approval.deepCopy());
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
        state.set("businessDigests", f.json.valueToTree(M8BusinessSnapshot.capture(f.db.getDataSource(), f.tenant)));
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

    private void observeApplicationAdmissions() {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            admission(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3));
            return result;
        }).when(f.access).requireAdmission(any(), any(), any(), nullable(UUID.class));
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            admission(invocation.getArgument(0), ProductOperation.RESERVATION_HOLD, invocation.getArgument(1), invocation.getArgument(2));
            return result;
        }).when(f.access).requireHoldAdmission(any(), any(), any(), anyInt(), anyString());
    }

    private void admission(AuthenticatedPrincipal principal, ProductOperation operation, VenueId venue, UUID target) {
        if (principal.restriction() == null) return;
        var attempt = pendingAdmissions.get(principal.restriction().productCredentialId());
        if (attempt == null) return;
        boolean exact = principal.principalId().value().equals(activeActor.principal())
            && principal.restriction().tenantId().value().equals(f.tenant) && venue.equals(f.venue)
            && attempt.path("tool").asString().equals(operation.tool);
        if (operation == ProductOperation.RESERVATION_HOLD) {
            exact &= target != null && target.toString().equals(attempt.path("arguments").path("slotInventoryId").asString());
        } else if (operation == ProductOperation.RESERVATION_GET) {
            exact &= target != null && target.toString().equals(attempt.path("arguments").path("reservationId").asString());
        }
        attempt.put("authorized", exact);
        attempt.putObject("applicationAdmission").put("observed", true).put("principal", principal.principalId().value().toString())
            .put("tenant", f.tenant.toString()).put("venue", venue.value().toString()).put("operation", operation.name())
            .put("target", target == null ? "" : target.toString());
    }

    private void observeApproval(ApprovalReview review, UUID confirmation, RepresentativeFlowIntegrationTests.Identity actor) {
        int matches = f.db.queryForObject("SELECT COUNT(*) FROM mcp_hold_intents i JOIN mcp_hold_confirmations c ON c.intent_id=i.id "
            + "WHERE i.id=? AND c.id=? AND i.approver_id=? AND i.delegation_id=? AND i.tenant_id=? AND i.venue_id=? "
            + "AND i.slot_id=? AND i.party_size=? AND i.tool='reservation.hold' AND i.action='RESERVATION_WRITE' AND c.expires_at>?",
            Integer.class, bytes(review.intentId()), bytes(confirmation), bytes(actor.principal()), bytes(actor.delegation().delegationId()),
            bytes(f.tenant), bytes(f.venue.value()), bytes(f.slot), 2, java.sql.Timestamp.from(f.clock.instant()));
        approval.put("approved", matches == 1).put("exactActor", matches == 1)
            .put("intent", review.intentId().toString()).put("confirmation", confirmation.toString())
            .put("principal", actor.principal().toString()).put("delegation", actor.delegation().delegationId().toString());
    }
}
