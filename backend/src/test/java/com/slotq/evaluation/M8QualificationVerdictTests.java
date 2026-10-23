package com.slotq.evaluation;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;
import static com.slotq.evaluation.M8ModelQualificationTests.*;
import static org.assertj.core.api.Assertions.*;

/** Fabricated sealed scorer inputs only. No provider adapter, credential access or actual-model evidence. */
class M8QualificationVerdictTests {
    @Test void completeFrozenQualificationCellCanPassWithoutQualifyingTheRequiredPathSet() throws Exception {
        var archive = archive();
        var result = archive.calculate();
        assertThat(result.path("rows").get(0).path("automaticStatus").asString()).isEqualTo("PASS");
        assertThat(result.path("rows").get(0).path("status").asString()).isEqualTo("PASS");
        assertThat(result.path("status").asString()).isEqualTo("PASS");
        assertThat(result.path("criteriaState").asString()).isEqualTo("FROZEN");
        assertThat(result.path("rows").get(0).path("checks").path("evidence_lane").path("status").asString()).isEqualTo("PASS");
        assertThat(result.path("modelQualification").path("requiredPathSetStatus").asString()).isEqualTo("INCOMPLETE");
        assertThat(result.path("releaseQualification").asBoolean()).isFalse();
        assertThat(result.path("modelQualification").path("releaseQualification").asBoolean()).isFalse();
        assertThat(archive.calculate()).isEqualTo(result);
        var report = archive.root().resolve("test-report");
        M8Evaluation.writeReport(report, result);
        assertThat(Files.readString(report.resolve("summary.ko.md"))).contains("기준: FROZEN", "출시 판정: 수행하지 않음");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending-pilot", "frozen-pilot", "no-call", "usage-missing", "review-missing", "review-digest", "raw-missing", "oracle", "safety", "review-rejected"})
    void pendingPilotAndMissingEvidenceNeverPassWhileObservedViolationsFail(String defect) throws Exception {
        var archive = archive(); var root = archive.root(); var frozen = archive.plan();
        var snapshots = (ObjectNode) frozen.path("snapshots");
        var qualification = readNode(root, snapshots.path("qualification").path("path").asString());
        var criteria = (ObjectNode) qualification.path("criteria");
        if (defect.endsWith("pilot")) {
            qualification.put("phase", "PILOT"); frozen.put("phase", "PILOT");
            if (defect.equals("pending-pilot")) {
                criteria = (ObjectNode) read("criteria.json"); qualification.set("criteria", criteria);
                ((ObjectNode) frozen.path("manifest").path("versions")).put("criteria", "1.0.0");
                replace(root, snapshots, "manifest", frozen.path("manifest"));
                var source = readNode(root, snapshots.path("source-build").path("path").asString());
                source.put("configDigest", snapshots.path("manifest").path("digest").asString());
                replace(root, snapshots, "source-build", source);
            }
            replace(root, snapshots, "criteria.json", criteria);
            var source = readNode(root, snapshots.path("source-build").path("path").asString());
            qualification.set("frozenIdentity", M8ModelQualification.identity(source, snapshots));
            replace(root, snapshots, "qualification", qualification);
        } else {
            var record = readNode(root, "cells/0/record.json");
            var artifacts = (ObjectNode) record.path("artifacts");
            switch (defect) {
                case "no-call" -> artifacts.remove("provider-call-a");
                case "usage-missing" -> {
                    var journal = readNode(root, artifacts.path("provider-call-a").path("path").asString());
                    ((ObjectNode) journal.path("result")).putObject("usage").put("status", "unavailable");
                    replace(root, artifacts, "provider-call-a", journal);
                }
                case "review-missing" -> artifacts.remove("review");
                case "review-digest", "review-rejected" -> {
                    var review = readNode(root, artifacts.path("review").path("path").asString());
                    if (defect.equals("review-digest")) review.put("answerDigest", "0".repeat(64));
                    else review.put("state", "REJECTED");
                    replace(root, artifacts, "review", review);
                }
                case "raw-missing" -> Files.delete(root.resolve(artifacts.path("disclosure").path("path").asString()));
                case "oracle" -> {
                    var result = readNode(root, artifacts.path("result").path("path").asString());
                    ((ObjectNode) result.path("proposal")).put("partySize", 3);
                    replace(root, artifacts, "result", result);
                }
                case "safety" -> {
                    var disclosure = readNode(root, artifacts.path("disclosure").path("path").asString());
                    disclosure.put("content", "synthetic-private-sentinel");
                    replace(root, artifacts, "disclosure", disclosure);
                }
            }
            Files.write(root.resolve("cells/0/record.json"), JSON.writeValueAsBytes(record));
        }
        Files.write(root.resolve("plan.json"), JSON.writeValueAsBytes(frozen));
        var result = M8Evaluation.calculate(root, seal(root, defect.equals("raw-missing")));
        String expected = Set.of("oracle", "safety", "review-rejected").contains(defect) ? "FAIL" : "INCOMPLETE";
        assertThat(result.path("rows").get(0).path("status").asString()).isEqualTo(expected);
        assertThat(result.path("criteriaState").asString()).isEqualTo(defect.equals("pending-pilot") ? "PENDING" : "FROZEN");
        if (defect.equals("safety")) assertThat(result.path("rows").get(0).path("hardGateViolations")).isNotEmpty();
    }

    @Test void criteriaSnapshotCannotDisagreeWithTheApprovedQualificationPlan() throws Exception {
        var archive = archive(); var snapshots = (ObjectNode) archive.plan().path("snapshots");
        replace(archive.root(), snapshots, "criteria.json", read("criteria.json"));
        Files.write(archive.root().resolve("plan.json"), JSON.writeValueAsBytes(archive.plan()));
        assertThatThrownBy(() -> M8Evaluation.calculate(archive.root(), seal(archive.root(), false)))
            .hasMessageContaining("criteria snapshot");
    }

    @ParameterizedTest
    @ValueSource(strings = {"same-run", "same-digest", "same-reference"})
    void pilotAndBaselineMustBeDifferentSealedExecutions(String defect) {
        var qualification = qualificationPlan(); var source = JSON.createObjectNode(); var snapshots = JSON.createObjectNode();
        var pilot = referenceVector(qualification.path("pilot"), source, snapshots);
        var baseline = referenceVector(qualification.path("baseline"), source, snapshots);
        M8ModelQualification.verifyReferences(qualification, source, snapshots, pilot, baseline);
        if (defect.equals("same-reference")) qualification.set("baseline", qualification.path("pilot"));
        else ((ObjectNode) qualification.path("baseline")).set(defect.equals("same-run") ? "runId" : "digest",
            qualification.path("pilot").path(defect.equals("same-run") ? "runId" : "digest"));
        assertThatThrownBy(() -> M8ModelQualification.verifyReferences(qualification, source, snapshots, pilot, pilot))
            .hasMessageContaining("distinct pilot and baseline");
        var manifest = manifest(qualification); ((ObjectNode) manifest.path("versions")).put("criteria", "1.1.0");
        assertThatThrownBy(() -> M8ModelQualification.preflight(qualification, manifest, List.of(CELL), Instant.now()))
            .hasMessageContaining("distinct pilot and baseline");
    }

    @Test void missingBaselineNeverBecomesAComparisonPass() throws Exception {
        var archive = archive(); var result = archive.calculate();
        var summary = M8ModelQualification.summarize(result.path("rows"), archive.qualification(),
            archive.source(), archive.plan().path("snapshots"), null);
        assertThat(summary.path("cohorts")).allSatisfy(c -> {
            assertThat(c.path("baselineComparable").asBoolean()).isFalse();
            assertThat(c.path("status").asString()).isEqualTo("INCOMPLETE");
        });
    }

    @Test void criteriaApprovalChangesFullIdentityButPreservesTheExecutionComparison() {
        var pilot = manifest(plan(List.of(CELL))); var qualification = pilot.deepCopy();
        ((ObjectNode) qualification.path("versions")).put("criteria", "1.1.0");
        ((ObjectNode) qualification.path("provider")).put("termsCheckedAt", Instant.now().toString());
        var pilotSource = configSource(pilot); var qualificationSource = configSource(qualification);
        var before = JSON.createObjectNode(); before.putObject("criteria.json").put("digest", "a".repeat(64));
        var after = JSON.createObjectNode(); after.putObject("criteria.json").put("digest", "b".repeat(64));
        assertThat(M8ModelQualification.identity(pilotSource, before)).isNotEqualTo(M8ModelQualification.identity(qualificationSource, after));
        assertThat(M8ModelQualification.comparisonIdentity(pilotSource, before)).isEqualTo(M8ModelQualification.comparisonIdentity(qualificationSource, after));
        var plan = qualificationPlan();
        var pilotRef = referenceVector(plan.path("pilot"), pilotSource, before);
        var baselineRef = referenceVector(plan.path("baseline"), pilotSource, before);
        M8ModelQualification.verifyReferences(plan, qualificationSource, after, pilotRef, baselineRef);
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "api", "endpoint", "prompt", "schema", "support", "runtime", "router", "prompt-bytes", "schema-bytes", "scorer-bytes"})
    void actualExecutionChangesInvalidateReferenceComparison(String change) {
        var plan = qualificationPlan(); var manifest = manifest(plan); var snapshots = JSON.createObjectNode();
        var source = configSource(manifest); var pilot = referenceVector(plan.path("pilot"), source, snapshots);
        var baseline = referenceVector(plan.path("baseline"), source, snapshots);
        switch (change) {
            case "model" -> ((ObjectNode) manifest.path("provider")).putArray("models").add("different-model");
            case "api", "endpoint" -> ((ObjectNode) manifest.path("provider")).put(change, "changed");
            case "prompt", "schema", "support" -> ((ObjectNode) manifest.path("versions")).put(change, "changed");
            case "runtime" -> ((ObjectNode) manifest.path("configuration")).put("runtimeLifetimeSeconds", 300);
            case "router" -> snapshots.putObject("router-profile").put("digest", "b".repeat(64));
            case "prompt-bytes", "schema-bytes", "scorer-bytes" -> source.put(change.replace("-bytes", "Digest"), "b".repeat(64));
        }
        source.put("configDigest", digest(JSON.writeValueAsBytes(manifest)))
            .put("executionConfigDigest", M8ModelQualification.executionConfigDigest(manifest));
        assertThatThrownBy(() -> M8ModelQualification.verifyReferences(plan, source, snapshots, pilot, baseline))
            .hasMessageContaining("not directly comparable");
    }

    @Test void comparisonDigestCannotReplaceFullManifestIntegrity() throws Exception {
        var archive = archive(); var snapshots = (ObjectNode) archive.plan().path("snapshots");
        archive.source().put("executionConfigDigest", "0".repeat(64));
        replace(archive.root(), snapshots, "source-build", archive.source());
        // Reference comparison is intentionally updated too: the independent manifest projection must still reject it.
        for (String name : List.of("pilot", "baseline")) replace(archive.root(), snapshots, "reference-" + name,
            referenceVector(archive.qualification().path(name), archive.source(), snapshots));
        Files.write(archive.root().resolve("plan.json"), JSON.writeValueAsBytes(archive.plan()));
        assertThatThrownBy(archive::calculate).hasMessageContaining("execution configuration identity mismatch");
    }

    static ObjectNode configSource(JsonNode manifest) {
        return JSON.createObjectNode().put("configDigest", digest(JSON.writeValueAsBytes(manifest)))
            .put("executionConfigDigest", M8ModelQualification.executionConfigDigest(manifest));
    }

    record Archive(Path root, ObjectNode plan, ObjectNode qualification, ObjectNode source) {
        JsonNode calculate() throws Exception { return M8Evaluation.calculate(root, seal(root, false)); }
    }

    // Promote a fabricated PILOT archive only inside this scorer test; never publish a latest-model pointer.
    static Archive archive() throws Exception {
        var pilotPlan = plan(List.of(CELL));
        var session = pilotArchive(pilotPlan, manifest(pilotPlan));
        Path root = session.directory(); var frozen = readNode(root, "plan.json");
        var snapshots = (ObjectNode) frozen.path("snapshots"); var qualification = qualificationPlan();
        qualification.withObject("providerPreflight").set("checkedAt", pilotPlan.path("providerPreflight").path("checkedAt"));
        var manifest = (ObjectNode) frozen.path("manifest"); ((ObjectNode) manifest.path("versions")).put("criteria", "1.1.0");
        replace(root, snapshots, "manifest", manifest);
        replace(root, snapshots, "criteria.json", qualification.path("criteria"));
        var source = readNode(root, snapshots.path("source-build").path("path").asString());
        source.put("sourceDirty", false).put("configDigest", snapshots.path("manifest").path("digest").asString());
        replace(root, snapshots, "source-build", source);
        qualification.set("frozenIdentity", M8ModelQualification.identity(source, snapshots));
        replace(root, snapshots, "qualification", qualification);
        for (String name : List.of("pilot", "baseline")) {
            replace(root, snapshots, "reference-" + name, referenceVector(qualification.path(name), source, snapshots));
        }
        frozen.put("phase", "QUALIFICATION"); Files.write(root.resolve("plan.json"), JSON.writeValueAsBytes(frozen));
        return new Archive(root, frozen, qualification, source);
    }

    static M8Evaluation pilotArchive(ObjectNode pilotPlan, ObjectNode manifest) throws Exception {
        String runId = "scorer-qualification-vector-" + UUID.randomUUID();
        var session = new M8Evaluation(runId, List.of(CELL), manifest, M8ModelQualification.budget(pilotPlan),
            c -> M8Bindings.create(c, runId, M8EvaluationTests.BASE, Map.of()), null, pilotPlan);
        session.execute((cell, input, recorder) -> {
            var raw = M8EvaluationTests.vector(M8Evaluation.item(cell.caseId()), recorder.binding());
            ((ObjectNode) raw.get("result")).put("observationKind", "ACTUAL_PROVIDER_SYNTHETIC_FIXTURE");
            raw.put("provider-call-a", journal("", true));
            raw.forEach((id, value) -> M8EvaluationTests.put(recorder, id, value));
            var review = JSON.createObjectNode().put("runId", runId).put("rubricVersion", "m8-answer-v1")
                .put("state", "APPROVED").put("reviewer", "synthetic-scorer-test-only").put("reviewedAt", Instant.now().toString())
                .put("requestDigest", digest(JSON.writeValueAsBytes(raw.get("request"))))
                .put("answerDigest", digest(JSON.writeValueAsBytes(raw.get("answer"))));
            review.set("cell", JSON.valueToTree(cell)); var findings = review.putObject("findings");
            for (String name : strings(read("review-rubric.json"), "required")) findings.put(name, true);
            recorder.put("review", review);
        });
        return session;
    }

    static ObjectNode referenceVector(JsonNode ref, JsonNode source, JsonNode snapshots) {
        var reference = JSON.createObjectNode(); reference.putObject("completion").put("endedAt", "2026-10-19T00:00:00Z");
        var results = reference.putObject("results").put("runId", ref.path("runId").asString()).put("evidenceDigest", ref.path("digest").asString());
        results.putObject("integrity"); var quality = results.putObject("modelQualification").put("phase", "PILOT");
        quality.set("comparisonIdentity", M8ModelQualification.comparisonIdentity(source, snapshots));
        var row = results.putArray("rows").addObject(); row.set("cell", JSON.valueToTree(CELL));
        row.put("observationKind", "ACTUAL_PROVIDER_SYNTHETIC_FIXTURE"); row.putObject("providerObservation").put("calls", 1);
        row.withObject("checks").putObject("evidence_lane").put("status", "PASS");
        return reference;
    }

    static ObjectNode readNode(Path root, String path) throws Exception {
        return (ObjectNode) JSON.readTree(Files.readAllBytes(root.resolve(path)));
    }

    static void replace(Path root, ObjectNode references, String id, JsonNode value) throws Exception {
        String path = references.path(id).path("path").asString("snapshots/" + id + ".json");
        byte[] bytes = JSON.writeValueAsBytes(value); Files.write(root.resolve(path), bytes);
        references.set(id, JSON.valueToTree(Map.of("path", path, "bytes", bytes.length, "digest", digest(bytes))));
    }

    static String seal(Path root, boolean preserveMissing) throws Exception {
        var hashes = preserveMissing ? readNode(root, "seal.json") : JSON.createObjectNode();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String path = root.relativize(file).toString().replace('\\', '/');
                if (Set.of("seal.json", "evidence.sha256", "results.json", "summary.ko.md").contains(path) || path.startsWith("test-report/")) continue;
                hashes.put(path, digest(Files.readAllBytes(file)));
            }
        }
        byte[] bytes = JSON.writeValueAsBytes(hashes); Files.write(root.resolve("seal.json"), bytes);
        String sealDigest = digest(bytes); Files.writeString(root.resolve("evidence.sha256"), sealDigest); return sealDigest;
    }
}
