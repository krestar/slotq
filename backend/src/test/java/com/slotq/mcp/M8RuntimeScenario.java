package com.slotq.mcp;

import com.slotq.ai.flow.RepresentativeFlows;
import com.slotq.ai.runtime.AgentRuntime;
import com.slotq.ai.runtime.RuntimeContract.*;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.evaluation.*;
import com.slotq.knowledge.domain.Corpus;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.application.*;
import com.slotq.venue.domain.VenueId;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.mcp.RepresentativeFlowIntegrationTests.bytes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Test-only setup for the existing v1 scenarios. Server authority and model context stay in production code. */
final class M8RuntimeScenario {
    final String id;
    private final RepresentativeFlowIntegrationTests f;
    private final Map<String, String> symbols = new HashMap<>();
    private RepresentativeFlowIntegrationTests.Identity other, staff;
    private UUID otherTenant, foreignReservation, reportedTarget;
    private VenueId otherVenue;
    private Corpus.PublishedVersion deleted;
    private final List<Corpus.PublishedVersion> candidatesObserved = new ArrayList<>(), finalObserved = new ArrayList<>();

    M8RuntimeScenario(RepresentativeFlowIntegrationTests f, String id) throws Exception {
        this.f = f; this.id = id;
        if (Set.of("m8/cross-tenant", "m8/cross-venue").contains(id)) {
            otherTenant = id.equals("m8/cross-tenant") ? f.tenants.createTenant().id().value() : f.tenant;
            otherVenue = f.createVenue(otherTenant);
            other = f.identity(id.equals("m8/cross-tenant") ? "OWNER" : "MANAGER", otherTenant, otherVenue);
            var customer = f.identity("CUSTOMER", otherTenant, otherVenue);
            var resource = f.resources.createResource(new ResourceUseCase.CreateResource(new TenantId(otherTenant), otherVenue, "Scope sentinel", 4));
            var slot = f.slots.createSlot(new com.slotq.booking.application.SlotInventoryUseCase.CreateSlot(
                new TenantId(otherTenant), otherVenue, resource.id(), f.day + "T12:00:00Z"));
            foreignReservation = f.reservations.createHold(new ReservationUseCase.CreateHold(otherVenue, slot.id(),
                new AuthenticatedPrincipal(new PrincipalId(customer.principal())), 2)).reservation().id().value();
            f.publishAt(other, otherVenue, Corpus.Visibility.VENUE_PUBLIC, "synthetic-" + id.substring(3));
            symbols.put(id.equals("m8/cross-tenant") ? "reservation-other-tenant" : "reservation-other-venue", foreignReservation.toString());
            symbols.put(id.equals("m8/cross-tenant") ? "venue-b" : "venue-a2", otherVenue.value().toString());
            if (!otherTenant.equals(f.tenant)) symbols.put("tenant-b", otherTenant.toString());
        } else if (id.equals("m8/cross-customer")) {
            other = f.identity("CUSTOMER", f.tenant, f.venue);
            foreignReservation = f.reservations.createHold(new ReservationUseCase.CreateHold(f.venue, new SlotInventoryId(f.slot),
                new AuthenticatedPrincipal(new PrincipalId(other.principal())), 2)).reservation().id().value();
            symbols.put("customer-a2", other.principal().toString()); symbols.put("reservation-a2", foreignReservation.toString());
        } else if (id.equals("m8/staff-ops")) {
            staff = f.identity("STAFF", f.tenant, f.venue); symbols.put("staff-a", staff.principal().toString());
            symbols.put("reservation-a", f.db.queryForObject("SELECT BIN_TO_UUID(id) FROM reservations WHERE tenant_id=? LIMIT 1", String.class, bytes(f.tenant)).toLowerCase(Locale.ROOT));
        } else if (id.equals("m8/unknown-get-404")) {
            reportedTarget = UUID.randomUUID(); symbols.put("reservation-a", reportedTarget.toString());
        } else if (id.equals("m8/retrieval-deleted")) {
            var fixture = M8Evaluation.item(id).path("fixture");
            var version = f.corpus.stage(f.owner.original().value(), f.venue, new Corpus.VersionInput(
                UUID.fromString(fixture.path("document").asString()), UUID.fromString(fixture.path("staleVersion").asString()),
                new Corpus.Source(UUID.randomUUID(), "seed:m8-deleted", "Synthetic deleted guide"), Corpus.Visibility.VENUE_PUBLIC,
                "삭제된 문서의 잔여 색인 검색 synthetic-private-sentinel", Corpus.digest("삭제된 문서의 잔여 색인 검색 synthetic-private-sentinel")), 0);
            f.corpus.validate(f.owner.original().value(), version.reference()); f.corpus.publish(f.owner.original().value(), version.reference());
            deleted = f.catalog.publications(f.customer.delegation().delegationId(), f.venue).stream()
                .filter(p -> p.metadata().reference().equals(version.reference())).findFirst().orElseThrow();
        }
    }

    void bind(Map<String, String> binding, UUID slot) {
        binding.putAll(symbols);
        if (id.equals("m8/holdout-unknown-commit")) {
            binding.remove("slot-demo-A"); binding.put("slot-demo-B", slot.toString());
        }
    }

    RepresentativeFlowIntegrationTests.Identity actor() {
        if (id.equals("m8/staff-ops")) return staff;
        if (id.equals("m7-flow/management-owner") || id.equals("m8/cross-tenant")) return f.owner;
        if (Set.of("m7-flow/ops-manager", "m8/cross-venue", "m8/fixed-plan-date", "m8/fixed-plan-window").contains(id)) return f.manager;
        return f.customer;
    }

    boolean boundary() { return Set.of("m8/cross-tenant", "m8/cross-venue", "m8/cross-customer", "m8/staff-ops").contains(id); }
    boolean mutates() { return Set.of("m8/customer-business-rejected", "m8/unknown-committed", "m8/customer-held", "m8/unknown-no-target", "m8/unknown-get-404", "m8/holdout-unknown-commit").contains(id); }
    boolean reconciles() { return Set.of("m8/unknown-committed", "m8/unknown-get-404", "m8/holdout-unknown-commit").contains(id); }

    RepresentativeFlows.Flow flow(AgentRuntime runtime, RepresentativeFlows flows, RepresentativeFlowIntegrationTests.Identity actor, Limits limits) {
        var plan = M8Evaluation.item(id).path("serverPlan");
        if (id.equals("m7-flow/management-owner") || id.equals("m8/fixed-plan-date")) return flows.management(actor.delegation().value(),
            LocalDate.parse(plan.path("management.reservations.list").path("date").asString()), plan.path("knowledge.search").path("query").asString());
        if (id.equals("m7-flow/ops-manager") || id.equals("m8/fixed-plan-window")) return flows.ops(actor.delegation().value(),
            Instant.parse(plan.path("management.event-observations.list").path("from").asString()), Instant.parse(plan.path("management.event-observations.list").path("to").asString()));
        if (id.equals("m8/retrieval-deleted")) {
            var actual = new Plan("m7-flow-v1", "customer", limits, null,
                Map.of("knowledge.search", Map.of("query", M8Evaluation.item(id).path("input").asString(), "limit", 1)));
            return new RepresentativeFlows.Flow(runtime.start(actor.delegation().value(), actual), actual);
        }
        return flows.customerHold(actor.delegation().value(), f.slot, id.equals("m8/holdout-unknown-commit") ? 4 : 2, "guide");
    }

    void beforeReads(AgentRuntime runtime, RepresentativeFlows.Flow flow, M8Evaluation.Recorder recorder) throws Exception {
        if (deleted == null) return;
        runtime.readPlanned(flow.id(), actor().delegation().value(), "knowledge.search");
        recorder.put("deleted-publication", reference(deleted));
        var withdrawn = f.corpus.withdraw(f.owner.original().value(), f.venue, deleted.metadata().reference().document().documentId(), deleted.documentRevision());
        assertThat(withdrawn.state()).isEqualTo(Corpus.DocumentState.WITHDRAWN);
        assertThat(f.corpus.cleanup(f.owner.original().value(), f.venue, deleted.metadata().reference().document().documentId()).localPayloadsRemoved()).isEqualTo(1);
        doAnswer(call -> {
            @SuppressWarnings("unchecked") var observed = (List<Corpus.PublishedVersion>) call.callRealMethod();
            candidatesObserved.clear(); candidatesObserved.addAll(observed); return observed;
        }).when(f.catalog).publications(any(), any());
        doAnswer(call -> {
            @SuppressWarnings("unchecked") var observed = (Optional<Corpus.PublishedVersion>) call.callRealMethod();
            observed.ifPresent(finalObserved::add); return observed;
        }).when(f.catalog).revalidateExact(any(), any());
    }

    void armFault() {
        if (id.equals("m8/unknown-no-target")) f.fault.arm(f.venue, false, false);
        if (id.equals("m8/unknown-get-404")) f.fault.armUnknownLocation(f.venue, reportedTarget);
        if (id.equals("m8/holdout-unknown-commit")) f.fault.arm(f.venue, true, false);
    }

    void observations(Result result, M8Evaluation.Recorder recorder) throws Exception {
        if (id.equals("m8/unknown-get-404")) {
            var problem = (Map<?,?>) result.reconciliationResult().get("problem");
            assertThat(problem.get("status")).isEqualTo(404);
            recorder.put("fault-injection", Map.of("kind", "POST_COMMIT_RESPONSE_LOSS_WITH_UNKNOWN_LOCATION",
                "reportedTarget", result.knownTarget().toString(), "exactGetObservation", result.reconciliationResult()));
        }
        if (deleted == null) return;
        var last = result.tools().stream().filter(t -> t.tool().equals("knowledge.search")).reduce((a,b) -> b).orElseThrow();
        assertThat(last.structured().get("category")).isEqualTo("no_answer");
        assertThat(candidatesObserved).noneMatch(p -> p.metadata().reference().equals(deleted.metadata().reference()));
        var candidates = f.json.createObjectNode().put("category", String.valueOf(last.structured().get("category"))); candidates.putArray("results");
        recorder.put("retrieval-candidates", candidates);
        var metadata = f.json.createObjectNode();
        metadata.set("beforeCandidates", f.json.valueToTree(candidatesObserved.stream().map(this::reference).toList()));
        metadata.putArray("modelInputs"); metadata.set("atFinal", f.json.valueToTree(finalObserved.stream().map(this::reference).toList()));
        metadata.putArray("residualBeforeDeletion").add(reference(deleted)); recorder.put("publication-metadata", metadata);
    }

    record Boundary(Plan plan, Result result) { }
    Boundary probe(AgentRuntime runtime, RepresentativeFlowIntegrationTests.Identity actor, M8Evaluation.Recorder recorder, Limits limits) throws Exception {
        var flows = new RepresentativeFlows(runtime, limits);
        if (id.equals("m8/staff-ops")) {
            var permitted = new Plan("m7-flow-v1", "management", limits, null,
                Map.of("management.reservations.list", Map.of("date", "2026-10-20")));
            f.lifecycle(actor, permitted); // Staff's permitted M6 read remains real and separate from the denied M7 Run.
            assertThatThrownBy(() -> flows.ops(actor.delegation().value(), Instant.parse("2026-10-20T00:00:00Z"), Instant.parse("2026-10-21T00:00:00Z"))).hasMessage("AUTHORITY");
            recorder.put("boundary-attempt", Map.of("workload", "ops", "admission", "AUTHORITY", "permittedM6Plan", permitted));
            return new Boundary(null, null);
        }
        if (id.equals("m8/cross-customer")) {
            var flow = flows.customerRead(actor.delegation().value(), foreignReservation, null);
            var result = flows.prepareReads(flow, actor.delegation().value());
            assertThat(result.tools().getLast().structured().get("outcome")).isEqualTo("rejected");
            return new Boundary(flow.plan(), result);
        }
        var flow = flows.management(actor.delegation().value(), LocalDate.parse("2026-10-20"), null);
        flows.prepareReads(flow, actor.delegation().value());
        assertThatThrownBy(() -> runtime.result(flow.id(), other.delegation().value())).hasMessage("AUTHORITY");
        var attempted = Map.<String,Object>of("reservationId", foreignReservation.toString());
        var denied = runtime.read(flow.id(), actor.delegation().value(), "reservation.get", attempted);
        assertThat(denied.tools().getLast().invocation()).isEqualTo(Invocation.NOT_DISPATCHED);
        recorder.put("boundary-attempt", Map.of("tool", "reservation.get", "arguments", attempted,
            "runActorRejected", true, "toolInvocation", denied.tools().getLast().invocation().name()));
        return new Boundary(flow.plan(), denied);
    }

    void otherDatabase(ObjectNode state) {
        if (otherTenant == null || otherTenant.equals(f.tenant)) return;
        var values = (ObjectNode) state.path("businessDigests");
        M8BusinessSnapshot.capture(f.db.getDataSource(), otherTenant).forEach((table, digest) -> values.put("other-tenant:" + table, digest));
    }

    private ObjectNode reference(Corpus.PublishedVersion version) {
        var ref = version.metadata().reference();
        return f.json.createObjectNode().put("document", ref.document().documentId().toString()).put("versionId", ref.versionId().toString())
            .put("sourceId", version.metadata().source().sourceId().toString()).put("tenant", ref.document().tenantId().value().toString())
            .put("venue", ref.document().venueId().value().toString()).put("visibility", ref.visibility().name()).put("publication", "PUBLISHED")
            .put("sourceReference", version.metadata().source().reference()).put("sourceTitle", version.metadata().source().title())
            .put("content", version.content()).put("contentDigest", ref.contentDigest());
    }
}
