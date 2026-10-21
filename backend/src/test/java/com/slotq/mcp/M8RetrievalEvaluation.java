package com.slotq.mcp;

import com.slotq.auth.access.*;
import com.slotq.evaluation.*;
import com.slotq.integration.mcp.knowledge.*;
import com.slotq.knowledge.application.*;
import com.slotq.knowledge.domain.Corpus.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.mcp.KnowledgeRetrievalIntegrationTests.bytes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Runs the existing M6 canonical oracle with the adopted lexical search, without embedding/model calls. */
final class M8RetrievalEvaluation {
    private final KnowledgeRetrievalIntegrationTests f;
    M8RetrievalEvaluation(KnowledgeRetrievalIntegrationTests fixture) { f = fixture; }
    @SuppressWarnings("unchecked")
    void run() throws Exception {
        var target = M8MutationTarget.disposable(f.MYSQL, f.ORIGIN);
        var seeds = new ArrayList<CorpusIngestion.Manifest>();
        for (String file : List.of("seed-manifest.json", "retrieval-extra-manifest.json", "retrieval-other-tenant-manifest.json")) {
            seeds.add(CorpusIngestion.readManifest(getClass().getResourceAsStream("/knowledge/" + file)));
        }
        var a = seeds.getFirst(); var b = seeds.getLast(); f.seed(a.expectedTenant(), a.venue()); f.seed(b.expectedTenant(), b.venue());
        String ownerA = f.owner(a.expectedTenant()), ownerB = f.owner(b.expectedTenant());
        seedProductBaseline();
        var oracle = f.json.readTree(getClass().getResourceAsStream("/knowledge/retrieval-oracle.json"));
        var ids = new ArrayList<String>(); for (var q : oracle.path("queries")) ids.add("m6/" + q.path("id").asString());
        ids.add("m8/retrieval-deleted"); ids.add("m8/holdout-retrieval-no-answer");
        var cells = M8Evaluation.cells(ids, Set.of("lexical"), Set.of("DETERMINISTIC"), 1);
        String runId = "retrieval-" + UUID.randomUUID();
        var grants = new HashMap<String, com.slotq.auth.persistence.ActorAccessService.ProvisionedCredential>();
        for (var cell : cells) {
            JsonNode item = M8Evaluation.item(cell.caseId()); boolean other = item.path("scope").path("tenant").asString().equals("tenant-b");
            boolean operator = item.path("scope").path("actor").asString().equals("owner-a");
            grants.put(cell.caseId(), f.delegate(other ? ownerB : ownerA, other ? b.venue() : a.venue(), operator ? AccessProfile.MANAGEMENT : AccessProfile.CUSTOMER,
                operator ? Set.of(AccessAction.KNOWLEDGE_PUBLIC, AccessAction.KNOWLEDGE_OPERATOR) : Set.of(AccessAction.KNOWLEDGE_PUBLIC)));
        }
        var manifest = target.manifest(f.clock.instant(), false, f.db);
        var session = new M8Evaluation(runId, cells, manifest, new M8Evaluation.Budget(cells.size(), 0, 0, Instant.now().plusSeconds(180), true),
            c -> {
                var actual = new HashMap<>(Map.of("tenant-a", a.expectedTenant().value().toString(), "tenant-b", b.expectedTenant().value().toString(),
                    "venue-a", a.venue().value().toString(), "venue-b", b.venue().value().toString()));
                actual.put(M8Evaluation.item(c.caseId()).path("scope").path("actor").asString(), f.observations.get(grants.get(c.caseId()).delegationId()).original().principalId().value().toString());
                return M8Bindings.create(c, runId, f.clock.instant(), actual);
            }, null);
        var finalReads = new ArrayList<PublishedVersion>();
        doAnswer(invocation -> {
            Optional<PublishedVersion> current = (Optional<PublishedVersion>) invocation.callRealMethod();
            current.ifPresent(finalReads::add);
            return current;
        }).when(f.catalog).revalidateExact(any(), any());
        var result = session.execute((cell, input, recorder) -> {
            for (var t : List.of(a.expectedTenant(), b.expectedTenant())) {
                f.db.update("UPDATE knowledge_documents SET state='WITHDRAWN',current_version_id=NULL WHERE tenant_id=?", bytes(t.value()));
                f.db.update("DELETE FROM knowledge_versions WHERE tenant_id=?", bytes(t.value())); f.db.update("DELETE FROM knowledge_documents WHERE tenant_id=?", bytes(t.value()));
            }
            f.ingestion.ingest(ownerA, a); f.ingestion.ingest(ownerA, seeds.get(1)); f.ingestion.ingest(ownerB, b);
            var query = oracle.path("queries").valueStream().filter(q -> cell.caseId().equals("m6/" + q.path("id").asString())).findFirst().orElse(null);
            String scenario = query == null ? "base" : query.path("scenario").asString();
            boolean other = scenario.equals("tenant-b"); f.tenant = other ? b.expectedTenant() : a.expectedTenant(); f.venue = other ? b.venue() : a.venue(); f.original = other ? ownerB : ownerA;
            f.delegated = grants.get(cell.caseId());
            var item = M8Evaluation.item(cell.caseId()); String text = item.path("input").asString();
            var initial = f.catalog.publications(f.delegated.delegationId(), f.venue); UUID hours = UUID.fromString("30000000-0000-0000-0000-000000000001");
            var lexical = new LexicalSearch(f.clock);
            if (scenario.equals("withdrawn")) { lexical.search(initial, text, f.clock.instant().plusSeconds(10)); f.authoring.withdraw(f.original, f.venue, hours, 2); }
            else if (scenario.equals("superseded")) f.canonicalUpdate(f.venue, f.original, hours);
            else if (scenario.equals("revoked")) f.access.revokeDelegation(f.delegated.delegationId());
            else if (scenario.equals("expired")) f.db.update("UPDATE auth_access_delegations SET issued_at=UTC_TIMESTAMP(6)-INTERVAL 2 SECOND, expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE id=?", bytes(f.delegated.delegationId()));
            else if (cell.caseId().equals("m8/retrieval-deleted")) {
                // Retain metadata of the old candidate separately; cleanup removes payload before the actual search.
                f.authoring.withdraw(f.original, f.venue, hours, 2); f.authoring.cleanup(f.original, f.venue, hours);
            }
            var entered = f.json.createArrayNode();
            CandidateSearch search = (eligible, q, deadline) -> {
                eligible.forEach(v -> entered.add(reference(v)));
                if (scenario.equals("unavailable")) throw new RetrievalFailure(false);
                if (scenario.equals("timeout")) return lexical.search(eligible, q, f.clock.instant().minusMillis(1));
                var hits = lexical.search(eligible, q, deadline);
                if (scenario.equals("withdraw-during")) f.authoring.withdraw(f.original, f.venue, hours, 2);
                else if (scenario.equals("update-during")) f.canonicalUpdate(f.venue, f.original, hours);
                return hits;
            };
            finalReads.clear();
            var observed = f.search(search, text); var content = f.json.valueToTree(observed.content());
            var candidates = f.json.createObjectNode(); candidates.set("category", content.path("category")); var results = candidates.putArray("results");
            var atFinal = f.json.createArrayNode(); finalReads.forEach(v -> atFinal.add(reference(v)));
            for (var hit : content.path("results")) {
                var ref = atFinal.valueStream().filter(v -> v.path("document").equals(hit.path("documentId")) && v.path("versionId").equals(hit.path("versionId"))).findFirst().orElse(f.json.createObjectNode());
                var actual = (ObjectNode) ref.deepCopy();
                actual.set("document", hit.path("documentId"));
                for (String key : List.of("versionId", "sourceId", "sourceReference", "sourceTitle", "visibility")) actual.set(key, hit.path(key));
                results.add(actual);
            }
            var metadata = f.json.createObjectNode(); metadata.set("beforeCandidates", entered); metadata.set("modelInputs", entered.deepCopy()); metadata.set("atFinal", atFinal);
            metadata.set("residualBeforeDeletion", f.json.valueToTree(initial.stream().map(this::reference).toList()));
            recorder.put("request", input); recorder.put("retrieval-candidates", candidates); recorder.put("publication-metadata", metadata);
            if (cell.caseId().equals("m8/retrieval-deleted")) {
                recorder.put("server-plan", Map.of("knowledge.search", Map.of("query", text)));
                recorder.put("tool-arguments", Map.of("knowledge.search", Map.of("query", text)));
            }
            var raw = f.json.createObjectNode(); raw.put("observationKind", "PRODUCTION_KNOWLEDGE_LEXICAL").put("providerCalls", 0).put("tokens", 0); raw.set("response", content); recorder.put("result", raw);
            var disclosure = f.json.createObjectNode(); disclosure.put("observed", true); disclosure.set("references", results.deepCopy()); disclosure.set("content", content);
            disclosure.putArray("forbiddenValues").add("Other tenant secret-SENTINEL"); recorder.put("disclosure", disclosure);
        });
        assertThat(result.path("rows")).hasSize(20);
        for (var row : result.path("rows")) {
            // The adopted lexical path's known semantic miss is quality evidence, not an evaluator regression.
            String expected = row.path("cell").path("caseId").asString().equals("m6/semantic-hours") ? "FAIL" : "PASS";
            assertThat(row.path("automaticStatus").asString()).as(row.path("cell").toString() + row.path("reasons")).isEqualTo(expected);
        }
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest())).isEqualTo(result);
        java.nio.file.Files.writeString(java.nio.file.Path.of("../build/m8-evidence/latest-retrieval-run.txt"), runId + "\n" + session.evidenceDigest());
    }
    private ObjectNode reference(PublishedVersion version) {
        var metadata = version.metadata();
        var ref = metadata.reference();
        var value = f.json.createObjectNode(); value.put("document", ref.document().documentId().toString()).put("versionId", ref.versionId().toString())
            .put("sourceId", metadata.source().sourceId().toString()).put("tenant", ref.document().tenantId().value().toString()).put("venue", ref.document().venueId().value().toString())
            .put("visibility", ref.visibility().name()).put("publication", "PUBLISHED")
            .put("sourceReference", metadata.source().reference()).put("sourceTitle", metadata.source().title())
            .put("content", version.content()).put("contentDigest", ref.contentDigest()); return value;
    }

    private void seedProductBaseline() {
        var tenants = f.application.getBean(com.slotq.tenancy.application.TenantUseCase.class);
        var venues = f.application.getBean(com.slotq.venue.application.VenueConfigurationUseCase.class);
        var resources = f.application.getBean(com.slotq.venue.application.ResourceUseCase.class);
        var slots = f.application.getBean(com.slotq.booking.application.SlotInventoryUseCase.class);
        var reservations = f.application.getBean(com.slotq.booking.application.ReservationUseCase.class);
        var tenant = tenants.createTenant().id();
        var day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2);
        var venue = venues.createVenue(new com.slotq.venue.application.VenueConfigurationUseCase.CreateVenue(tenant, "Synthetic M8 baseline", "UTC",
            new com.slotq.venue.domain.WeeklyOperatingHours(Map.of(day.getDayOfWeek(), new com.slotq.venue.domain.DailyOperatingHours(java.time.LocalTime.of(9, 0), java.time.LocalTime.of(22, 0)))),
            new com.slotq.venue.domain.BookingPolicyTerms(30, 5, 20, 10))).id();
        var resource = resources.createResource(new com.slotq.venue.application.ResourceUseCase.CreateResource(tenant, venue, "Synthetic table", 4));
        var slot = slots.createSlot(new com.slotq.booking.application.SlotInventoryUseCase.CreateSlot(tenant, venue, resource.id(), day + "T12:00:00Z"));
        UUID customer = UUID.randomUUID(); f.db.update("INSERT INTO auth_principals(id) VALUES(?)", bytes(customer));
        reservations.createHold(new com.slotq.booking.application.ReservationUseCase.CreateHold(venue, slot.id(), new com.slotq.auth.domain.AuthenticatedPrincipal(new com.slotq.auth.domain.PrincipalId(customer)), 2));
    }
}
