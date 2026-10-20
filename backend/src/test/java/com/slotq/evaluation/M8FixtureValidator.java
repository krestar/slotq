package com.slotq.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Offline contract validation only. Does not execute cases or score model/Product results. */
public final class M8FixtureValidator {
    static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static final String ROOT = "evaluation/m8-v1/";
    static final Set<String> AXES = Set.of("tool_selection", "parameter_extraction", "retrieval_quality",
        "execution_success", "forbidden_action");
    static final Set<String> SPLITS = Set.of("CALIBRATION", "REGRESSION", "HISTORICAL_HOLDOUT", "HOLDOUT");
    static final Set<String> LANES = Set.of("DETERMINISTIC", "ACTUAL_PROVIDER_FIXTURE", "ACTUAL_PROVIDER_FLOW",
        "FAULT_INJECTION", "HISTORICAL", "RECALCULATION", "NOT_EXECUTED");

    public static void main(String[] args) {
        validateDataset(read("dataset.json"), read("case-history.json"), M8FixtureValidator::resource);
        var criteria = read("criteria.json");
        M8RunContract.validateCriteria(criteria);
        M8EnvironmentContract.validateSet(List.of(read("manifest-regression.json"), read("manifest-pre-release.json")));
        validateRubric(read("review-rubric.json"));
        System.out.println("M8 fixture/manifest contract valid; no evaluation or provider call executed");
    }

    public static JsonNode read(String file) {
        return JSON.readTree(resource(ROOT + file));
    }

    static byte[] resource(String path) {
        require(path.matches("[a-zA-Z0-9/._-]+") && !path.contains(".."), "unsafe resource path");
        try (var input = M8FixtureValidator.class.getResourceAsStream("/" + path)) {
            require(input != null, "missing resource: " + path);
            return input.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("unreadable resource: " + path, e);
        }
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] normalized(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static void validateDataset(JsonNode dataset, JsonNode history, Function<String, byte[]> resources) {
        fields(dataset, "version oracleVersion dataClass sources scope cases denominators bindings");
        version(dataset, "version");
        version(dataset, "oracleVersion");
        equal(dataset, "dataClass", "SYNTHETIC");
        fields(dataset.path("bindings"), "uuid time");
        text(dataset.path("bindings"), "uuid");
        text(dataset.path("bindings"), "time");
        var sources = new HashMap<String, JsonNode>();
        var imported = new HashMap<String, JsonNode>();
        for (var source : array(dataset, "sources")) {
            fields(source, "id path revision selector sha256");
            String id = text(source, "id");
            require(!sources.containsKey(id), "duplicate source ID");
            text(source, "revision");
            String path = text(source, "path");
            require(path.startsWith("knowledge/") || path.startsWith("model-router/")
                || path.startsWith("representative-flows/"), "unsupported legacy path");
            byte[] bytes = resources.apply(path);
            require(digest(normalized(bytes)).equals(text(source, "sha256")), "legacy source digest changed: " + id);
            JsonNode original = JSON.readTree(bytes);
            sources.put(id, source);
            String selector = text(source, "selector");
            if (selector.equals("reference")) continue;
            require(Set.of("queries", "cases").contains(selector), "unsupported legacy selector");
            String revision = original.has("revision") ? original.path("revision").asString() : original.path("version").asString();
            require(revision.equals(text(source, "revision")), "legacy version mismatch");
            for (var item : array(original, selector)) {
                require(imported.put(id + "/" + text(item, "id"), item) == null, "duplicate legacy case");
            }
        }
        require(sources.keySet().equals(Set.of("m6", "m7-router", "m7-flow", "m6-seed", "m6-extra",
            "m6-other", "m7-review", "m7-historical")), "required legacy sources missing");
        validateScopeRegistry(dataset.path("scope"));
        var ids = new HashSet<String>();
        var usedImports = new HashSet<String>();
        var cases = array(dataset, "cases");
        for (var item : cases) {
            validateCase(item, dataset.path("scope"));
            String id = text(item, "id");
            require(ids.add(id), "duplicate case ID: " + id);
            JsonNode origin = item.path("origin");
            fields(origin, "source caseId");
            if (text(origin, "source").equals("new")) {
                require(id.startsWith("m8/") && text(origin, "caseId").equals(id), "new case identity mismatch");
            } else {
                String key = text(origin, "source") + "/" + text(origin, "caseId");
                require(key.equals(id) && imported.containsKey(key), "bad legacy reference");
                require(usedImports.add(key), "legacy case counted twice");
                validateLegacy(item, imported.get(key));
            }
        }
        require(usedImports.equals(imported.keySet()), "mandatory legacy cases missing");
        validateHistory(history, cases);
        JsonNode denominators = dataset.path("denominators");
        require(denominators.propertyNames().equals(SPLITS), "split denominator missing");
        for (String split : SPLITS) {
            JsonNode counts = denominators.path(split);
            require(counts.propertyNames().equals(AXES), "axis denominator missing");
            for (String axis : AXES) {
                long expected = cases.stream().filter(c -> split.equals(c.path("split").asString())
                    && strings(c, "axes").contains(axis)).count();
                require(counts.path(axis).isIntegralNumber() && counts.path(axis).asLong() == expected,
                    "incorrect planned denominator: " + split + "/" + axis);
            }
        }
        rejectSensitive(dataset);
    }

    private static void validateScopeRegistry(JsonNode scope) {
        fields(scope, "tenants venues actors targets");
        Set<String> tenants = strings(scope, "tenants");
        require(tenants.size() >= 2, "two isolated tenants required");
        require(scope.path("venues").isObject() && scope.path("actors").isObject()
            && scope.path("targets").isObject(), "scope registry required");
        scope.path("venues").properties().forEach(e -> require(tenants.contains(e.getValue().asString()), "unknown venue tenant"));
        for (var actor : scope.path("actors")) {
            fields(actor, "role tenant venues customer");
            oneOf(actor, "role", Set.of("CUSTOMER", "OWNER", "MANAGER", "STAFF"));
            require(tenants.contains(text(actor, "tenant")), "unknown actor tenant");
            require(!strings(actor, "venues").isEmpty(), "actor venues missing");
            for (String venue : strings(actor, "venues")) {
                require(scope.path("venues").path(venue).asString().equals(text(actor, "tenant")), "actor venue tenant mismatch");
            }
            if (actor.path("role").asString().equals("CUSTOMER")) text(actor, "customer");
        }
        for (var target : scope.path("targets")) {
            fields(target, "tenant venue customer");
            require(tenants.contains(text(target, "tenant")) && scope.path("venues").path(text(target, "venue"))
                .asString().equals(text(target, "tenant")), "target venue tenant mismatch");
            String customer = target.path("customer").asString();
            if (!customer.isEmpty()) {
                JsonNode actor = scope.path("actors").path(customer);
                require(actor.path("role").asString().equals("CUSTOMER")
                    && actor.path("tenant").equals(target.path("tenant")), "unknown target customer");
            }
        }
    }

    private static void validateCase(JsonNode item, JsonNode registry) {
        fields(item, "id version origin split required workload scope input axes parameterOrigin lanes oracle serverPlan humanReview rubric basis fixture");
        require(text(item, "id").matches("[a-z0-9/-]+"), "invalid case ID");
        version(item, "version");
        oneOf(item, "split", SPLITS);
        require(bool(item, "required"), "v1 mandatory case disabled");
        oneOf(item, "workload", Set.of("customer", "management", "ops"));
        text(item, "input");
        text(item, "basis");
        require(item.path("fixture").isObject() && !item.path("fixture").isEmpty(), "synthetic input/setup fixture required");
        if (item.path("fixture").has("sourceFixtures")) {
            require(Set.of("m6-seed", "m6-extra", "m6-other").containsAll(strings(item.path("fixture"), "sourceFixtures")), "invalid fixture source reference");
        }
        Set<String> axes = strings(item, "axes");
        require(!axes.isEmpty() && AXES.containsAll(axes), "invalid axis");
        Set<String> lanes = strings(item, "lanes");
        require(!lanes.isEmpty() && Set.of("DETERMINISTIC", "ACTUAL_PROVIDER_FIXTURE", "ACTUAL_PROVIDER_FLOW").containsAll(lanes), "invalid required lane");
        oneOf(item, "parameterOrigin", Set.of("NONE", "MODEL_PROPOSAL", "SERVER_PLAN"));
        require(axes.contains("parameter_extraction") == item.path("parameterOrigin").asString().equals("MODEL_PROPOSAL"),
            "server Plan cannot count as model extraction");
        boolean review = bool(item, "humanReview");
        require(item.path("rubric").isString() && (review ? item.path("rubric").asString().equals("m8-answer-v1")
            : item.path("rubric").asString().isEmpty()), "missing or invalid review rubric");
        validateOracle(item.path("oracle"));
        validateScope(item, registry);
        validatePlan(item.path("serverPlan"));
        require(item.path("serverPlan").isEmpty() || !axes.contains("parameter_extraction"), "fixed Plan extraction denominator");
    }

    private static void validateScope(JsonNode item, JsonNode registry) {
        JsonNode scope = item.path("scope");
        fields(scope, "actor tenant venue customer target boundary");
        JsonNode actor = registry.path("actors").path(text(scope, "actor"));
        require(actor.isObject(), "unknown actor");
        require(actor.path("tenant").equals(scope.path("tenant"))
            && actor.path("customer").equals(scope.path("customer"))
            && strings(actor, "venues").contains(text(scope, "venue")), "invalid trusted actor scope");
        String boundary = text(scope, "boundary");
        require(Set.of("WITHIN_SCOPE", "CROSS_TENANT", "CROSS_VENUE", "CROSS_CUSTOMER", "ROLE_DENIED").contains(boundary), "invalid boundary");
        String targetId = scope.path("target").asString();
        JsonNode target = registry.path("targets").path(targetId);
        if (!targetId.isEmpty()) {
            require(target.isObject(), "unknown target reference");
            String actual = !target.path("tenant").equals(scope.path("tenant")) ? "CROSS_TENANT"
                : !target.path("venue").equals(scope.path("venue")) ? "CROSS_VENUE"
                : actor.path("role").asString().equals("CUSTOMER") && !target.path("customer").asString().isEmpty()
                    && !target.path("customer").equals(scope.path("customer")) ? "CROSS_CUSTOMER" : "WITHIN_SCOPE";
            if (!boundary.equals("ROLE_DENIED")) require(actual.equals(boundary), "scope boundary misclassified");
        } else require(boundary.equals("WITHIN_SCOPE"), "cross-scope case requires exact target");
        boolean roleDenied = item.path("workload").asString().equals("ops") && actor.path("role").asString().equals("STAFF");
        require(!roleDenied || boundary.equals("ROLE_DENIED"), "existing Ops role restriction missing");
        if (!boundary.equals("WITHIN_SCOPE")) {
            JsonNode oracle = item.path("oracle");
            equal(oracle.path("proposal"), "disposition", "REJECT");
            equal(oracle.path("execution"), "invocation", "NOT_DISPATCHED");
            equal(oracle.path("execution"), "db", "UNCHANGED");
        }
    }

    static void validateOracle(JsonNode oracle) {
        fields(oracle, "proposal attribution retrieval execution forbidden");
        JsonNode proposal = oracle.path("proposal");
        fields(proposal, "disposition tool target partySize outcome");
        oneOf(proposal, "disposition", Set.of("PROPOSE_HOLD", "ANSWER", "CLARIFY", "INSUFFICIENT", "REJECT"));
        oneOf(proposal, "outcome", Set.of("NOT_APPLICABLE", "HELD", "REJECTED", "UNKNOWN"));
        if (proposal.path("disposition").asString().equals("PROPOSE_HOLD")) {
            equal(proposal, "tool", "reservation.hold");
            text(proposal, "target");
            positive(proposal, "partySize");
            require(proposal.path("partySize").asInt() <= 20, "invalid party size");
            equal(proposal, "outcome", "NOT_APPLICABLE");
        } else {
            equal(proposal, "tool", "none");
            equal(proposal, "target", "");
            require(proposal.path("partySize").isIntegralNumber() && proposal.path("partySize").asInt() == 0, "non-proposal arguments");
        }
        JsonNode retrieval = oracle.path("retrieval");
        fields(retrieval, "category sources");
        oneOf(retrieval, "category", Set.of("NOT_APPLICABLE", "evidence", "insufficient", "no_answer", "denied", "unavailable"));
        var referenceIds = new HashSet<String>();
        for (var ref : array(retrieval, "sources")) {
            fields(ref, "document versionId sourceId");
            for (String key : List.of("document", "versionId", "sourceId")) UUID.fromString(text(ref, key));
            require(referenceIds.add(text(ref, "versionId")), "duplicate retrieval reference");
        }
        require(!retrieval.path("category").asString().equals("evidence") || !referenceIds.isEmpty(), "missing retrieval oracle");
        validateAttribution(oracle.path("attribution"));
        JsonNode execution = oracle.path("execution");
        fields(execution, "invocation effect db reconciliation");
        oneOf(execution, "invocation", Set.of("NOT_DISPATCHED", "RESPONSE_OBSERVED", "UNKNOWN"));
        oneOf(execution, "effect", Set.of("NOT_APPLICABLE", "SUCCEEDED", "REJECTED", "UNKNOWN"));
        oneOf(execution, "db", Set.of("NOT_OBSERVED", "UNCHANGED", "HOLD_ATOMIC"));
        oneOf(execution, "reconciliation", Set.of("NOT_REQUESTED", "EXACT_GET_HELD", "UNKNOWN_TARGET", "GET_404_UNRESOLVED"));
        String effect = execution.path("effect").asString();
        require(!effect.equals("SUCCEEDED") || execution.path("db").asString().equals("HOLD_ATOMIC"), "success requires authoritative atomic state");
        require(!effect.equals("REJECTED") || execution.path("db").asString().equals("UNCHANGED"), "business rejection effects");
        require(!execution.path("invocation").asString().equals("UNKNOWN") || effect.equals("UNKNOWN"), "unknown invocation relabeled");
        require(!effect.equals("UNKNOWN") || proposal.path("outcome").asString().equals("UNKNOWN"), "mutation uncertainty erased");
        require(!effect.equals("SUCCEEDED") || proposal.path("outcome").asString().equals("HELD"), "HOLD is not CONFIRMED");
        JsonNode forbidden = oracle.path("forbidden");
        fields(forbidden, "disclosure dispatch partialCommit capacityExceeded");
        for (String key : forbidden.propertyNames()) require(!bool(forbidden, key), "forbidden expected effect");
    }

    static void validateAttribution(JsonNode attribution) {
        fields(attribution, "requiredSources allowedSources allowedSetOrigin requiredClaims allowedClaims claimSources");
        oneOf(attribution, "allowedSetOrigin", Set.of("STATIC", "REQUEST_CONTEXT"));
        Set<String> requiredSources = strings(attribution, "requiredSources");
        Set<String> allowedSources = strings(attribution, "allowedSources");
        Set<String> requiredClaims = strings(attribution, "requiredClaims");
        Set<String> allowedClaims = strings(attribution, "allowedClaims");
        boolean dynamic = attribution.path("allowedSetOrigin").asString().equals("REQUEST_CONTEXT");
        require(dynamic ? allowedSources.isEmpty() && allowedClaims.isEmpty()
            : allowedSources.containsAll(requiredSources) && allowedClaims.containsAll(requiredClaims), "required/allowed attribution sets");
        JsonNode links = attribution.path("claimSources");
        require(links.isObject(), "claim/source correspondence required");
        for (var entry : links.properties()) {
            require((dynamic ? requiredClaims : allowedClaims).contains(entry.getKey()), "unknown linked claim");
            var refs = strings(links, entry.getKey());
            require(!refs.isEmpty() && (dynamic ? requiredSources : allowedSources).containsAll(refs), "unknown linked source");
        }
        for (String claim : dynamic ? requiredClaims : allowedClaims) {
            if (claim.startsWith("knowledge:")) require(links.has(claim), "knowledge claim requires its own source");
        }
    }

    static void validatePlan(JsonNode plan) {
        require(plan.isObject(), "server Plan required");
        for (var entry : plan.properties()) {
            var args = entry.getValue();
            switch (entry.getKey()) {
                case "management.reservations.list" -> {
                    fields(args, "date");
                    LocalDate.parse(text(args, "date"));
                }
                case "management.event-observations.list" -> {
                    fields(args, "from to limit");
                    Instant from = Instant.parse(text(args, "from"));
                    Instant to = Instant.parse(text(args, "to"));
                    require(from.isBefore(to) && java.time.Duration.between(from, to).compareTo(java.time.Duration.ofDays(31)) <= 0, "invalid bounded time window");
                    positive(args, "limit");
                    require(args.path("limit").asInt() <= 100, "observation bound");
                }
                case "knowledge.search" -> {
                    fields(args, "query limit");
                    require(text(args, "query").length() <= 200, "knowledge query bound");
                    require(positive(args, "limit") <= 5, "knowledge limit");
                }
                case "reservation.get" -> {
                    fields(args, "reservationId");
                    text(args, "reservationId");
                }
                default -> throw invalid("unsupported fixed Plan tool");
            }
        }
    }

    private static void validateLegacy(JsonNode item, JsonNode original) {
        require(item.path("required").asBoolean(), "legacy required case disabled");
        String source = item.path("origin").path("source").asString();
        JsonNode expected = item.path("oracle");
        JsonNode attribution = expected.path("attribution");
        if (source.equals("m6")) {
            equal(item, "input", original.path("query").asString());
            equal(expected.path("retrieval"), "category", original.path("category").asString());
            var refs = array(expected.path("retrieval"), "sources");
            require(refs.size() == (original.has("document") ? 1 : 0), "M6 original reference omitted");
            if (original.has("document")) {
                for (String key : List.of("document", "versionId", "sourceId")) {
                    require(refs.getFirst().path(key).equals(original.path(key)), "M6 original oracle changed");
                }
            }
            String scenario = original.path("scenario").asString();
            equal(item.path("scope"), "actor", scenario.equals("tenant-b") ? "customer-b" : scenario.equals("operator") ? "owner-a" : "customer-a");
            require(strings(item, "axes").equals(Set.of("retrieval_quality", "forbidden_action")), "M6 evaluation meaning changed");
        } else if (source.equals("m7-router")) {
            equal(item, "input", original.path("input").asString());
            require(item.path("fixture").equals(original.path("fixture")), "M7 supplied fixture changed");
            JsonNode oracle = original.path("oracle");
            JsonNode proposal = expected.path("proposal");
            for (String key : List.of("disposition", "target", "partySize", "outcome")) {
                require(proposal.path(key).equals(oracle.path(key)), "M7 original proposal changed");
            }
            require(strings(attribution, "requiredSources").equals(strings(oracle, "sources")), "M7 required sources changed");
            require(strings(attribution, "allowedSources").equals(strings(oracle, oracle.has("allowedSources") ? "allowedSources" : "sources")), "M7 allowed sources changed");
            require(strings(attribution, "requiredClaims").equals(strings(oracle, "required"))
                && strings(attribution, "allowedClaims").equals(strings(oracle, "allowed")), "M7 claims changed");
            equal(attribution, "allowedSetOrigin", "STATIC");
            JsonNode knowledge = original.path("fixture").path("knowledge");
            var links = new TreeMap<String, Set<String>>();
            if (knowledge.has("fact")) links.put(knowledge.path("fact").asString(), Set.of(knowledge.path("source").asString()));
            require(JSON.valueToTree(links).equals(attribution.path("claimSources")), "M7 claim/source correspondence changed");
            equal(item, "split", original.path("holdout").asBoolean() ? "HISTORICAL_HOLDOUT" : "REGRESSION");
            require(strings(item, "axes").equals(Set.of("tool_selection", "parameter_extraction", "forbidden_action")), "M7 supplied context is not retrieval/execution evidence");
        } else if (source.equals("m7-flow")) {
            equal(expected.path("proposal"), "disposition", original.path("disposition").asString());
            require(strings(attribution, "requiredSources").equals(strings(original, "requiredSources"))
                && strings(attribution, "requiredClaims").equals(strings(original, "requiredClaims")), "M7 flow mandatory oracle changed");
            equal(attribution, "allowedSetOrigin", "REQUEST_CONTEXT");
            require(bool(item, "humanReview"), "integrated answer needs content review");
        } else throw invalid("unsupported legacy source");
        if (!source.equals("m6")) equal(item, "workload", original.path("workload").asString());
    }

    private static void validateHistory(JsonNode history, List<JsonNode> cases) {
        fields(history, "version entries changes");
        version(history, "version");
        var entries = new HashMap<String, JsonNode>();
        for (var entry : array(history, "entries")) {
            fields(entry, "id version split everCalibrated required fixtureSha256");
            version(entry, "version");
            require(entries.put(text(entry, "id"), entry) == null, "duplicate history case");
            boolean tuned = bool(entry, "everCalibrated");
            require(!entry.path("split").asString().equals("CALIBRATION") || tuned, "calibration usage cannot disappear from history");
            require(!tuned || !Set.of("HOLDOUT", "HISTORICAL_HOLDOUT").contains(text(entry, "split")), "tuned case is not independent holdout");
        }
        require(entries.size() == cases.size(), "case history coverage");
        for (var item : cases) {
            var previous = entries.get(text(item, "id"));
            require(previous != null && previous.path("version").equals(item.path("version"))
                && previous.path("split").equals(item.path("split"))
                && previous.path("required").equals(item.path("required")), "case identity/split history changed");
            require(text(previous, "fixtureSha256").equals(digest(JSON.writeValueAsBytes(canonical(item)))), "frozen fixture/oracle changed without history");
        }
        require(!array(history, "changes").isEmpty(), "change history required");
        for (var change : array(history, "changes")) {
            fields(change, "version previous reason");
            version(change, "version");
            text(change, "reason");
        }
    }

    static void validateRubric(JsonNode rubric) {
        fields(rubric, "version required identity states basis");
        equal(rubric, "version", "m8-answer-v1");
        require(strings(rubric, "required").equals(Set.of("unsupported_claim", "outcome_distortion", "policy_violation",
            "source_claim_correspondence", "partial_observation_limits")), "review requirements changed");
        require(strings(rubric, "identity").equals(Set.of("caseId", "runId", "repeat", "model", "lane", "requestDigest",
            "answerDigest", "rubricVersion", "reviewer", "reviewedAt")), "review identity incomplete");
        require(strings(rubric, "states").equals(Set.of("PENDING", "APPROVED", "REJECTED")), "review state");
        text(rubric, "basis");
    }

    static void rejectSensitive(JsonNode node) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                require(!Set.of("credential", "apiKey", "password", "idempotencyKey", "authorization", "customerEmail",
                    "customerPhone", "rawPrompt", "rawResponse").contains(entry.getKey()), "sensitive field: " + entry.getKey());
                rejectSensitive(entry.getValue());
            }
        } else if (node.isArray()) node.forEach(M8FixtureValidator::rejectSensitive);
        else if (node.isString()) {
            String value = node.asString();
            require(!value.matches("(?s).*(?:Bearer\\s+[A-Za-z0-9._-]+|AIza[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9_-]{16,}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|010[- ]?[0-9]{4}[- ]?[0-9]{4}|://[^/\\s]+:[^/\\s]+@|-----BEGIN [A-Z ]*PRIVATE KEY-----).*"), "sensitive value");
        }
    }

    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, Object>();
            node.properties().forEach(e -> sorted.put(e.getKey(), canonical(e.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            var values = new ArrayList<Object>();
            node.forEach(value -> values.add(canonical(value)));
            return values;
        }
        return node;
    }

    static void fields(JsonNode node, String names) {
        require(node.isObject() && node.propertyNames().equals(Set.of(names.split(" "))), "required/unknown fields: " + names);
    }

    static String text(JsonNode node, String key) {
        require(node.path(key).isString() && !node.path(key).asString().isBlank(), "missing text: " + key);
        return node.path(key).asString();
    }

    static List<JsonNode> array(JsonNode node, String key) {
        require(node.path(key).isArray(), "missing array: " + key);
        var result = new ArrayList<JsonNode>();
        node.path(key).forEach(result::add);
        return result;
    }

    static Set<String> strings(JsonNode node, String key) {
        var result = new HashSet<String>();
        for (var value : array(node, key)) {
            require(value.isString() && !value.asString().isBlank() && result.add(value.asString()), "duplicate/invalid reference: " + key);
        }
        return result;
    }

    static boolean bool(JsonNode node, String key) {
        require(node.path(key).isBoolean(), "missing boolean: " + key);
        return node.path(key).asBoolean();
    }

    static int positive(JsonNode node, String key) {
        require(node.path(key).isIntegralNumber() && node.path(key).asLong() > 0 && node.path(key).asLong() <= Integer.MAX_VALUE, "positive integer: " + key);
        return node.path(key).asInt();
    }

    static void version(JsonNode node, String key) {
        equal(node, key, "1.0.0");
    }

    static void equal(JsonNode node, String key, String expected) {
        require(node.path(key).isString() && node.path(key).asString().equals(expected), "invalid " + key + ": expected " + expected);
    }

    static void oneOf(JsonNode node, String key, Set<String> values) {
        require(node.path(key).isString() && values.contains(node.path(key).asString()), "invalid " + key);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("M8 contract: " + message);
    }
}
