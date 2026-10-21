package com.slotq.evaluation;

import com.slotq.ai.router.ProviderProtocol;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;

/** Execution-only fixture identity and an allowlisted model input projection. */
public final class M8Bindings {
    private static final tools.jackson.databind.json.JsonMapper REPLAY = tools.jackson.databind.json.JsonMapper.builder()
        .enable(tools.jackson.databind.cfg.JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(tools.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();
    static String group(JsonNode item) {
        String id = text(item, "id");
        return id.startsWith("m7-flow/customer-hold-") ? "m7-flow/customer-hold" : id;
    }

    public static ObjectNode create(M8Evaluation.Cell cell, String runId, Instant baseTime, Map<String, String> actual) {
        var item = M8Evaluation.item(cell.caseId());
        var node = JSON.createObjectNode();
        node.put("runId", runId).put("fixtureGroup", group(item)).put("repeat", cell.repeat())
            .put("model", cell.model()).put("lane", cell.lane()).put("baseTime", baseTime.toString()).put("venueTimezone", "Asia/Seoul");
        var symbols = node.putObject("symbols");
        var scope = read("dataset.json").path("scope");
        var names = new TreeSet<String>();
        scope.path("tenants").forEach(n -> names.add(n.asString()));
        for (String key : List.of("venues", "actors", "targets")) names.addAll(scope.path(key).propertyNames());
        for (String name : names) {
            if (actual.isEmpty() || actual.containsKey(name)) symbols.put(name, actual.getOrDefault(name, UUID.randomUUID().toString()));
        }
        var times = node.putObject("times");
        times.put("base", baseTime.toString());
        times.put("basePlusOneHour", baseTime.plusSeconds(3600).toString());
        times.put("venueDate", LocalDate.ofInstant(baseTime, ZoneId.of("Asia/Seoul")).toString());
        for (String key : List.of("slotStartsAt", "slotEndsAt")) if (actual.containsKey("time:" + key)) times.put(key, actual.get("time:" + key));
        return node;
    }

    static void validate(JsonNode binding, CellIdentity identity, JsonNode item) {
        fields(binding, "runId fixtureGroup repeat model lane baseTime venueTimezone symbols times");
        equal(binding, "runId", identity.runId()); equal(binding, "fixtureGroup", group(item));
        equal(binding, "model", identity.cell().model()); equal(binding, "lane", identity.cell().lane());
        require(binding.path("repeat").asInt() == identity.cell().repeat(), "binding repeat mismatch");
        equal(binding, "venueTimezone", "Asia/Seoul");
        Instant base = Instant.parse(text(binding, "baseTime"));
        var times = binding.path("times");
        equal(times, "base", base.toString()); equal(times, "basePlusOneHour", base.plusSeconds(3600).toString());
        equal(times, "venueDate", LocalDate.ofInstant(base, ZoneId.of("Asia/Seoul")).toString());
        if (times.has("slotStartsAt")) {
            Instant start = Instant.parse(text(times, "slotStartsAt")), end = Instant.parse(text(times, "slotEndsAt"));
            require(start.isBefore(end), "invalid actual slot time binding");
            String setup = item.path("fixture").path("setup").asString();
            if (setup.equals("CLOSED_SLOT")) require(!base.isBefore(start), "closed slot fixture is still bookable");
            if (setup.equals("BOOKABLE_SLOT")) require(base.isBefore(start), "bookable slot fixture already closed");
        }
        require(binding.path("symbols").isObject(), "binding symbols missing");
        var seen = new HashSet<String>();
        for (var entry : binding.path("symbols").properties()) {
            if (entry.getValue().asString().equals("UNBOUND")) {
                require(entry.getKey().startsWith("reservation-")
                    && (item.path("oracle").path("execution").path("db").asString().equals("HOLD_ATOMIC") || item.path("fixture").has("requestedHold")),
                    "only a not-yet-created reservation may be unbound");
            } else require(seen.add(UUID.fromString(entry.getValue().asString()).toString()), "aliased fixture UUID");
        }
        for (String key : List.of("tenant", "venue", "actor", "customer", "target")) {
            String symbol = item.path("scope").path(key).asString();
            if (!symbol.isEmpty()) text(binding.path("symbols"), symbol);
        }
        String target = item.path("oracle").path("proposal").path("target").asString();
        if (!target.isEmpty()) text(binding.path("symbols"), target);
        rejectSensitive(binding);
    }

    static boolean preservesAssignments(JsonNode before, JsonNode after) {
        var previous = (ObjectNode) before.deepCopy();
        for (var entry : before.path("symbols").properties()) {
            String next = after.path("symbols").path(entry.getKey()).asString();
            if (entry.getValue().asString().equals("UNBOUND") && !next.equals("UNBOUND")) {
                UUID.fromString(next); ((ObjectNode) previous.path("symbols")).put(entry.getKey(), next);
            }
        }
        return previous.equals(after);
    }

    record CellIdentity(String runId, M8Evaluation.Cell cell) { }

    public static JsonNode modelInput(JsonNode item, JsonNode binding) {
        var node = JSON.createObjectNode();
        node.put("input", text(item, "input"));
        // Only observed/supplied Product and Knowledge facts are exposed. Fault, approval and DB setup stay in the harness.
        var facts = node.putObject("facts");
        for (String key : List.of("knowledge", "product", "requestedHold", "exactSlot", "slot", "partySize", "facts", "untrustedNote", "productOutcome", "projection", "delivery", "receipt", "truncated", "windowAxis")) {
            if (item.path("fixture").has(key)) facts.set(key, bind(item.path("fixture").path(key), binding.path("symbols")));
        }
        rejectSensitive(node);
        return node;
    }

    private static JsonNode bind(JsonNode value, JsonNode symbols) {
        if (value.isString() && symbols.has(value.asString())) return symbols.path(value.asString());
        if (value.isObject()) {
            var result = JSON.createObjectNode();
            value.properties().forEach(e -> result.set(e.getKey(), bind(e.getValue(), symbols)));
            return result;
        }
        if (value.isArray()) {
            var result = JSON.createArrayNode(); value.forEach(v -> result.add(bind(v, symbols))); return result;
        }
        return value;
    }

    public static ObjectNode request(ProviderProtocol.Request request) {
        var node = (ObjectNode) JSON.valueToTree(request);
        int start = request.prompt().indexOf('{'), end = request.prompt().indexOf("\n응답 제어 필드:");
        require(start >= 0 && end > start, "actual RepresentativeContext content missing");
        JsonNode context = JSON.readTree(request.prompt().substring(start, end));
        node.set("context", context);
        var mapping = node.putObject("claimSources");
        collect(context, "", mapping);
        if (context.path("limitations").isString()) mapping.putArray("limitation:scope");
        for (String claim : request.knownClaims()) require(mapping.has(claim), "unbound dynamic context claim");
        rejectSensitive(node);
        return node;
    }

    static boolean verifiesRequest(JsonNode item, Map<String, JsonNode> raw) {
        var request = raw.get("request"); var binding = raw.get("bindings");
        if (request == null || binding == null) return false;
        if (request.has("input")) {
            var expected = modelInput(item, binding);
            return expected.path("input").equals(request.path("input")) && expected.path("facts").equals(request.path("facts"));
        }
        if (!raw.containsKey("flow-plan") || !raw.containsKey("actor")) return false;
        var plan = REPLAY.treeToValue(raw.get("flow-plan"), com.slotq.ai.runtime.RuntimeContract.Plan.class);
        var actor = REPLAY.treeToValue(raw.get("actor"), com.slotq.auth.access.DelegatedActor.class);
        var facts = REPLAY.treeToValue(raw.get("result").path("runtime"), com.slotq.ai.runtime.RuntimeContract.Result.class);
        var stage = (item.path("oracle").path("proposal").path("disposition").asString().equals("PROPOSE_HOLD")
                || raw.get("result").path("boundaryRejection").path("stage").asString().equals("AFTER_PROPOSAL"))
            ? com.slotq.ai.runtime.RuntimeContract.Stage.PROPOSAL : com.slotq.ai.runtime.RuntimeContract.Stage.ANSWER;
        var expected = new com.slotq.ai.flow.RepresentativeContext(a -> true).prepare(actor, stage, facts, plan).request();
        var projected = request(expected);
        String prompt = request.path("prompt").asString();
        int begin = prompt.indexOf('{'), end = prompt.indexOf("\n응답 제어 필드:");
        if (begin < 0 || end <= begin) return false;
        String expectedPrompt = expected.prompt();
        return plan.workload().equals(item.path("workload").asString())
            && request.path("maximumOutputTokens").asInt() == expected.maximumOutputTokens()
            && actor.original().principalId().value().toString().equals(binding.path("symbols").path(item.path("scope").path("actor").asString()).asString())
            && actor.tenantId().value().toString().equals(binding.path("symbols").path(item.path("scope").path("tenant").asString()).asString())
            && actor.venueId().value().toString().equals(binding.path("symbols").path(item.path("scope").path("venue").asString()).asString())
            && projected.path("context").equals(request.path("context")) && projected.path("context").equals(JSON.readTree(prompt.substring(begin, end)))
            && projected.path("claimSources").equals(request.path("claimSources"))
            && strings(projected, "knownSources").equals(strings(request, "knownSources"))
            && strings(projected, "knownClaims").equals(strings(request, "knownClaims"))
            && prompt.substring(0, begin).equals(expectedPrompt.substring(0, expectedPrompt.indexOf('{')))
            && prompt.substring(end).equals(expectedPrompt.substring(expectedPrompt.indexOf("\n응답 제어 필드:")));
    }

    private static void collect(JsonNode node, String inheritedSource, ObjectNode mapping) {
        if (node.isObject()) {
            String source = node.path("sourceRef").asString(inheritedSource);
            for (var e : node.properties()) {
                if (e.getKey().toLowerCase(Locale.ROOT).endsWith("claimref") && e.getValue().isString()) {
                    var refs = mapping.putArray(e.getValue().asString()); if (!source.isEmpty()) refs.add(source);
                }
                collect(e.getValue(), source, mapping);
            }
        } else if (node.isArray()) node.forEach(n -> collect(n, inheritedSource, mapping));
    }
    private M8Bindings() { }
}
