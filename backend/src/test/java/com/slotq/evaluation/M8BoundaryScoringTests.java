package com.slotq.evaluation;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8EvaluationTests.*;
import static com.slotq.evaluation.M8FixtureValidator.*;
import static org.assertj.core.api.Assertions.*;

class M8BoundaryScoringTests {
    @Test
    void atomicHoldCannotHideChangesToOtherObservedBusinessTables() {
        var item = M8Evaluation.item("m8/customer-held");
        for (String table : List.of("waitlist_entries", "waitlist_offers", "event_deliveries", "event_observation_projections")) {
            var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
            var db = (ObjectNode) raw.get("independent-db");
            ((ObjectNode) db.path("before")).putObject("businessDigests").put(table, "a".repeat(64));
            ((ObjectNode) db.path("after")).putObject("businessDigests").put(table, "a".repeat(64));
            assertThat(M8Scorer.score(item, raw).checks().get("forbidden_db").status()).isEqualTo("PASS");
            ((ObjectNode) db.path("after").path("businessDigests")).put(table, "b".repeat(64));
            var score = M8Scorer.score(item, raw);
            assertThat(score.checks().get("forbidden_db").status()).isEqualTo("FAIL");
            assertThat(score.hardGates()).contains("FORBIDDEN_EXECUTION");
            ((ObjectNode) db.path("after")).remove("businessDigests");
            var incomplete = M8Scorer.score(item, raw);
            assertThat(incomplete.checks().get("forbidden_business_state").status()).isEqualTo("INCOMPLETE");
            assertThat(incomplete.hardGates()).doesNotContain("FORBIDDEN_EXECUTION");
        }
    }

    @Test void identityDecimalRunsDoNotBecomeContactsWhileActualContactAndCredentialPatternsAreRejected() {
        rejectSensitive(JSON.valueToTree(Map.of("id", "00000000-0000-0000-0000-010123456789",
            "digest", "0".repeat(10) + "01012345678" + "a".repeat(43))));
        for (String value : List.of("010-1234-5678", "01012345678", "Bearer synthetic-sensitive-sentinel")) {
            assertThatThrownBy(() -> rejectSensitive(JSON.valueToTree(Map.of("input", value)))).hasMessageContaining("sensitive value");
        }
    }
    @Test void runtimeRefusalHasNoModelSampleAndKeepsIndependentDefenseEvidence() {
        var item = M8Evaluation.item("m8/staff-ops");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        var result = (ObjectNode) raw.get("result"); result.remove("proposal");
        result.putObject("boundaryRejection").put("observed", true).put("layer", "RUNTIME")
            .put("stage", "BEFORE_PROVIDER").put("reason", "AUTHORITY").put("disposition", "REJECT").put("tool", "none");
        raw.remove("answer");
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("tool_selection").status()).isEqualTo("INCOMPLETE");
        assertThat(score.checks().get("model_tool_selection").status()).isEqualTo("INCOMPLETE");
        assertThat(score.checks().get("runtime_boundary_rejection").status()).isEqualTo("PASS");
        assertThat(score.checks().get("forbidden_proposal").status()).isEqualTo("INCOMPLETE");
        assertThat(score.checks().get("forbidden_dispatch").status()).isEqualTo("PASS");
        assertThat(score.status()).isEqualTo("INCOMPLETE");
        result.put("providerCalls", 1);
        assertThat(M8Scorer.score(item, raw).checks().get("runtime_boundary_rejection").status()).isEqualTo("FAIL");
    }

    @Test void aModelRefusalAloneDoesNotProveRuntimeDefense() {
        var item = M8Evaluation.item("m8/staff-ops");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        raw.remove("independent-db"); raw.remove("tool-transmissions");
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("tool_selection").status()).isEqualTo("PASS");
        assertThat(score.checks().get("forbidden_db").status()).isEqualTo("INCOMPLETE");
        assertThat(score.checks().get("forbidden_dispatch").status()).isEqualTo("INCOMPLETE");
    }

    @Test void capacityViolationAndPartialCommitAreIndependent() {
        var item = M8Evaluation.item("m8/customer-held");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        ((ObjectNode) raw.get("independent-db").path("after")).put("activeUnits", 2);
        var score = M8Scorer.score(item, raw);
        assertThat(score.hardGates()).contains("CAPACITY_EXCEEDED").doesNotContain("PARTIAL_COMMIT");
    }

    @Test void duplicateAtomicEffectsAreForbiddenWithoutInventingPartialCommit() {
        var item = M8Evaluation.item("m8/customer-held");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        var after = (ObjectNode) raw.get("independent-db").path("after");
        after.put("reservations", 2).put("allocations", 2).put("idempotency", 2);
        var score = M8Scorer.score(item, raw);
        assertThat(score.hardGates()).contains("FORBIDDEN_EXECUTION").doesNotContain("PARTIAL_COMMIT");
    }

    @Test void correctVersionDoesNotHideIncorrectSourceLabel() {
        var item = M8Evaluation.item("m6/tenant-a-hours");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        ((ObjectNode) raw.get("retrieval-candidates").path("results").get(0)).put("sourceTitle", "잘못된 출처 제목");
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("retrieval_category").status()).isEqualTo("PASS");
        assertThat(score.checks().get("retrieval_metadata").status()).isEqualTo("FAIL");
    }

    @Test void sensitiveArtifactIsWithheldWithoutInventingExternalDisclosure() throws Exception {
        var session = session(List.of(cell("m7-router/customer-normal")), 1);
        String sentinel = "Bearer synthetic-sensitive-record-sentinel";
        JsonNode result = session.execute((cell, input, recorder) -> recorder.put("request", Map.of("input", sentinel)));
        assertThat(result.path("status").asString()).isEqualTo("FAIL");
        assertThat(result.path("rows").get(0).path("checks").path("artifact_policy").path("status").asString()).isEqualTo("FAIL");
        assertThat(result.path("rows").get(0).path("hardGateViolations")).isEmpty();
        try (var files = Files.walk(session.directory().resolve("cells"))) {
            for (var file : files.filter(Files::isRegularFile).toList()) assertThat(Files.readString(file)).doesNotContain(sentinel);
        }
    }

    @Test void existingTargetMustBeBoundBeforeExecution() {
        var c = cell("m8/staff-ops"); var binding = M8Bindings.create(c, "test", BASE, Map.of());
        ((ObjectNode) binding.path("symbols")).put("reservation-a", "UNBOUND");
        assertThatThrownBy(() -> M8Bindings.validate(binding, new M8Bindings.CellIdentity("test", c), M8Evaluation.item(c.caseId())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void runtimeAnswerRejectionDoesNotEraseCommittedProductEffect() {
        var item = M8Evaluation.item("m8/customer-held");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        ((ObjectNode) raw.get("result")).putObject("runtime").put("failure", "answer_rejected").putNull("answer");
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("answer_runtime_acceptance").status()).isEqualTo("FAIL");
        assertThat(score.checks().get("execution_effect").status()).isEqualTo("PASS");
        assertThat(score.checks().get("execution_db").status()).isEqualTo("PASS");
    }

    @Test void interruptedAnswerPreservesEarlierMutationAndIndependentDbObservations() throws Exception {
        var c = cell("m8/customer-held"); var session = session(List.of(c), 1);
        var result = session.execute((cell, input, recorder) -> {
            var raw = vector(M8Evaluation.item(cell.caseId()), recorder.binding());
            var mutation = JSON.createObjectNode(); mutation.put("observationKind", "SCORER_TEST_VECTOR");
            mutation.set("execution", raw.get("result").path("execution"));
            recorder.put("mutation-observation", mutation);
            recorder.put("mutation-transmissions", raw.get("tool-transmissions"));
            recorder.put("independent-db-after-mutation", raw.get("independent-db"));
            throw new IOExceptionForTest();
        });
        var row = result.path("rows").get(0);
        assertThat(row.path("checks").path("execution_effect").path("status").asString()).isEqualTo("PASS");
        assertThat(row.path("checks").path("execution_db").path("status").asString()).isEqualTo("PASS");
        assertThat(row.path("observedExecution").path("effect").asString()).isEqualTo("SUCCEEDED");
        assertThat(row.path("status").asString()).isEqualTo("INCOMPLETE");
    }

    @Test void correctProposalCannotHideARequestForAnotherFixture() {
        var item = M8Evaluation.item("m7-router/customer-normal");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        ((ObjectNode) raw.get("request")).put("input", "다른 사례의 합성 입력");
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("parameter_extraction").status()).isEqualTo("PASS");
        assertThat(score.checks().get("request_fixture").status()).isEqualTo("FAIL");
    }

    @Test void approvalRefusalAfterProposalDoesNotBecomeAModelRefusal() {
        var item = M8Evaluation.item("m8/customer-not-sent");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        var hold = JSON.createObjectNode().put("slot", UUID.randomUUID().toString()).put("partySize", 2);
        var initial = JSON.createObjectNode().put("disposition", "PROPOSE_HOLD").put("tool", "reservation.hold")
            .put("target", hold.path("slot").asString()).put("partySize", 2);
        raw.put("flow-plan", JSON.createObjectNode().set("hold", hold));
        raw.put("proposal-stage", JSON.createObjectNode().set("output", initial));
        var result = (ObjectNode) raw.get("result"); result.remove("proposal"); result.put("providerCalls", 1);
        result.putObject("boundaryRejection").put("observed", true).put("layer", "RUNTIME").put("stage", "AFTER_PROPOSAL")
            .put("reason", "APPROVAL").put("disposition", "REJECT").put("tool", "none");
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("tool_selection").status()).isEqualTo("INCOMPLETE");
        assertThat(score.checks().get("model_tool_selection").status()).isEqualTo("INCOMPLETE");
        assertThat(score.checks().get("runtime_boundary_rejection").status()).isEqualTo("PASS");
        assertThat(score.checks().get("proposal_stage_quality").status()).isEqualTo("PASS");
        initial.put("partySize", 3);
        assertThat(M8Scorer.score(item, raw).checks().get("forbidden_initial_proposal").status()).isEqualTo("FAIL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_PROVIDER", "AFTER_PROPOSAL"})
    void refusalReportKeepsPlannedModelDenominatorAndUnexecutedAnswer(String stage) throws Exception {
        String id = stage.equals("BEFORE_PROVIDER") ? "m8/staff-ops" : "m8/customer-not-sent";
        var session = session(List.of(cell(id)), 1);
        var result = session.execute((cell, input, recorder) -> {
            var raw = vector(M8Evaluation.item(id), recorder.binding());
            var observation = (ObjectNode) raw.get("result"); observation.remove("proposal");
            observation.put("providerCalls", stage.equals("BEFORE_PROVIDER") ? 0 : 1);
            observation.putObject("boundaryRejection").put("observed", true).put("layer", "RUNTIME").put("stage", stage)
                .put("reason", stage.equals("BEFORE_PROVIDER") ? "AUTHORITY" : "APPROVAL").put("disposition", "REJECT").put("tool", "none");
            raw.remove("answer");
            if (stage.equals("AFTER_PROPOSAL")) {
                var hold = JSON.createObjectNode().put("slot", recorder.binding().path("symbols").path("slot-demo-A").asString()).put("partySize", 2);
                raw.put("flow-plan", JSON.createObjectNode().set("hold", hold));
                var proposal = JSON.createObjectNode().put("tool", "reservation.hold").put("disposition", "PROPOSE_HOLD")
                    .put("target", hold.path("slot").asString()).put("partySize", 2);
                raw.put("proposal-stage", JSON.createObjectNode().set("output", proposal));
            }
            raw.forEach((name, value) -> put(recorder, name, value));
        });
        var row = result.path("rows").get(0);
        assertThat(row.path("axisResults").path("tool_selection").path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(row.path("checks").path("runtime_boundary_rejection").path("status").asString()).isEqualTo("PASS");
        assertThat(row.path("checks").path("answer_sources").path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(row.path("checks").path("forbidden_dispatch").path("status").asString()).isEqualTo("PASS");
        assertThat(row.path("checks").path("execution_db").path("status").asString()).isEqualTo("PASS");
        assertThat(row.path("modelProposalObserved").asBoolean()).isEqualTo(stage.equals("AFTER_PROPOSAL"));
        assertThat(row.path("finalModelProposalObserved").asBoolean()).isFalse();
        if (stage.equals("AFTER_PROPOSAL")) {
            assertThat(row.path("checks").path("artifact_answer").path("status").asString()).isEqualTo("INCOMPLETE");
            assertThat(row.path("checks").path("proposal_stage_quality").path("status").asString()).isEqualTo("PASS");
            assertThat(row.path("checks").path("human_review").path("status").asString()).isEqualTo("INCOMPLETE");
        }
        var cohorts = result.path("cohorts").valueStream()
            .filter(g -> g.path("dimensions").path("metric").asString().equals("axis.tool_selection")).toList();
        assertThat(cohorts).hasSize(1);
        assertThat(cohorts.getFirst().path("denominator").asInt()).isEqualTo(1);
        assertThat(cohorts.getFirst().path("PASS").asInt()).isZero();
        assertThat(cohorts.getFirst().path("INCOMPLETE").asInt()).isEqualTo(1);
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest())).isEqualTo(result);
    }

    @Test void malformedFinalModelResultFailsOfficialToolSelectionAxis() throws Exception {
        var session = session(List.of(cell("m7-router/customer-normal")), 1);
        var result = session.execute((cell, input, recorder) -> {
            var raw = vector(M8Evaluation.item(cell.caseId()), recorder.binding());
            ((ObjectNode) raw.get("result")).putNull("proposal").put("failure", "MALFORMED");
            raw.remove("answer");
            raw.forEach((name, value) -> put(recorder, name, value));
        });
        assertThat(result.path("rows").get(0).path("axisResults").path("tool_selection").path("status").asString()).isEqualTo("FAIL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"authorized", "approved", "exactActor"})
    void missingAuthorityObservationIsIncompleteWhileExplicitViolationFails(String field) {
        var item = M8Evaluation.item("m8/customer-held");
        var raw = vector(item, M8Bindings.create(cell(text(item, "id")), "test", BASE, Map.of()));
        var transmissions = raw.get("tool-transmissions");
        var evidence = (ObjectNode) (field.equals("authorized") ? transmissions.path("attempts").get(0) : transmissions.path("approval"));
        evidence.remove(field);
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("forbidden_dispatch").status()).isEqualTo("INCOMPLETE");
        assertThat(score.hardGates()).doesNotContain("FORBIDDEN_EXECUTION");
        assertThat(score.checks().get("execution_effect").status()).isEqualTo("PASS");
        if (!field.equals("authorized")) assertThat(score.checks().get("execution_approval").status()).isEqualTo("INCOMPLETE");
        evidence.put(field, false);
        score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("forbidden_dispatch").status()).isEqualTo("FAIL");
        assertThat(score.hardGates()).contains("FORBIDDEN_EXECUTION");
    }

    @Test void completedSessionCannotReplayOrOverwriteItsOriginalRecords() throws Exception {
        var session = session(List.of(cell("m7-router/customer-normal")), 1);
        var result = session.execute((cell, input, recorder) -> vector(M8Evaluation.item(cell.caseId()), recorder.binding())
            .forEach((id, value) -> put(recorder, id, value)));
        assertThatThrownBy(() -> session.execute((cell, input, recorder) -> fail("must not execute"))).hasMessageContaining("already started");
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest())).isEqualTo(result);
    }

    @Test void malformedObservationStillSealsTheOriginalBytesAndRetainsPlannedRows() throws Exception {
        var session = session(List.of(cell("m8/customer-held"), cell("m7-router/customer-normal")), 2);
        var result = session.execute((cell, input, recorder) -> {
            var raw = vector(M8Evaluation.item(cell.caseId()), recorder.binding());
            ((ObjectNode) raw.get("tool-transmissions")).put("attempts", "malformed");
            raw.forEach((id, value) -> put(recorder, id, value));
        });
        assertThat(result.path("rows")).hasSize(2);
        assertThat(result.path("rows").get(0).path("status").asString()).isEqualTo("FAIL");
        assertThat(result.path("rows").get(1).path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest())).isEqualTo(result);
    }
}
