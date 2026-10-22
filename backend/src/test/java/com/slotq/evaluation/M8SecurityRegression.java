package com.slotq.evaluation;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;

/** Coverage adapter for executed security assertions, sharing #179 marks, cohorts and report format. */
public final class M8SecurityRegression {
    static final String CATALOG = "evaluation/security-v1/cases.json";
    static final Set<String> REQUIRED = Set.of(
        "product-auth", "reservation-roles", "waitlist-roles", "management-scope", "credential-audience",
        "product-material", "current-authority", "run-identity-budget", "exact-approval", "untrusted-instructions",
        "retrieval-prescope", "publication-revalidation", "provider-predispatch", "staff-and-ops-authority",
        "ops-observation", "redaction", "runtime-configuration", "architecture", "evaluator-observations"
    );

    static JsonNode catalog() throws Exception {
        try (var input = M8SecurityRegression.class.getClassLoader().getResourceAsStream(CATALOG)) {
            require(input != null, "security catalog missing");
            return JSON.readTree(input.readAllBytes());
        }
    }

    static Set<String> selectors(JsonNode catalog) {
        var cases = new HashSet<String>();
        var tests = new TreeSet<String>();
        require(text(catalog, "version").equals("1.0.0"), "security catalog version");
        text(catalog, "profile");
        for (var item : array(catalog, "cases")) {
            String id = text(item, "id");
            require(cases.add(id), "duplicate security case");
            for (String field : List.of("actor", "credential", "scope", "entry", "expected", "observations")) text(item, field);
            var local = new HashSet<String>();
            for (var test : array(item, "tests")) {
                require(test.isString() && test.asString().matches("com\\.slotq\\.[A-Za-z0-9_.]+\\.[A-Za-z0-9_]+"), "invalid test selector");
                require(local.add(test.asString()), "duplicate test in security case");
                tests.add(test.asString());
            }
            require(!local.isEmpty(), "unobserved security case");
        }
        require(cases.equals(REQUIRED.stream().map(id -> "security/" + id).collect(java.util.stream.Collectors.toSet())), "mandatory security coverage missing/unknown");
        return tests;
    }

    static JsonNode observe(Path directory, Set<String> selectors) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var tests = JSON.createObjectNode();
        var digests = JSON.createObjectNode();
        List<Path> junitFiles = List.of();
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                junitFiles = files.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).sorted().toList();
            }
        }
        for (var file : junitFiles) {
            byte[] bytes = Files.readAllBytes(file);
            digests.put(file.getFileName().toString(), digest(bytes));
            var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
            var cases = document.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                var item = (org.w3c.dom.Element) cases.item(i);
                String name = item.getAttribute("name").split("[\\[(]", 2)[0];
                String selector = item.getAttribute("classname") + "." + name;
                if (!selectors.contains(selector)) continue;
                String status = item.getElementsByTagName("failure").getLength() > 0 || item.getElementsByTagName("error").getLength() > 0
                    ? "FAIL" : item.getElementsByTagName("skipped").getLength() > 0 ? "INCOMPLETE" : "PASS";
                if (tests.has(selector)) status = "FAIL";
                // Do not copy exception messages, system-out, credentials or provider text from JUnit XML.
                tests.putObject(selector).put("status", status).put("observed", true);
            }
        }
        var evidence = JSON.createObjectNode();
        evidence.set("tests", tests);
        evidence.set("junitDigests", digests);
        return evidence;
    }

    static JsonNode calculate(JsonNode catalog, JsonNode evidence) {
        selectors(catalog);
        require(digest(JSON.writeValueAsBytes(catalog)).equals(text(evidence, "catalogDigest")), "security catalog digest mismatch");
        var rows = JSON.createArrayNode();
        for (var item : array(catalog, "cases")) {
            var row = JSON.createObjectNode();
            row.putObject("cell").put("caseId", text(item, "id")).put("caseVersion", text(catalog, "version"))
                .put("repeat", 1).put("model", "no-provider-security-regression").put("lane", "DETERMINISTIC");
            row.put("required", true).put("denominator", 1).put("observationKind", "EXECUTED_SECURITY_ASSERTIONS")
                .put("role", text(item, "actor")).put("workload", "security").put("split", "REGRESSION")
                .put("expectedOutcome", text(item, "expected")).put("expectedInvocation", "CASE_CONTRACT")
                .put("expectedEffect", "CASE_CONTRACT").put("expectedDisposition", "CASE_CONTRACT").put("expectedBoundary", text(item, "scope"));
            row.set("contract", item);
            var marks = new TreeMap<String, M8Scorer.Mark>();
            for (var test : array(item, "tests")) {
                var observed = evidence.path("tests").path(test.asString());
                String status = observed.path("observed").asBoolean() ? observed.path("status").asString() : "INCOMPLETE";
                require(Set.of("PASS", "FAIL", "INCOMPLETE").contains(status), "invalid security observation");
                marks.put(test.asString(), new M8Scorer.Mark(status, status.equals("PASS") ? "실제 보안 assertion 통과"
                    : status.equals("FAIL") ? "필수 테스트 실패 또는 중복 관측" : "필수 관측 누락 또는 skip"));
            }
            String status = M8Scorer.combine(marks.values().stream().map(M8Scorer.Mark::status).toList());
            row.put("automaticStatus", status).put("status", status);
            row.set("checks", JSON.valueToTree(marks));
            row.set("hardGateViolations", JSON.valueToTree(status.equals("FAIL") ? List.of("MANDATORY_SECURITY_REGRESSION") : List.of()));
            row.set("reasons", JSON.valueToTree(marks.entrySet().stream().filter(e -> !e.getValue().status().equals("PASS"))
                .map(e -> e.getKey() + ": " + e.getValue().reason()).toList()));
            rows.add(row);
        }
        String securityStatus = M8Scorer.combine(rows.valueStream().map(r -> r.path("status").asString()).toList());
        var result = JSON.createObjectNode();
        result.put("scorer", "m8-security-coverage-v1").put("runId", text(evidence, "runId")).put("plannedCells", rows.size())
            .put("criteriaState", "PENDING").put("releaseQualification", false).put("securityStatus", securityStatus)
            .put("status", securityStatus.equals("FAIL") ? "FAIL" : "INCOMPLETE");
        result.set("rows", rows);
        result.set("cohorts", M8Evaluation.aggregate(rows));
        result.put("evidenceDigest", digest(JSON.writeValueAsBytes(evidence)));
        return result;
    }

    public static void main(String[] args) throws Exception {
        JsonNode catalog = catalog();
        String runId = "security-" + UUID.randomUUID();
        Path root = M8ProtectedFiles.root(runId);
        String acl = M8ProtectedFiles.create(root);
        var evidence = (ObjectNode) observe(Path.of(System.getProperty("slotq.security.junit")), selectors(catalog));
        evidence.put("runId", runId).put("catalogDigest", digest(JSON.writeValueAsBytes(catalog)))
            .put("observedAt", Instant.now().toString()).put("retentionDays", 90).put("accessVerification", acl);
        var compiled = evidence.putObject("compiledTests");
        for (String selector : selectors(catalog)) {
            String type = selector.substring(0, selector.lastIndexOf('.'));
            try (var input = M8SecurityRegression.class.getClassLoader().getResourceAsStream(type.replace('.', '/') + ".class")) {
                require(input != null, "security test class missing");
                compiled.put(type, digest(input.readAllBytes()));
            }
        }
        Files.write(root.resolve("catalog.json"), JSON.writeValueAsBytes(catalog), StandardOpenOption.CREATE_NEW);
        Files.write(root.resolve("observations.json"), JSON.writeValueAsBytes(evidence), StandardOpenOption.CREATE_NEW);
        JsonNode result = calculate(catalog, evidence);
        M8Evaluation.writeReport(root, result);
        JsonNode recalculated = calculate(JSON.readTree(Files.readAllBytes(root.resolve("catalog.json"))),
            JSON.readTree(Files.readAllBytes(root.resolve("observations.json"))));
        require(recalculated.equals(result), "security recalculation mismatch");
        Files.writeString(root.getParent().resolve("latest-security-run.txt"), runId + "\n" + result.path("evidenceDigest").asString());
        System.out.println("보안 회귀: " + result.path("securityStatus").asString() + "; 필수 사례 " + result.path("plannedCells").asInt()
            + "; M8 출시 판정: " + result.path("status").asString());
        require(result.path("securityStatus").asString().equals("PASS"), "mandatory security coverage failed/incomplete");
    }

    private M8SecurityRegression() { }
}
