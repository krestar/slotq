package com.slotq.evaluation;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;
import static org.assertj.core.api.Assertions.*;

class M8EvaluationTests {
    static final Instant BASE = Instant.parse("2026-10-20T15:30:00Z");
    static M8Evaluation.Cell cell(String id) { return new M8Evaluation.Cell(id, "1.0.0", 1, "scorer-test-vector", "DETERMINISTIC"); }

    @Test void deterministicDatasetReportAndOfflineRecalculationPreserveAllSixtyCells() throws Exception {
        var ids = array(read("dataset.json"), "cases").stream().map(i -> text(i, "id")).toList();
        var cells = M8Evaluation.cells(ids, Set.of("scorer-test-vector"), Set.of("DETERMINISTIC"), 1);
        var session = session(cells, 60);
        var result = session.execute((cell, input, recorder) -> {
            assertThat(input.toString()).doesNotContain("\"oracle\":", "\"approval\":", "\"fault\":", "\"db\":", "\"forbidden\":", "\"expected\":", "\"attemptedTarget\":");
            vector(M8Evaluation.item(cell.caseId()), recorder.binding()).forEach((id, value) -> put(recorder, id, value));
        });
        assertThat(result.path("plannedCells").asInt()).isEqualTo(60);
        assertThat(result.path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(result.path("releaseQualification").asBoolean()).isFalse();
        assertThat(result.path("rows")).allSatisfy(row -> assertThat(row.path("status").asString()).isEqualTo("INCOMPLETE"));
        assertThat(result.path("rows").valueStream().filter(r -> r.path("automaticStatus").asString().equals("FAIL")).toList()).isEmpty();
        assertThat(result.path("rows").valueStream().filter(r -> r.path("cell").path("caseId").asString().equals("m7-router/customer-normal"))
            .findFirst().orElseThrow().path("axisResults").path("execution_success").path("status").asString()).isEqualTo("NOT_APPLICABLE");
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest())).isEqualTo(result);
        assertThat(Files.readString(session.directory().resolve("summary.ko.md"))).contains("계획 cell: 60", "PENDING");
        Files.writeString(Path.of("../build/m8-evidence/latest-scorer-run.txt"), session.directory().getFileName().toString() + "\n" + session.evidenceDigest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"tool", "target", "party", "disposition", "source", "claim", "claim-source", "confirmed", "dispatch", "disclosure", "db", "partial", "duplicate", "capacity", "approval", "initial-unknown", "get-target", "get-state", "not-sent", "effect"})
    void detectsIncorrectModelAndIndependentExecutionObservations(String defect) {
        String id = Set.of("initial-unknown", "get-target", "get-state").contains(defect) ? "m8/unknown-committed"
            : Set.of("dispatch", "disclosure", "db", "not-sent").contains(defect) ? "m8/customer-not-sent"
            : Set.of("source", "claim", "claim-source").contains(defect) ? "m8/attribution-two-sources"
            : Set.of("partial", "duplicate", "capacity", "approval", "effect", "confirmed").contains(defect) ? "m8/customer-held" : "m7-router/customer-normal";
        var item = M8Evaluation.item(id); var binding = M8Bindings.create(cell(id), "test", BASE, Map.of());
        var raw = vector(item, binding); var output = (ObjectNode) raw.get("result").path("proposal");
        var execution = (ObjectNode) raw.get("result").path("execution");
        switch (defect) {
            case "tool" -> output.put("tool", "recovery.replay");
            case "target" -> output.put("target", UUID.randomUUID().toString());
            case "party" -> output.put("partySize", 3);
            case "disposition" -> output.put("disposition", "REJECT");
            case "source" -> output.putArray("sourceRefs");
            case "claim" -> output.putArray("claimRefs");
            case "claim-source" -> output.putArray("sourceRefs").add("hours-v2");
            case "confirmed" -> output.put("outcome", "CONFIRMED");
            case "dispatch", "not-sent" -> ((ObjectNode) raw.get("tool-transmissions")).withArray("attempts").addObject()
                .put("tool", "reservation.hold").put("sent", true).put("authorized", false).put("responseObserved", false);
            case "disclosure" -> ((ObjectNode) raw.get("disclosure")).put("content", "synthetic-private-sentinel");
            case "db" -> ((ObjectNode) raw.get("independent-db").path("after")).put("reservations", 1);
            case "partial" -> ((ObjectNode) raw.get("independent-db").path("after")).put("allocations", 0);
            case "duplicate" -> ((ObjectNode) raw.get("independent-db").path("after")).put("reservations", 2);
            case "capacity" -> ((ObjectNode) raw.get("independent-db").path("after")).put("activeUnits", 100);
            case "approval" -> ((ObjectNode) raw.get("tool-transmissions").path("approval")).put("exactActor", false);
            case "initial-unknown" -> execution.put("effect", "SUCCEEDED");
            case "get-target" -> ((ObjectNode) raw.get("tool-transmissions").path("exactGet")).put("target", UUID.randomUUID().toString());
            case "get-state" -> ((ObjectNode) raw.get("tool-transmissions").path("exactGet")).put("state", "CONFIRMED");
            case "effect" -> execution.put("effect", "REJECTED");
            default -> throw new AssertionError(defect);
        }
        var score = M8Scorer.score(item, raw);
        assertThat(score.status()).isEqualTo("FAIL");
        if (Set.of("dispatch", "disclosure", "db", "partial", "duplicate", "capacity", "approval").contains(defect)) assertThat(score.hardGates()).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"m8/customer-held", "m8/customer-business-rejected", "m8/customer-not-sent", "m8/unknown-committed", "m8/unknown-no-target", "m8/unknown-get-404"})
    void distinguishesExpectedBusinessOutcomesAndUnknownFromCurrentGet(String id) {
        var item = M8Evaluation.item(id); var raw = vector(item, M8Bindings.create(cell(id), "test", BASE, Map.of()));
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("execution_invocation").status()).isEqualTo("PASS");
        assertThat(score.checks().get("execution_effect").status()).isEqualTo("PASS");
        assertThat(score.checks().get("execution_reconciliation").status()).isEqualTo("PASS");
        if (id.contains("unknown")) assertThat(raw.get("result").path("execution").path("effect").asString()).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "source", "tenant", "venue", "visibility", "publication"})
    void gradesRetrievalReferencesAndPublicationScopeIndependently(String defect) {
        var item = M8Evaluation.item("m6/tenant-a-hours");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        var reference = (ObjectNode) raw.get("retrieval-candidates").path("results").get(0);
        switch (defect) {
            case "version" -> reference.put("versionId", UUID.randomUUID().toString());
            case "source" -> reference.put("sourceId", UUID.randomUUID().toString());
            case "tenant", "venue" -> reference.put(defect, UUID.randomUUID().toString());
            case "visibility" -> reference.put("visibility", "VENUE_OPERATOR");
            case "publication" -> reference.put("publication", "WITHDRAWN");
        }
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("retrieval_category").status()).isEqualTo("PASS");
        assertThat(score.status()).isEqualTo("FAIL");
    }

    @Test void serverPlanPreservationIsNotModelParameterAccuracy() {
        var item = M8Evaluation.item("m8/fixed-plan-date");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        ((ObjectNode) raw.get("tool-arguments").path("management.reservations.list")).put("date", "2026-10-21");
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("server_plan_preservation").status()).isEqualTo("FAIL");
        assertThat(score.checks()).doesNotContainKey("parameter_extraction");
    }

    @Test void missingInterruptedAndDuplicatePlannedCellsCannotShrinkDenominator() throws Exception {
        var cells = M8Evaluation.cells(List.of("m7-router/customer-normal"), Set.of("scorer-test-vector"), Set.of("DETERMINISTIC"), 3);
        var session = session(cells, 1);
        var result = session.execute((c, input, recorder) -> { throw new IOExceptionForTest(); });
        assertThat(result.path("plannedCells").asInt()).isEqualTo(3);
        assertThat(result.path("rows")).hasSize(3);
        assertThat(result.path("cohorts").valueStream().filter(g -> g.path("dimensions").path("metric").asString().equals("axis.tool_selection"))
            .mapToInt(g -> g.path("denominator").asInt()).sum()).isEqualTo(3);
        assertThat(result.path("rows")).allSatisfy(r -> assertThat(r.path("automaticStatus").asString()).isEqualTo("INCOMPLETE"));
        assertThatThrownBy(() -> session(List.of(cells.getFirst(), cells.getFirst()), 2)).hasMessageContaining("duplicate planned cell");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes", "missing", "binding", "new-record", "review-missing", "review-digest", "review-rejected"})
    void protectsRawBytesBindingRecordIdentityAndHumanReview(String defect) throws Exception {
        var cells = List.of(cell("m7-router/customer-normal")); var session = session(cells, 1);
        var result = session.execute((c, input, recorder) -> {
            var raw = vector(M8Evaluation.item(c.caseId()), recorder.binding());
            raw.forEach((id, value) -> put(recorder, id, value));
            if (defect.equals("binding")) {
                // Incorrect request target despite a syntactically valid UUID binding.
                ((ObjectNode) raw.get("result").path("proposal")).put("target", UUID.randomUUID().toString());
            }
            if (defect.startsWith("review-") && !defect.equals("review-missing")) {
                var review = JSON.createObjectNode(); review.put("runId", session.directory().getFileName().toString());
                review.set("cell", JSON.valueToTree(c)); review.put("rubricVersion", "m8-answer-v1");
                review.put("requestDigest", "0".repeat(64)).put("answerDigest", "0".repeat(64)).put("reviewer", "test-reviewer")
                    .put("reviewedAt", Instant.now().toString()).put("state", defect.equals("review-rejected") ? "REJECTED" : "APPROVED");
                var findings = review.putObject("findings"); for (String name : strings(read("review-rubric.json"), "required")) findings.put(name, false);
                recorder.put("review", review);
            }
        });
        Path raw = session.directory().resolve("cells/0/result.json");
        switch (defect) {
            case "bytes" -> Files.writeString(raw, Files.readString(raw) + " ");
            case "missing" -> Files.delete(raw);
            case "new-record" -> Files.writeString(session.directory().resolve("cells/0/extra.json"), "{}");
            case "binding" -> {
                var binding = session.directory().resolve("bindings/0.json");
                Files.writeString(binding, Files.readString(binding).replace("fixtureGroup", "wrongGroup"));
            }
        }
        var recalculated = M8Evaluation.calculate(session.directory(), session.evidenceDigest());
        if (Set.of("bytes", "binding", "new-record", "review-rejected").contains(defect)) assertThat(recalculated.path("status").asString()).isEqualTo("FAIL");
        else assertThat(recalculated.path("status").asString()).isEqualTo("INCOMPLETE");
        if (defect.equals("review-digest")) assertThat(result.path("rows").get(0).path("checks").path("human_review").path("reason").asString()).contains("digest");
    }

    @Test void rejectsMutationBeforeExecutorOrEvidenceCreationWithoutDisposableTarget() {
        var manifest = manifest(); ((ObjectNode) manifest.path("features")).put("productWrite", true);
        assertThatThrownBy(() -> new M8Evaluation("unsafe-" + UUID.randomUUID(), List.of(cell("m8/customer-held")), manifest,
            budget(1), c -> M8Bindings.create(c, "unsafe", BASE, Map.of()), null)).hasMessageContaining("verified disposable MySQL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "repeat", "model", "lane", "group", "time", "alias"})
    void rejectsInvalidBindingsBeforeAnyExecution(String defect) {
        var cell = cell("m7-router/customer-normal"); var binding = M8Bindings.create(cell, "binding-test", BASE, Map.of());
        switch (defect) {
            case "run" -> binding.put("runId", "other-run");
            case "repeat" -> binding.put("repeat", 2);
            case "model" -> binding.put("model", "other-model");
            case "lane" -> binding.put("lane", "ACTUAL_PROVIDER_FIXTURE");
            case "group" -> binding.put("fixtureGroup", "other-case");
            case "time" -> ((ObjectNode) binding.path("times")).put("venueDate", "2026-10-20");
            case "alias" -> ((ObjectNode) binding.path("symbols")).set("venue-a", binding.path("symbols").path("venue-b"));
        }
        assertThatThrownBy(() -> M8Bindings.validate(binding, new M8Bindings.CellIdentity("binding-test", cell), M8Evaluation.item(cell.caseId())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void linkedProposalAndAnswerCannotRebindTheirFixtureGroup() {
        var cells = M8Evaluation.cells(List.of("m7-flow/customer-hold-proposal", "m7-flow/customer-hold-answer"), Set.of("scorer-test-vector"), Set.of("DETERMINISTIC"), 1);
        String id = "rebound-" + UUID.randomUUID();
        assertThatThrownBy(() -> new M8Evaluation(id, cells, manifest(), budget(2), c -> M8Bindings.create(c, id, BASE, Map.of()), null))
            .hasMessageContaining("shared fixture group rebound");
    }

    @Test void noObservedDatabaseSnapshotCannotBeReportedAsRollbackOrDefenseSuccess() {
        var item = M8Evaluation.item("m8/customer-not-sent"); var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        raw.put("independent-db", JSON.readTree("{\"observed\":true,\"before\":{},\"after\":{}}"));
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("execution_db").status()).isEqualTo("INCOMPLETE");
        assertThat(score.checks().get("forbidden_db").status()).isEqualTo("INCOMPLETE");
        assertThat(score.hardGates()).isEmpty();
    }

    @Test void answerFailureDoesNotTurnObservedProductCommitIntoExecutionFailure() {
        var item = M8Evaluation.item("m8/customer-held");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        ((ObjectNode) raw.get("result")).putNull("proposal").put("failure", "MALFORMED"); raw.remove("answer");
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("tool_selection").status()).isEqualTo("FAIL");
        assertThat(score.checks().get("execution_effect").status()).isEqualTo("PASS");
        assertThat(score.checks().get("execution_db").status()).isEqualTo("PASS");
    }

    @Test void generatedReservationBindingIsAssignedOnceAndEarlierStageRemainsUnbound() throws Exception {
        String id = "binding-completion-" + UUID.randomUUID();
        var c = cell("m8/unknown-committed"); var binding = M8Bindings.create(c, id, BASE, Map.of());
        ((ObjectNode) binding.path("symbols")).put("reservation-a", "UNBOUND");
        UUID created = UUID.randomUUID();
        var session = new M8Evaluation(id, List.of(c), manifest(), budget(1), cell -> binding, null);
        var result = session.execute((cell, input, recorder) -> {
            assertThat(recorder.binding().path("symbols").path("reservation-a").asString()).isEqualTo("UNBOUND");
            recorder.bindCreatedReservation("reservation-a", created);
            assertThatThrownBy(() -> recorder.bindCreatedReservation("reservation-a", UUID.randomUUID())).hasMessageContaining("already assigned");
            vector(M8Evaluation.item(cell.caseId()), recorder.binding()).forEach((key, value) -> put(recorder, key, value));
        });
        assertThat(JSON.readTree(Files.readAllBytes(session.directory().resolve("bindings/0.json"))).path("symbols").path("reservation-a").asString()).isEqualTo("UNBOUND");
        assertThat(result.path("rows").get(0).path("automaticStatus").asString()).isEqualTo("PASS");
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest())).isEqualTo(result);
    }

    @Test void fullAttributionRequiredEvenWhenRetrievalIsCorrect() {
        var item = M8Evaluation.item("m8/attribution-two-sources"); var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        ((ObjectNode) raw.get("result").path("proposal")).putArray("sourceRefs").add("hours-v2");
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("answer_claim_sources").status()).isEqualTo("FAIL");
    }

    @Test void approvedRejectedMismatchedAndDuplicateReviewsRecalculateWithoutExecutingProvider() throws Exception {
        var c = cell("m7-router/customer-normal"); var session = session(List.of(c), 1);
        var original = session.execute((cell, input, recorder) -> vector(M8Evaluation.item(cell.caseId()), recorder.binding()).forEach((id, value) -> put(recorder, id, value)));
        var row = original.path("rows").get(0); var bundle = JSON.createObjectNode();
        bundle.put("version", "m8-answer-v1").put("runId", session.directory().getFileName().toString());
        var review = bundle.putArray("reviews").addObject(); review.put("runId", bundle.path("runId").asString()).set("cell", JSON.valueToTree(c));
        review.put("state", "APPROVED").put("rubricVersion", "m8-answer-v1").put("reviewer", "synthetic-review-validator-test")
            .put("reviewedAt", Instant.now().toString()).set("requestDigest", row.path("evidence").path("request").path("digest"));
        review.set("answerDigest", row.path("evidence").path("answer").path("digest"));
        for (String name : strings(read("review-rubric.json"), "required")) review.withObject("findings").put(name, true);
        byte[] bytes = JSON.writeValueAsBytes(bundle);
        var approved = M8Evaluation.calculate(session.directory(), session.evidenceDigest(), bytes, digest(bytes));
        assertThat(approved.path("rows").get(0).path("checks").path("human_review").path("status").asString()).isEqualTo("PASS");
        assertThat(approved.path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest(), bytes, digest(bytes))).isEqualTo(approved);
        assertThat(Files.readString(session.directory().resolve("results.json"))).contains("PENDING");
        review.put("state", "REJECTED"); bytes = JSON.writeValueAsBytes(bundle);
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest(), bytes, digest(bytes)).path("status").asString()).isEqualTo("FAIL");
        review.put("state", "APPROVED").put("answerDigest", "0".repeat(64)); bytes = JSON.writeValueAsBytes(bundle);
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest(), bytes, digest(bytes)).path("rows").get(0).path("checks").path("human_review").path("status").asString()).isEqualTo("INCOMPLETE");
        bundle.withArray("reviews").add(review.deepCopy()); byte[] duplicate = JSON.writeValueAsBytes(bundle);
        assertThatThrownBy(() -> M8Evaluation.calculate(session.directory(), session.evidenceDigest(), duplicate, digest(duplicate))).hasMessageContaining("duplicate/unknown review");
        assertThatThrownBy(() -> M8Evaluation.calculate(session.directory(), session.evidenceDigest(), duplicate, "0".repeat(64))).hasMessageContaining("review bundle bytes/digest");
    }

    static M8Evaluation session(List<M8Evaluation.Cell> cells, int maximum) throws Exception {
        String runId = "scorer-" + UUID.randomUUID(); var groups = new HashMap<String, JsonNode>();
        return new M8Evaluation(runId, cells, manifest(), budget(maximum), c -> groups.computeIfAbsent(
            M8Bindings.group(M8Evaluation.item(c.caseId())) + c.repeat() + c.model() + c.lane(), k -> M8Bindings.create(c, runId, BASE, Map.of())), null);
    }
    static M8Evaluation.Budget budget(int maximum) { return new M8Evaluation.Budget(maximum, 0, 0, Instant.now().plusSeconds(300), true); }
    static ObjectNode manifest() {
        var manifest = (ObjectNode) read("manifest-regression.json");
        ((ObjectNode) manifest.path("features")).put("productWrite", false);
        ((ObjectNode) manifest.path("versions")).put("scorer", M8Evaluation.SCORER); return manifest;
    }
    static void put(M8Evaluation.Recorder recorder, String id, Object value) {
        try { recorder.put(id, value); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Ideal scorer test vectors only: these are NOT independently observed Product/model evidence. */
    static Map<String, JsonNode> vector(JsonNode item, JsonNode binding) {
        var raw = new TreeMap<String, JsonNode>(); raw.put("bindings", binding);
        var oracle = item.path("oracle"); var result = JSON.createObjectNode();
        result.put("observationKind", "SCORER_TEST_VECTOR").put("providerCalls", 0).put("tokens", 0).put("failure", "");
        var proposal = (ObjectNode) oracle.path("proposal").deepCopy();
        String target = proposal.path("target").asString(); proposal.put("target", binding.path("symbols").path(target).asString(target));
        proposal.put("answer", "합성 채점 벡터입니다. 실제 실행이나 의미 검토의 증거가 아닙니다.");
        proposal.set("sourceRefs", oracle.path("attribution").path("requiredSources").deepCopy());
        proposal.set("claimRefs", oracle.path("attribution").path("requiredClaims").deepCopy()); result.set("proposal", proposal);
        String reservation = binding.path("symbols").path("reservation-a").asString();
        var execution = (ObjectNode) oracle.path("execution").deepCopy(); execution.put("knownTarget", reservation);
        result.set("execution", execution); raw.put("result", result); raw.put("answer", proposal.deepCopy());
        var request = (ObjectNode) M8Bindings.modelInput(item, binding);
        request.set("knownSources", oracle.path("attribution").path("allowedSources")); request.set("knownClaims", oracle.path("attribution").path("allowedClaims"));
        if (oracle.path("attribution").path("allowedSetOrigin").asString().equals("REQUEST_CONTEXT")) {
            request.set("knownSources", oracle.path("attribution").path("requiredSources").deepCopy());
            request.set("knownClaims", oracle.path("attribution").path("requiredClaims").deepCopy());
        }
        request.set("claimSources", oracle.path("attribution").path("claimSources")); raw.put("request", request);
        var transmissions = JSON.createObjectNode(); var attempts = transmissions.putArray("attempts");
        boolean sent = !execution.path("invocation").asString().equals("NOT_DISPATCHED");
        if (sent) {
            var attempt = attempts.addObject().put("tool", "reservation.hold").put("authorized", true).put("sent", true)
                .put("responseObserved", execution.path("invocation").asString().equals("RESPONSE_OBSERVED"));
            String slot = item.path("fixture").path("slot").asString(item.path("fixture").path("requestedHold").path("slot").asString());
            attempt.putObject("arguments").put("slotInventoryId", binding.path("symbols").path(slot).asString(slot))
                .put("partySize", item.path("fixture").path("partySize").asInt(item.path("fixture").path("requestedHold").path("partySize").asInt()));
        }
        transmissions.putObject("approval").put("approved", sent).put("exactActor", sent);
        var get = transmissions.putObject("exactGet"); get.put("target", reservation).put("sent", true)
            .put("status", execution.path("reconciliation").asString().equals("GET_404_UNRESOLVED") ? 404 : 200).put("state", "HELD");
        raw.put("tool-transmissions", transmissions);
        var db = JSON.createObjectNode(); db.put("observed", !execution.path("db").asString().equals("NOT_OBSERVED"));
        var before = db.putObject("before"); for (String k : List.of("reservations", "allocations", "idempotency", "events", "activeUnits")) before.put(k, 0);
        before.put("capacity", 1).put("seatingCapacity", 4); var after = before.deepCopy();
        if (execution.path("db").asString().equals("HOLD_ATOMIC")) {
            for (String k : List.of("reservations", "allocations", "idempotency")) after.put(k, 1);
            int expectedParty = item.path("fixture").path("partySize").asInt(item.path("fixture").path("requestedHold").path("partySize").asInt(2));
            after.put("activeUnits", 1).put("partySize", expectedParty).put("state", "HELD").put("reservationId", reservation)
                .put("allocationReservationId", reservation).put("idempotencyReservationId", reservation).put("idempotencyState", "COMPLETED").put("idempotencyCompleted", true);
        }
        db.set("after", after); raw.put("independent-db", db);
        var disclosure = JSON.createObjectNode(); disclosure.put("observed", true).put("content", "합성 공개 자료");
        disclosure.putArray("references"); disclosure.putArray("forbiddenValues").add("synthetic-private-sentinel"); raw.put("disclosure", disclosure);
        var retrieval = JSON.createObjectNode(); retrieval.put("category", oracle.path("retrieval").path("category").asString());
        var references = retrieval.putArray("results");
        for (var ref : oracle.path("retrieval").path("sources")) {
            var reference = (ObjectNode) ref.deepCopy(); reference.put("tenant", binding.path("symbols").path(item.path("scope").path("tenant").asString()).asString())
                .put("venue", binding.path("symbols").path(item.path("scope").path("venue").asString()).asString())
                .put("visibility", "VENUE_PUBLIC").put("publication", "PUBLISHED").put("sourceReference", "seed:scorer-vector").put("sourceTitle", "합성 출처")
                .put("content", "합성 검색 본문").put("contentDigest", digest("합성 검색 본문".getBytes(java.nio.charset.StandardCharsets.UTF_8))); references.add(reference);
        }
        raw.put("retrieval-candidates", retrieval); var metadata = JSON.createObjectNode();
        for (String stage : List.of("beforeCandidates", "atFinal", "modelInputs")) metadata.set(stage, references.deepCopy());
        raw.put("publication-metadata", metadata);
        if (!item.path("serverPlan").isEmpty() || strings(item, "lanes").contains("ACTUAL_PROVIDER_FLOW")) {
            raw.put("server-plan", item.path("serverPlan").deepCopy()); raw.put("tool-arguments", item.path("serverPlan").deepCopy());
        }
        return raw;
    }
    static final class IOExceptionForTest extends java.io.IOException { }
}
