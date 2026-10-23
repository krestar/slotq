package com.slotq.evaluation;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;
import static com.slotq.evaluation.M8ModelQualificationTests.*;
import static com.slotq.evaluation.M8QualificationVerdictTests.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic comparison/scorer tests only; no model calls or environment qualification. */
class M8ExecutionComparisonTests {
    @ParameterizedTest
    @ValueSource(strings = {"calls", "db-port", "tls-port", "all"})
    void independentExecutionValuesDoNotChangeAnEquivalentProfile(String change) {
        var before = disposableManifest(); var after = before.deepCopy();
        if (change.equals("calls") || change.equals("all")) after.withObject("provider").put("maximumCalls", 24);
        if (change.equals("db-port") || change.equals("all")) after.withObject("environment").put("dbHost", "jdbc:mysql://localhost:49153/evaluation?useSSL=false");
        if (change.equals("tls-port") || change.equals("all")) after.withObject("ingress")
            .put("mcpOrigin", "https://localhost:49155").put("productOrigin", "https://localhost:49155");
        M8EnvironmentContract.validate(before); M8EnvironmentContract.validate(after);
        var source = configSource(before); var next = configSource(after); var snapshots = JSON.createObjectNode();
        assertThat(source.path("configDigest")).isNotEqualTo(next.path("configDigest"));
        var qualification = qualificationPlan();
        M8ModelQualification.verifyReferences(qualification, next, snapshots,
            referenceVector(qualification.path("pilot"), source, snapshots), referenceVector(qualification.path("baseline"), source, snapshots));
        assertThat(M8ModelQualification.comparisonIdentity(source, snapshots)).isEqualTo(M8ModelQualification.comparisonIdentity(next, snapshots));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mysql", "image", "db-name", "db-query", "remote-db", "remote-origin", "tls", "cors", "network", "cpu", "memory", "measurements",
        "baseTime", "slots", "seed", "timezone", "dataset", "oracle", "authority", "approval", "runtime-budget", "runtime-timeout", "feature"})
    void unprovenEnvironmentOrEvaluationChangesRemainIncomparable(String change) {
        var before = disposableManifest(); var after = before.deepCopy();
        switch (change) {
            case "mysql" -> after.withObject("environment").put("mysql", "9.0");
            case "image" -> after.withObject("environment").put("storage", "test-disk; mysql:8.4; image=sha256:" + "b".repeat(64));
            case "db-name" -> after.withObject("environment").put("dbHost", "jdbc:mysql://localhost:49152/other?useSSL=false");
            case "db-query" -> after.withObject("environment").put("dbHost", "jdbc:mysql://localhost:49152/evaluation?useSSL=true");
            case "remote-db" -> after.withObject("environment").put("dbHost", "jdbc:mysql://remote.example:49152/evaluation?useSSL=false");
            case "remote-origin" -> after.withObject("ingress").put("mcpOrigin", "https://remote.example:49154").put("productOrigin", "https://remote.example:49154");
            case "tls" -> after.withObject("ingress").put("tlsEnabled", false);
            case "cors" -> after.withObject("ingress").putArray("corsOrigins").add("https://localhost:49154");
            case "network" -> after.withObject("environment").put("network", "different-route");
            case "cpu" -> after.withObject("environment").put("cpuLimit", 4);
            case "memory" -> after.withObject("environment").put("memoryMiB", 8192);
            case "measurements" -> after.withObject("environment").put("networkMeasurements", "different-measurements");
            case "baseTime" -> after.withObject("data").put("baseTime", "2026-10-21T00:00:00Z");
            case "slots" -> after.withObject("data").put("slots", 6);
            case "seed" -> after.withObject("data").put("seed", 180);
            case "timezone" -> after.withObject("data").put("venueTimezone", "UTC");
            case "dataset", "oracle" -> after.withObject("versions").put(change, "2.0.0");
            case "authority" -> after.withObject("credentials").put("originalActor", "DIFFERENT_ACTOR");
            case "approval" -> after.withObject("credentials").put("approval", "DIFFERENT_APPROVAL");
            case "runtime-budget" -> after.withObject("configuration").put("runtimeProviderAttempts", 2);
            case "runtime-timeout" -> after.withObject("configuration").put("runtimeAttemptTimeoutSeconds", 20);
            case "feature" -> after.withObject("features").put("recovery", true);
        }
        var source = configSource(before); var next = configSource(after); var snapshots = JSON.createObjectNode();
        var qualification = qualificationPlan();
        assertThatThrownBy(() -> M8ModelQualification.verifyReferences(qualification, next, snapshots,
            referenceVector(qualification.path("pilot"), source, snapshots), referenceVector(qualification.path("baseline"), source, snapshots)))
            .hasMessageContaining("not directly comparable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"credential", "not-disposable", "unmeasured", "nonlocal", "image-missing", "read-only", "origin-mismatch"})
    void portDifferencesNeedTheExistingDisposableTargetProfile(String missing) {
        var before = disposableManifest();
        switch (missing) {
            case "credential" -> before.withObject("environment").put("dbPasswordSource", "ENV_REF:OTHER");
            case "not-disposable" -> before.withObject("environment").put("disposable", false);
            case "unmeasured" -> before.withObject("environment").put("measured", false);
            case "nonlocal" -> before.withObject("environment").put("network", "remote-network");
            case "image-missing" -> before.withObject("environment").put("storage", "synthetic-test-disk");
            case "read-only" -> before.withObject("features").put("productWrite", false);
            case "origin-mismatch" -> before.withObject("ingress").put("mcpOrigin", "https://localhost:12345");
        }
        var after = before.deepCopy();
        after.withObject("environment").put("dbHost", "jdbc:mysql://localhost:49153/evaluation?useSSL=false");
        after.withObject("ingress").put("productOrigin", "https://localhost:49155");
        if (!missing.equals("origin-mismatch")) after.withObject("ingress").put("mcpOrigin", "https://localhost:49155");
        assertThat(M8ModelQualification.executionConfigDigest(before)).isNotEqualTo(M8ModelQualification.executionConfigDigest(after));
    }

    @Test void independentlySealedSnapshotsCompareAcrossRunCapsAndCriteriaWithoutRewritingEvidence() throws Exception {
        var firstPlan = plan(List.of(CELL)); var first = pilotArchive(firstPlan, manifest(firstPlan));
        var baselinePlan = plan(List.of(CELL)); baselinePlan.put("maximumCalls", 3);
        var baseline = pilotArchive(baselinePlan, manifest(baselinePlan));
        var qualification = qualificationPlan();
        qualification.set("pilot", referenceTo(first)); qualification.set("baseline", referenceTo(baseline));
        var pilotReference = M8ModelQualification.reference(qualification.path("pilot"));
        var baselineReference = M8ModelQualification.reference(qualification.path("baseline"));
        for (var threshold : qualification.path("criteria").path("thresholds")) {
            if (List.of("quality", "security").contains(threshold.path("id").asString())) {
                var evidence = ((ObjectNode) threshold).putObject("pilotEvidence");
                evidence.setAll(referenceTo(first)); evidence.set("completedAt", pilotReference.path("completion").path("endedAt"));
                String approved = Instant.now().toString();
                ((ObjectNode) threshold).put("approvedAt", approved).put("effectiveAt", approved);
            }
        }
        var nextPlan = plan(List.of(CELL)); nextPlan.put("maximumCalls", 4);
        nextPlan.set("criteria", qualification.path("criteria"));
        var nextManifest = manifest(nextPlan); nextManifest.withObject("versions").put("criteria", "1.1.0");
        var next = pilotArchive(nextPlan, nextManifest);
        var nextSnapshot = readNode(next.directory(), "plan.json");
        var source = readNode(next.directory(), "snapshots/source-build.json");
        M8RunContract.preflight(nextManifest, qualification.path("criteria"), java.util.Set.of("quality", "security"),
            Instant.parse(nextSnapshot.path("frozenAt").asString()));
        M8ModelQualification.verifyReferences(qualification, source, nextSnapshot.path("snapshots"), pilotReference, baselineReference);
        assertThat(first.directory()).isNotEqualTo(baseline.directory());
        assertThat(first.evidenceDigest()).isNotEqualTo(baseline.evidenceDigest());
        for (var session : List.of(first, baseline, next)) {
            byte[] original = Files.readAllBytes(session.directory().resolve("results.json"));
            var recalculated = M8Evaluation.calculate(session.directory(), session.evidenceDigest());
            assertThat(JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(recalculated)).isEqualTo(original);
            assertThat(recalculated.path("status").asString()).isEqualTo("INCOMPLETE");
            assertThat(Files.readAllBytes(session.directory().resolve("results.json"))).isEqualTo(original);
        }
    }

    @Test void runCapIsStillFrozenBoundedAndEnforced() throws Exception {
        var plan = plan(List.of(CELL)); plan.put("maximumCalls", 1);
        var manifest = manifest(plan);
        ((ObjectNode) plan.path("cellReservations").get(0)).put("providerCalls", 2).put("tokens", 70000);
        var runId = "cap-comparison-test-" + java.util.UUID.randomUUID();
        var session = new M8Evaluation(runId, List.of(CELL), manifest, M8ModelQualification.budget(plan),
            cell -> M8Bindings.create(cell, runId, M8EvaluationTests.BASE, java.util.Map.of()), null, plan);
        var result = session.execute((cell, input, recorder) -> { throw new AssertionError("over-budget cell must not execute"); });
        assertThat(result.path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(result.path("rows")).hasSize(1);
        assertThat(result.path("modelQualification").path("cohorts").get(0).path("unexecuted").asInt()).isEqualTo(1);
        assertThat(M8Evaluation.calculate(session.directory(), session.evidenceDigest())).isEqualTo(result);
        plan.put("maximumCalls", 2);
        assertThatThrownBy(() -> M8ModelQualification.preflight(plan, manifest, List.of(CELL), Instant.now())).hasMessageContaining("manifest call cap differs");
        plan.put("maximumCalls", 1001); manifest.withObject("provider").put("maximumCalls", 1001);
        assertThatThrownBy(() -> M8ModelQualification.preflight(plan, manifest, List.of(CELL), Instant.now())).hasMessageContaining("finite call budget");
    }

    @Test void comparablePortDeclarationsCannotAuthorizeMutationWithoutTheLiveTarget() {
        var plan = plan(List.of(CELL)); var manifest = disposableManifest();
        String runId = "unverified-port-test-" + java.util.UUID.randomUUID();
        assertThatThrownBy(() -> new M8Evaluation(runId, List.of(CELL), manifest, M8ModelQualification.budget(plan),
            cell -> M8Bindings.create(cell, runId, M8EvaluationTests.BASE, java.util.Map.of()), null, plan))
            .hasMessageContaining("verified disposable MySQL target");
    }

    @ParameterizedTest
    @ValueSource(strings = {"manifest", "source-build"})
    void originalSnapshotsCannotBeTamperedEvenWhenOnlyRunSpecificValuesChange(String snapshot) throws Exception {
        var plan = plan(List.of(CELL)); var session = pilotArchive(plan, manifest(plan));
        var path = session.directory().resolve("snapshots/" + snapshot + ".json");
        var changed = (ObjectNode) JSON.readTree(Files.readAllBytes(path));
        if (snapshot.equals("manifest")) changed.withObject("provider").put("maximumCalls", 24);
        else changed.put("configDigest", "0".repeat(64));
        Files.write(path, JSON.writeValueAsBytes(changed));
        assertThatThrownBy(() -> M8Evaluation.calculate(session.directory(), session.evidenceDigest())).hasMessageContaining("artifact");
    }

    static ObjectNode referenceTo(M8Evaluation session) throws Exception {
        return JSON.createObjectNode().put("runId", session.directory().getFileName().toString()).put("digest", session.evidenceDigest());
    }

    static ObjectNode disposableManifest() {
        var manifest = manifest(plan(List.of(CELL)));
        manifest.withObject("features").put("productWrite", true);
        manifest.withObject("environment").put("dbPasswordSource", "ENV_REF:TESTCONTAINERS_EPHEMERAL")
            .put("dbHost", "jdbc:mysql://localhost:49152/evaluation?useSSL=false")
            .put("storage", "test-disk; mysql:8.4; image=sha256:" + "a".repeat(64));
        manifest.withObject("ingress").put("mcpOrigin", "https://localhost:49154").put("productOrigin", "https://localhost:49154");
        return manifest;
    }
}
