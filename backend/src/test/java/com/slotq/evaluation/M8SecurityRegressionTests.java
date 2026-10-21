package com.slotq.evaluation;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;
import static org.assertj.core.api.Assertions.*;

class M8SecurityRegressionTests {
    @TempDir Path directory;

    @Test
    void catalogCoversEveryRequiredBoundaryAndReferencesExistingTests() throws Exception {
        var catalog = M8SecurityRegression.catalog();
        for (String selector : M8SecurityRegression.selectors(catalog)) {
            String type = selector.substring(0, selector.lastIndexOf('.'));
            String method = selector.substring(selector.lastIndexOf('.') + 1);
            Class<?> test = Class.forName(type, false, getClass().getClassLoader());
            assertThat(Arrays.stream(test.getDeclaredMethods()).map(java.lang.reflect.Method::getName)).contains(method);
        }
        ((tools.jackson.databind.node.ArrayNode) catalog.path("cases")).remove(0);
        assertThatThrownBy(() -> M8SecurityRegression.selectors(catalog)).hasMessageContaining("mandatory security coverage");
    }

    @Test
    void successfulAssertionsUseM8RowsAndCohortsWithoutGrantingReleasePass() throws Exception {
        var catalog = M8SecurityRegression.catalog();
        var evidence = evidence(catalog);
        var result = M8SecurityRegression.calculate(catalog, evidence);
        assertThat(result.path("securityStatus").asString()).isEqualTo("PASS");
        assertThat(result.path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(result.path("releaseQualification").asBoolean()).isFalse();
        assertThat(result.path("rows")).hasSize(M8SecurityRegression.REQUIRED.size());
        assertThat(result.path("rows")).allSatisfy(row -> {
            assertThat(row.path("denominator").asInt()).isEqualTo(1);
            assertThat(row.path("hardGateViolations")).isEmpty();
        });
        assertThat(M8SecurityRegression.calculate(JSON.readTree(JSON.writeValueAsBytes(catalog)),
            JSON.readTree(JSON.writeValueAsBytes(evidence)))).isEqualTo(result);
    }

    @Test
    void missingSkippedFalseOrDuplicateObservationsCannotBeAveragedAway() throws Exception {
        var catalog = M8SecurityRegression.catalog();
        String first = M8SecurityRegression.selectors(catalog).iterator().next();
        for (String status : List.of("MISSING", "INCOMPLETE", "FAIL")) {
            var evidence = evidence(catalog);
            var tests = (ObjectNode) evidence.path("tests");
            if (status.equals("MISSING")) tests.remove(first);
            else tests.putObject(first).put("observed", true).put("status", status);
            var result = M8SecurityRegression.calculate(catalog, evidence);
            assertThat(result.path("securityStatus").asString()).isEqualTo(status.equals("FAIL") ? "FAIL" : "INCOMPLETE");
            assertThat(result.path("cohorts").toString()).contains("INCOMPLETE");
        }
        var changed = evidence(catalog);
        changed.put("catalogDigest", "0".repeat(64));
        assertThatThrownBy(() -> M8SecurityRegression.calculate(catalog, changed)).hasMessageContaining("digest mismatch");
    }

    @Test
    void junitImporterKeepsOnlyExecutedStatusesAndRejectsDuplicateResults() throws Exception {
        String selector = "com.slotq.SyntheticTests.denied";
        Path file = directory.resolve("TEST-synthetic.xml");
        Files.writeString(file, """
            <testsuite><testcase classname="com.slotq.SyntheticTests" name="denied()"/>
            <system-out>credential-SENTINEL prompt-SENTINEL</system-out></testsuite>
            """);
        var observed = M8SecurityRegression.observe(directory, Set.of(selector));
        assertThat(observed.path("tests").path(selector).path("status").asString()).isEqualTo("PASS");
        assertThat(observed.toString()).doesNotContain("credential-SENTINEL", "prompt-SENTINEL");
        Files.writeString(directory.resolve("TEST-duplicate.xml"), Files.readString(file));
        assertThat(M8SecurityRegression.observe(directory, Set.of(selector)).path("tests").path(selector).path("status").asString()).isEqualTo("FAIL");
    }

    @Test
    void junitSkipFailureAndUnexecutedMethodRemainDistinct() throws Exception {
        assertThat(M8SecurityRegression.observe(directory.resolve("not-executed"), Set.of("com.slotq.SyntheticTests.missing"))
            .path("tests")).isEmpty();
        Files.writeString(directory.resolve("TEST-boundaries.xml"), """
            <testsuite>
            <testcase classname="com.slotq.SyntheticTests" name="skip()"><skipped/></testcase>
            <testcase classname="com.slotq.SyntheticTests" name="fail()"><failure message="private-SENTINEL"/></testcase>
            </testsuite>
            """);
        var observed = M8SecurityRegression.observe(directory, Set.of("com.slotq.SyntheticTests.skip", "com.slotq.SyntheticTests.fail", "com.slotq.SyntheticTests.missing"));
        assertThat(observed.path("tests").path("com.slotq.SyntheticTests.skip").path("status").asString()).isEqualTo("INCOMPLETE");
        assertThat(observed.path("tests").path("com.slotq.SyntheticTests.fail").path("status").asString()).isEqualTo("FAIL");
        assertThat(observed.path("tests").has("com.slotq.SyntheticTests.missing")).isFalse();
        assertThat(observed.toString()).doesNotContain("private-SENTINEL");
    }

    private ObjectNode evidence(tools.jackson.databind.JsonNode catalog) {
        var evidence = JSON.createObjectNode().put("runId", "security-scorer-test-vector")
            .put("catalogDigest", digest(JSON.writeValueAsBytes(catalog)));
        var tests = evidence.putObject("tests");
        for (String selector : M8SecurityRegression.selectors(catalog)) tests.putObject(selector).put("observed", true).put("status", "PASS");
        return evidence;
    }
}
