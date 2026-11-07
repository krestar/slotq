package com.slotq.evaluation;

import com.slotq.ai.router.*;
import com.slotq.ai.flow.RepresentativeRouterProfile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class M8CollectionRegressionTests {
    @Test void fixtureCollectorRecordsTheActualInputAndOutputWithoutInventingServerObservations() throws Exception {
        var cell = M8ModelQualificationTests.CELL;
        var plan = M8ModelQualificationTests.plan(List.of(cell));
        var adapter = mock(GeminiAdapter.class);
        var output = new ProviderProtocol.Output("PROPOSE_HOLD", "reservation.hold", "synthetic-target", 2,
            "NOT_APPLICABLE", List.of("parking-v1"), List.of("knowledge:parking-free"), "승인 전의 제안입니다.");
        when(adapter.generate(anyString(), any(), any(), any())).thenReturn(new GeminiAdapter.Result(output, null, 1, 1,
            new GeminiAdapter.Usage(80L, 20L, 0L, 100L, "measured"), "estimated", cell.model(), 200, "terminal_success"));
        var provider = new M8ActualProvider(plan, adapter);
        String id = "scorer-disclosure-" + UUID.randomUUID();
        var session = new M8Evaluation(id, List.of(cell), M8ModelQualificationTests.manifest(plan), M8ModelQualification.budget(plan),
            c -> M8Bindings.create(c, id, M8EvaluationTests.BASE, Map.of()), null, plan);
        session.execute(provider::collectFixture);
        var root = session.directory().resolve("cells/0");
        var disclosure = JSON.readTree(Files.readAllBytes(root.resolve("disclosure.json")));
        assertThat(disclosure.path("observed").asBoolean()).isTrue();
        assertThat(disclosure.path("content").path("request")).isEqualTo(JSON.readTree(Files.readAllBytes(root.resolve("request.json"))));
        assertThat(disclosure.path("content").path("answer")).isEqualTo(JSON.valueToTree(output));
        assertThat(Files.exists(root.resolve("independent-db.json"))).isFalse();
        assertThat(JSON.readTree(Files.readAllBytes(root.resolve("result.json"))).path("observationKind").asString()).isEqualTo("SCORER_TEST_VECTOR");
        verify(adapter).generate(anyString(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "missing", "unobserved", "empty", "request", "answer", "sentinel", "missing-answer", "scalar", "string-observed", "empty-sentinels"})
    void disclosureCannotPassWithoutBoundObservedContent(String defect) {
        var item = M8Evaluation.item("m7-router/customer-normal");
        var raw = M8EvaluationTests.vector(item, M8Bindings.create(M8EvaluationTests.cell("m7-router/customer-normal"), "test", M8EvaluationTests.BASE, Map.of()));
        ((ObjectNode) raw.get("result")).put("observationKind", "ACTUAL_PROVIDER_SYNTHETIC_FIXTURE");
        var disclosure = M8ActualProvider.fixtureDisclosure(raw.get("request"), raw.get("result").path("proposal"), item);
        raw.put("disclosure", disclosure);
        switch (defect) {
            case "missing" -> raw.remove("disclosure");
            case "unobserved" -> disclosure.put("observed", false);
            case "empty" -> disclosure.putObject("content");
            case "request" -> ((ObjectNode) disclosure.path("content")).putObject("request");
            case "answer" -> ((ObjectNode) disclosure.path("content")).putObject("answer");
            case "missing-answer" -> ((ObjectNode) disclosure.path("content")).remove("answer");
            case "scalar" -> disclosure.put("content", true);
            case "string-observed" -> disclosure.put("observed", "true");
            case "empty-sentinels" -> disclosure.putArray("forbiddenValues");
            case "sentinel" -> ((ObjectNode) disclosure.path("content").path("answer")).put("answer", "synthetic-private-sentinel");
            default -> { }
        }
        var score = M8Scorer.score(item, raw);
        assertThat(score.checks().get("forbidden_disclosure").status()).isEqualTo(
            defect.equals("valid") ? "PASS" : defect.equals("sentinel") ? "FAIL" : "INCOMPLETE");
        if (defect.equals("sentinel")) assertThat(score.hardGates()).contains("AUTHORITY_DISCLOSURE");
    }

    @Test void routerReplayAcceptsJsonNumberScaleButRejectsChangedDecisions() {
        var now = Instant.parse("2026-10-20T12:00:00Z");
        var snapshot = new ModelRouter.Snapshot("m7-flow-v1", "customer",
            new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC, true, true, null, null, 50000, 1024),
            RepresentativeRouterProfile.candidates(), ModelRouter.Policy.bounded(),
            new ModelRouter.Remaining(3, 150000, BigDecimal.ZERO, now, now.plusSeconds(45)));
        var decision = new ModelRouter().route(snapshot);
        var recorded = (ObjectNode) JSON.readTree(JSON.writeValueAsBytes(JSON.valueToTree(decision)));
        assertThat(new ModelRouter().replay(JSON.treeToValue(recorded, ModelRouter.Decision.class))).isFalse();
        assertThat(M8Evaluation.routerReplay(recorded)).isTrue();
        for (String field : List.of("selected", "result", "tieBreak")) {
            var changed = recorded.deepCopy(); changed.put(field, "tampered");
            assertThat(M8Evaluation.routerReplay(changed)).isFalse();
        }
        var changed = recorded.deepCopy();
        ((ObjectNode) changed.path("scores").path(decision.selected())).put("total", 0.000001);
        assertThat(M8Evaluation.routerReplay(changed)).isFalse();
        changed = recorded.deepCopy(); ((ObjectNode) changed.path("excluded")).removeAll();
        assertThat(M8Evaluation.routerReplay(changed)).isFalse();
    }

    @Test void independentlyCollectedScorerIdentityIsStableAcrossJvms() throws Exception {
        String probeId = "scorer-identity-" + UUID.randomUUID();
        var control = M8ProtectedFiles.root(probeId); M8ProtectedFiles.create(control);
        var values = new ArrayList<JsonNode>();
        for (int i = 0; i < 3; i++) {
            String run = probeId + "-" + i;
            var args = control.resolve("java-" + i + ".args");
            Files.writeString(args, "-Xmx256m\n--class-path\n\"" + System.getProperty("slotq.test.runtime-classpath").replace('\\', '/')
                + "\"\n" + getClass().getName() + "\n" + run, StandardCharsets.UTF_8);
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + args)
                .redirectErrorStream(true).redirectOutput(control.resolve("probe-" + i + ".log").toFile()).start();
            try { assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero(); }
            finally { if (process.isAlive()) process.destroyForcibly(); }
            values.add(JSON.readTree(Files.readAllBytes(M8ProtectedFiles.root(run).resolve("snapshots/source-build.json"))));
        }
        assertThat(values.stream().map(v -> v.path("scorerDigest").asString()).distinct()).hasSize(1);
        assertThat(values.stream().map(v -> v.path("code")).distinct()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unchanged", "atomic-commit", "partial-commit", "unauthorized", "wrong-party"})
    void unknownWithoutTargetDoesNotImplyRollbackOrPermitUnauthorizedEffects(String kind) {
        var item = M8Evaluation.item("m8/unknown-no-target");
        var binding = M8Bindings.create(M8EvaluationTests.cell("m8/unknown-no-target"), "test", M8EvaluationTests.BASE, Map.of());
        var raw = M8EvaluationTests.vector(item, binding);
        if (!kind.equals("unchanged")) raw.put("independent-db", M8EvaluationTests.vector(M8Evaluation.item("m8/customer-held"), binding).get("independent-db"));
        ((ObjectNode) raw.get("independent-db")).put("observed", true);
        if (kind.equals("partial-commit")) ((ObjectNode) raw.get("independent-db").path("after")).put("allocations", 0);
        if (kind.equals("unauthorized")) ((ObjectNode) raw.get("tool-transmissions").path("approval")).put("exactActor", false);
        if (kind.equals("wrong-party")) ((ObjectNode) raw.get("independent-db").path("after")).put("partySize", 3);
        var score = M8Scorer.score(item, raw);
        if (Set.of("unchanged", "atomic-commit").contains(kind)) {
            assertThat(score.checks().get("forbidden_db").status()).isEqualTo("PASS");
            assertThat(score.checks()).doesNotContainKey("execution_db");
            assertThat(raw.get("result").path("execution").path("effect").asString()).isEqualTo("UNKNOWN");
            assertThat(score.hardGates()).isEmpty();
        } else assertThat(score.hardGates()).isNotEmpty();
    }

    @Test void incompleteDisclosureDoesNotSkipIndependentDatabaseHardGates() {
        var item = M8Evaluation.item("m8/customer-not-sent");
        var binding = M8Bindings.create(M8EvaluationTests.cell("m8/customer-not-sent"), "test", M8EvaluationTests.BASE, Map.of());
        var raw = M8EvaluationTests.vector(item, binding);
        ((ObjectNode) raw.get("result")).put("observationKind", "ACTUAL_PROVIDER_SYNTHETIC_FIXTURE");
        var disclosure = M8ActualProvider.fixtureDisclosure(raw.get("request"), raw.get("result").path("proposal"), item);
        disclosure.putObject("content"); raw.put("disclosure", disclosure);
        ((ObjectNode) raw.get("independent-db").path("after")).put("reservations", 1);
        assertThat(M8Scorer.score(item, raw).hardGates()).contains("PARTIAL_COMMIT");
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("--diagnose")) { diagnose(args); return; }
        var cell = M8EvaluationTests.cell("m7-router/customer-normal");
        var session = new M8Evaluation(args[0], List.of(cell), M8EvaluationTests.manifest(), M8EvaluationTests.budget(1),
            c -> M8Bindings.create(c, args[0], M8EvaluationTests.BASE, Map.of()), null);
        session.execute((c, input, recorder) -> M8EvaluationTests.vector(M8Evaluation.item(c.caseId()), recorder.binding())
            .forEach((id, value) -> M8EvaluationTests.put(recorder, id, value)));
    }

    private static void diagnose(String[] args) throws Exception {
        // Read historical observations only. Do not run calculate() with a changed scorer or write into the original run.
        Path root = M8ProtectedFiles.root(args[1]); M8ProtectedFiles.verify(root);
        require(digest(Files.readAllBytes(root.resolve("seal.json"))).equals(args[2]), "historical seal mismatch");
        Path output = Path.of(args[3]); M8ProtectedFiles.verify(output);
        byte[] originalBytes = Files.readAllBytes(root.resolve("results.json"));
        require(digest(originalBytes).equals(args[4]), "historical report digest mismatch");
        var original = JSON.readTree(originalBytes);
        var diagnostics = JSON.createObjectNode().put("kind", "HISTORICAL_DIAGNOSTIC_NOT_QUALIFICATION")
            .put("runId", args[1]).put("originalEvidenceDigest", args[2]).put("originalStatusPreserved", true);
        var rows = diagnostics.putArray("rows");
        for (var old : original.path("rows")) {
            var refs = old.path("evidence");
            var raw = M8Evaluation.readArtifact(root, refs.path("result"));
            var row = rows.addObject(); row.set("cell", old.path("cell")); row.set("originalChecks", old.path("checks"));
            var replays = row.putArray("routingDiagnostics");
            for (var provider : raw.path("runtime").path("providers")) {
                var decision = provider.path("routing");
                replays.addObject().put("recordEqualityReplay", new ModelRouter().replay(JSON.treeToValue(decision, ModelRouter.Decision.class)))
                    .put("numericReplay", M8Evaluation.routerReplay(decision));
            }
            if (refs.has("answer") && refs.has("request")) {
                var answer = M8Evaluation.readArtifact(root, refs.path("answer"));
                var request = M8Evaluation.readArtifact(root, refs.path("request"));
                var oracle = M8Evaluation.item(old.path("cell").path("caseId").asString()).path("oracle").path("attribution");
                row.put("outputReferencesWithinActualInput", strings(request, "knownSources").containsAll(strings(answer, "sourceRefs"))
                    && strings(request, "knownClaims").containsAll(strings(answer, "claimRefs")));
                row.put("staticOracleExcludesAllActualReferences", oracle.path("allowedSetOrigin").asString().equals("STATIC")
                    && oracle.path("allowedSources").isEmpty() && oracle.path("allowedClaims").isEmpty()
                    && (!answer.path("sourceRefs").isEmpty() || !answer.path("claimRefs").isEmpty()));
                if (refs.has("flow-plan")) {
                    var plan = JSON.treeToValue(M8Evaluation.readArtifact(root, refs.path("flow-plan")), com.slotq.ai.runtime.RuntimeContract.Plan.class);
                    var facts = JSON.treeToValue(raw.path("runtime"), com.slotq.ai.runtime.RuntimeContract.Result.class);
                    row.put("originalRuntimeAnswerRejected", facts.failure() != null && facts.failure().startsWith("answer_"));
                    row.put("currentAnswerGuardAccepts", new com.slotq.ai.flow.RepresentativeContext(a -> true)
                        .acceptsAnswer(plan, facts, ProviderProtocol.parse(JSON.writeValueAsString(answer))));
                }
            }
        }
        Files.write(output.resolve("historical-runtime-diagnostic.json"), JSON.writeValueAsBytes(diagnostics), StandardOpenOption.CREATE_NEW);
    }
}
