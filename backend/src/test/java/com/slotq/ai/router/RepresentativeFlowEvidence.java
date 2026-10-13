package com.slotq.ai.router;

import com.slotq.ai.flow.*;
import com.slotq.ai.runtime.RuntimeContract.*;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/** Seven fixed integrated observations; shares #152 oracle and full-content review, never a provider/Runtime bypass. */
public final class RepresentativeFlowEvidence {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
        .build();
    private static final String SPECIFICATION = "/representative-flows/evidence-v3.json";
    private static final String REVIEW_POLICY = "/model-router/content-review-policy-v2.json";
    private final Path directory;
    private final Instant deadline;
    private final JsonNode specification;
    private int calls;

    public static GeminiAdapter adapter(HttpClient http, String key, Consumer<Map<String,Object>> capture) {
        return new GeminiAdapter(http,new GeminiAdapter.Secret(key),capture);
    }

    public RepresentativeFlowEvidence() throws Exception {
        if (!Boolean.getBoolean("slotq.flowEvidence.optIn") || !Boolean.getBoolean("slotq.flowEvidence.freeTier")
                || Boolean.getBoolean("slotq.flowEvidence.paidCalls")) throw new IllegalStateException("Explicit free-only integrated provider opt-in required");
        directory = directory();
        if (Files.exists(directory.resolve("manifest.json"))) throw new IllegalStateException("Integrated evidence already exists; choose a new ignored output directory");
        Files.createDirectories(directory);
        byte[] fixture = resource(SPECIFICATION);
        specification = JSON.readTree(fixture);
        deadline = Instant.now().plus(Duration.ofMinutes(specification.path("maximumMinutes").asInt()));
        var manifest = new LinkedHashMap<String,Object>();
        manifest.put("revision", "m7-integrated-flow-evidence-v3");
        manifest.put("evidenceKind", "actual-provider/production-runtime/real-mcp-product-knowledge/synthetic-demo");
        manifest.put("specification", specification);
        manifest.put("specificationSha256", digest(fixture));
        manifest.put("reviewPolicySha256", digest(resource(REVIEW_POLICY)));
        manifest.put("schemaSha256", digest(ProviderProtocol.SCHEMA_JSON.getBytes(StandardCharsets.UTF_8)));
        manifest.put("profileSha256", digest(resource("/ai/representative-router-v1.json")));
        manifest.put("sourceRevision", git("rev-parse", "HEAD"));
        manifest.put("sourceDirty", !git("status", "--porcelain").isBlank());
        var sources = new TreeMap<String,String>();
        for (String file : List.of("ai/flow/RepresentativeContext", "ai/flow/RepresentativeFlows", "ai/flow/RepresentativeRouterProfile",
                "ai/runtime/AgentRuntime", "ai/runtime/RuntimeContract", "ai/router/GeminiAdapter", "ai/router/ModelRouter", "ai/router/ProviderProtocol", "ai/router/ProviderBudget")) {
            sources.put(file, digest(Files.readAllBytes(Path.of("src/main/java/com/slotq/" + file + ".java"))));
        }
        for (String file : List.of("ai/router/RepresentativeFlowEvidence", "ai/router/ComparisonReview", "ai/router/ModelComparisonRunner", "mcp/RepresentativeFlowIntegrationTests")) {
            sources.put(file, digest(Files.readAllBytes(Path.of("src/test/java/com/slotq/" + file + ".java"))));
        }
        manifest.put("sourceDigests", sources);
        manifest.put("contextRevision", RepresentativeContext.REVISION);
        manifest.put("java", System.getProperty("java.version"));
        manifest.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        manifest.put("startedAt", Instant.now().toString()); manifest.put("deadline", deadline.toString());
        manifest.put("tier", "user-authorized-free/no-paid-calls");
        manifest.put("training", true); manifest.put("region", "unavailable"); manifest.put("retention", "unavailable");
        manifest.put("priceSource", "https://ai.google.dev/gemini-api/docs/pricing");
        manifest.put("dataControlSource", "https://ai.google.dev/gemini-api/terms");
        manifest.put("retryFallback", "unsupported; all provider failures terminal; independent flows only");
        manifest.put("httpControls", Map.of("version", "HTTP_1_1", "redirects", "NEVER",
            "disableRetryConnect", Boolean.getBoolean("jdk.httpclient.disableRetryConnect"),
            "enableAllMethodRetry", Boolean.getBoolean("jdk.httpclient.enableAllMethodRetry"),
            "retryLimit", System.getProperty("jdk.httpclient.redirects.retrylimit")));
        manifest.put("generationConfig", Map.of("temperature", 1, "candidateCount", 1, "maxOutputTokens", 1024,
            "responseMimeType", "application/json", "apiMode", GeminiAdapter.API_MODE));
        manifest.put("toolConnection", "Runtime invokes the co-located production McpEngine; HTTPS lifecycle/list/call checks use the same registry and delegation");
        manifest.put("syntheticFixture", "Disposable MySQL Tenant and Actors, real HOLD/Knowledge publication/event append and observer projection; pending waitlist deliveries are not processed");
        manifest.put("approvalHandoff", "Controlled synthetic original Actor reads immutable review and explicitly approves; no provider approval and no public endpoint");
        manifest.put("runControlLimit", "process-local exact delegation only; no restart resume or automatic mutation");
        Files.write(directory.resolve("manifest.json"), JSON.writeValueAsBytes(manifest));
    }

    public void beforeAttempt() throws Exception {
        if (calls >= specification.path("maximumCalls").asInt() || !Instant.now().isBefore(deadline)) throw new IllegalStateException("Integrated experiment budget ended");
        if (calls > 0) Thread.sleep(specification.path("minimumSpacingMillis").asLong());
        calls++;
    }

    public synchronized void providerEntered(String model, ModelRouter.Classification classification, ProviderProtocol.Request request) throws Exception {
        Files.writeString(directory.resolve("provider-starts.jsonl"),JSON.writeValueAsString(Map.of("evidenceKind","actual-adapter-entered",
            "model",model,"request",request,"classification",classification,"requestSha256",digest(request.prompt().getBytes(StandardCharsets.UTF_8)),
            "observedAt",Instant.now().toString()))+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }

    public synchronized void providerCompleted(ProviderProtocol.Request request,GeminiAdapter.Result result,Map<String,Object> captured) throws Exception {
        Files.writeString(directory.resolve("provider-completions.jsonl"),JSON.writeValueAsString(Map.of("evidenceKind","actual-adapter-completed",
            "requestSha256",digest(request.prompt().getBytes(StandardCharsets.UTF_8)),"result",result,"rawStructuredResponse",captured,
            "observedAt",Instant.now().toString()))+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }

    public void record(String caseId, Plan plan, Result before, ProviderProtocol.Request request, GeminiAdapter.Result provider,
                       Map<String,Object> rawResponse, Result result, long durationMillis, long approvalWaitMillis) throws Exception {
        JsonNode testCase = testCase(specification, caseId);
        var row = new LinkedHashMap<String,Object>();
        row.put("evidenceKind", "actual-provider"); row.put("caseId", caseId); row.put("workload", plan.workload());
        row.put("model", "gemini-3.5-flash-lite"); row.put("repeat", 1);
        row.put("request", request); row.put("requestSha256", digest(request.prompt().getBytes(StandardCharsets.UTF_8)));
        row.put("requestSchemaSha256", digest(JSON.writeValueAsBytes(ProviderProtocol.schema(request))));
        row.put("result", provider); row.put("rawStructuredResponse", rawResponse);
        row.put("plan", plan);
        Instant observedAt = Instant.now();
        row.put("before", before); row.put("runtime", result); row.put("observedAt", observedAt.toString());
        row.put("clientElapsedMillis", durationMillis);
        row.put("runDurationMillis", Duration.between(result.budget().admittedAt(), observedAt).toMillis());
        row.put("approvalWaitMillis", approvalWaitMillis);
        var oracle = new LinkedHashMap<String,Object>();
        boolean proposal = testCase.path("disposition").asString().equals("PROPOSE_HOLD");
        oracle.put("disposition", testCase.path("disposition").asString());
        oracle.put("target", proposal ? plan.hold().slot().toString() : "");
        oracle.put("partySize", proposal ? plan.hold().partySize() : 0);
        oracle.put("outcome", switch (before.productEffect()) {
            case SUCCEEDED -> "HELD"; case REJECTED -> "REJECTED"; case UNKNOWN -> "UNKNOWN"; case NOT_APPLICABLE -> "NOT_APPLICABLE";
        });
        oracle.put("sources", testCase.path("requiredSources")); oracle.put("required", testCase.path("requiredClaims"));
        oracle.put("allowedSources", request.knownSources()); oracle.put("allowed", request.knownClaims());
        row.put("oracle", oracle);
        Files.writeString(directory.resolve("attempts.jsonl"), JSON.writeValueAsString(row) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("slotq.flowEvidence.recalculate")) throw new IllegalStateException("Only offline recalculation is supported by this entry point");
        recalculate(directory());
    }

    public static void recalculate(Path directory) throws Exception {
        var manifest = JSON.readTree(Files.readAllBytes(directory.resolve("manifest.json")));
        byte[] specificationBytes = resource(SPECIFICATION);
        JsonNode specification = JSON.readTree(specificationBytes);
        if (!digest(specificationBytes).equals(manifest.path("specificationSha256").asString())
                || !specification.equals(manifest.path("specification"))
                || !digest(resource(REVIEW_POLICY)).equals(manifest.path("reviewPolicySha256").asString())
                || !digest(ProviderProtocol.SCHEMA_JSON.getBytes(StandardCharsets.UTF_8)).equals(manifest.path("schemaSha256").asString())
                || !digest(resource("/ai/representative-router-v1.json")).equals(manifest.path("profileSha256").asString())) {
            throw new IllegalStateException("Integrated evidence profile identity mismatch");
        }
        if (!RepresentativeContext.REVISION.equals(manifest.path("contextRevision").asString())
                || !manifest.path("generationConfig").equals(JSON.valueToTree(Map.of("temperature", 1,
                    "candidateCount", 1, "maxOutputTokens", 1024, "responseMimeType", "application/json", "apiMode", GeminiAdapter.API_MODE)))) {
            throw new IllegalStateException("Integrated context/generation configuration mismatch");
        }
        var http = manifest.path("httpControls");
        if (!http.path("version").asString().equals("HTTP_1_1") || !http.path("disableRetryConnect").asBoolean()
                || !http.has("enableAllMethodRetry") || http.path("enableAllMethodRetry").asBoolean()
                || !http.path("retryLimit").asString().equals("1") || !http.path("redirects").asString().equals("NEVER")) {
            throw new IllegalStateException("Integrated HTTP controls unavailable");
        }
        var rows = Files.readAllLines(directory.resolve("attempts.jsonl"), StandardCharsets.UTF_8).stream().filter(s -> !s.isBlank()).map(JSON::readTree).toList();
        if (rows.isEmpty() || rows.size() > specification.path("maximumCalls").asInt())
            throw new IllegalStateException("Integrated actual-provider observation count invalid");
        var review = ComparisonReview.verify(JSON.readTree(Files.readAllBytes(directory.resolve("content-review.json"))), rows,
            manifest.path("specificationSha256").asString(), manifest.path("reviewPolicySha256").asString());
        var counts = new HashSet<String>();
        var grades = new TreeMap<String,Map<String,Boolean>>();
        for (var row : rows) {
            String id = row.path("caseId").asString();
            JsonNode testCase = testCase(specification,id);
            if (!counts.add(id) || !row.path("evidenceKind").asString().equals("actual-provider")
                    || !row.path("model").asString().equals(specification.path("model").asString())
                    || !row.path("workload").equals(testCase.path("workload")) || row.path("repeat").asInt()!=1
                    || row.path("result").path("attempts").asInt()!=1) throw new IllegalStateException("Integrated observation identity invalid");
            var request = JSON.treeToValue(row.path("request"), ProviderProtocol.Request.class);
            if (!digest(request.prompt().getBytes(StandardCharsets.UTF_8)).equals(row.path("requestSha256").asString())
                    || !digest(JSON.writeValueAsBytes(ProviderProtocol.schema(request))).equals(row.path("requestSchemaSha256").asString())) {
                throw new IllegalStateException("Integrated request identity invalid");
            }
            var runtime = JSON.treeToValue(row.path("runtime"), Result.class);
            var plan = JSON.treeToValue(row.path("plan"), Plan.class);
            var routing = runtime.providers().getLast().routing();
            if (!new ModelRouter().replay(routing)) throw new IllegalStateException("Integrated routing replay mismatch");
            if (!routing.snapshot().candidates().equals(RepresentativeRouterProfile.candidates()))
                throw new IllegalStateException("Integrated routing candidate profile mismatch");
            if (routing.selected()==null || !routing.snapshot().workload().equals(row.path("workload").asString())
                    || !routing.selected().equals(row.path("workload").asString()+".gemini-3.5-flash-lite")
                    || !plan.workload().equals(routing.snapshot().workload()) || !plan.revision().equals(routing.snapshot().workloadRevision()))
                throw new IllegalStateException("Integrated routing mismatch");
            var oracle = row.path("oracle");
            if (!oracle.path("disposition").equals(testCase.path("disposition")) || !oracle.path("sources").equals(testCase.path("requiredSources"))
                    || !oracle.path("required").equals(testCase.path("requiredClaims"))) throw new IllegalStateException("Integrated oracle changed");
            boolean proposal=testCase.path("disposition").asString().equals("PROPOSE_HOLD");
            if (!(proposal ? plan.hold().slot().toString() : "").equals(oracle.path("target").asString())
                    || oracle.path("partySize").asInt()!=(proposal ? plan.hold().partySize() : 0)
                    || !new TreeSet<>(request.knownSources()).equals(references(oracle.path("allowedSources")))
                    || !new TreeSet<>(request.knownClaims()).equals(references(oracle.path("allowed"))))
                throw new IllegalStateException("Integrated oracle material/reference mismatch");
            var before = JSON.treeToValue(row.path("before"), Result.class);
            String expected = switch(before.productEffect()) { case SUCCEEDED -> "HELD"; case REJECTED -> "REJECTED"; case UNKNOWN -> "UNKNOWN"; case NOT_APPLICABLE -> "NOT_APPLICABLE"; };
            if (!expected.equals(oracle.path("outcome").asString()) || !before.runId().equals(runtime.runId())
                    || !before.budget().deadline().equals(runtime.budget().deadline())
                    || runtime.budget().providerAttempts()!=before.budget().providerAttempts()+1) throw new IllegalStateException("Integrated live Run continuity invalid");
            var output = row.path("result").path("output").isNull() ? null : JSON.treeToValue(row.path("result").path("output"),ProviderProtocol.Output.class);
            if (output!=null) {
                ProviderProtocol.parse(JSON.writeValueAsString(output),request);
                var captured=row.path("rawStructuredResponse").path("candidates");
                if(captured.size()!=1 || !captured.get(0).path("finishReason").asString().equals("STOP")
                        || captured.get(0).path("texts").size()!=1
                        || !ProviderProtocol.parse(captured.get(0).path("texts").get(0).asString(),request).equals(output)) {
                    throw new IllegalStateException("Integrated normalized/raw answer mismatch");
                }
                if(!row.path("result").path("usage").equals(row.path("rawStructuredResponse").path("usage")))
                    throw new IllegalStateException("Integrated normalized/raw usage mismatch");
            }
            var evaluation = JSON.createObjectNode().put("id",id).put("workload",row.path("workload").asString());
            evaluation.set("oracle",oracle);
            evaluation.set("fixture",JSON.valueToTree(Map.of("knowledge",Map.of("fact","knowledge:guide","source","knowledge:guide"))));
            var marks = new LinkedHashMap<>(ModelComparisonRunner.grade(evaluation,output));
            marks.put("runtimeResult", runtime.failure()==null && (testCase.path("disposition").asString().equals("PROPOSE_HOLD")
                ? runtime.execution()==Execution.WAITING_CONFIRMATION : runtime.answer()!=null));
            marks.put("contentReview",review.get(ComparisonReview.identity(row)).pass());
            marks.put("flowOutputContract",output!=null && (proposal || new RepresentativeContext(a -> false).acceptsAnswer(plan,runtime,output)));
            grades.put(id,marks);
        }
        var summary = new ArrayList<Map<String,Object>>();
        for (String workload : List.of("customer","management","ops")) {
            var selected = rows.stream().filter(r -> r.path("workload").asString().equals(workload)).toList();
            long expectedCases = 0;
            for (var testCase : specification.path("cases"))
                if (testCase.path("workload").asString().equals(workload)) expectedCases++;
            long passing = selected.stream().filter(r -> grades.get(r.path("caseId").asString()).values().stream().allMatch(Boolean::booleanValue)).count();
            long safety = selected.stream().filter(r -> !review.get(ComparisonReview.identity(r)).pass()).count();
            var latencies = selected.stream().map(r -> r.path("result").path("latencyMillis").asLong()).sorted().toList();
            long tokens = selected.stream().filter(r -> r.path("result").path("usage").path("status").asString().equals("measured"))
                .mapToLong(r -> r.path("result").path("usage").path("total").asLong()).sum();
            long unavailable = selected.stream().filter(r -> !r.path("result").path("usage").path("status").asString().equals("measured")).count();
            var item = new LinkedHashMap<String,Object>();
            item.put("workload",workload); item.put("actualAttempts",selected.size()); item.put("oracleAndReviewPass",passing);
            item.put("expectedCases",expectedCases); item.put("unobservedCases",expectedCases-selected.size());
            item.put("quality",selected.isEmpty()?null:(double)passing/selected.size()); item.put("safetyViolations",safety);
            item.put("p95ProviderMillis",selected.isEmpty()?null:latencies.get((int)Math.ceil(latencies.size()*0.95)-1));
            item.put("observedTokens",selected.size()==unavailable?null:tokens); item.put("usageUnavailableAttempts",unavailable);
            boolean knownCost = !selected.isEmpty() && unavailable==0;
            item.put("costStatus",knownCost?"estimated":"unavailable");
            item.put("estimatedObservedDollars",knownCost?0:null);
            item.put("failures",selected.stream().map(r -> r.path("result").path("failure").asString("none")).toList());
            item.put("maxRunDurationMillis",selected.isEmpty()?null:selected.stream().mapToLong(r -> r.path("runDurationMillis").asLong()).max().orElseThrow());
            item.put("approvalWaitMillis",selected.isEmpty()?null:selected.stream().mapToLong(r -> r.path("approvalWaitMillis").asLong()).max().orElse(0));
            item.put("complete",selected.size()==expectedCases && passing==selected.size() && safety==0 && unavailable==0);
            summary.add(item);
        }
        Files.write(directory.resolve("summary.json"),JSON.writeValueAsBytes(summary));
        Files.write(directory.resolve("oracle-marks.json"),JSON.writeValueAsBytes(grades));
        Files.write(directory.resolve("recalculation.json"),JSON.writeValueAsBytes(Map.of(
            "observedAt",Instant.now().toString(),
            "calculatorSha256",digest(Files.readAllBytes(Path.of("src/test/java/com/slotq/ai/router/RepresentativeFlowEvidence.java"))),
            "attemptsSha256",digest(Files.readAllBytes(directory.resolve("attempts.jsonl"))),
            "reviewSha256",digest(Files.readAllBytes(directory.resolve("content-review.json"))))));
        System.out.println("Recalculated " + rows.size() + " integrated actual-provider records and verified content review / live routing replay");
        if (summary.stream().anyMatch(s -> !Boolean.TRUE.equals(s.get("complete")))) throw new IllegalStateException("Integrated flow quality/evidence incomplete");
    }

    static Path directory() {
        String name = System.getProperty("slotq.flowEvidence.output","representative-flow-v3");
        if (!name.matches("representative-flow-[a-zA-Z0-9._-]{1,80}") || name.contains("..")) throw new IllegalArgumentException("Ignored evidence output name invalid");
        return Path.of("../build",name);
    }
    static JsonNode testCase(JsonNode specification,String id) {
        for (var testCase : specification.path("cases")) if (testCase.path("id").asString().equals(id)) return testCase;
        throw new IllegalStateException("Unknown integrated evidence case");
    }
    private static Set<String> references(JsonNode array) {
        if (!array.isArray()) throw new IllegalStateException("Integrated reference array unavailable");
        var values=new TreeSet<String>();
        for (var value : array) if (!value.isString() || !values.add(value.asString()))
            throw new IllegalStateException("Integrated reference identity invalid");
        return values;
    }
    static byte[] resource(String path) throws Exception {
        try (var input = RepresentativeFlowEvidence.class.getResourceAsStream(path)) {
            return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(StandardCharsets.UTF_8);
        }
    }
    static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git","-c","safe.directory=C:/dev/slotq")); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        if (process.waitFor()!=0) throw new IllegalStateException("Source provenance unavailable");
        return output;
    }
}
