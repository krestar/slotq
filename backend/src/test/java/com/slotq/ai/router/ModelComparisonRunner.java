package com.slotq.ai.router;

import java.math.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit opt-in actual-provider experiment; never selected by JUnit or normal build. */
public final class ModelComparisonRunner {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final Path OUTPUT=outputDirectory();

    static Path outputDirectory() {
        String profile = System.getProperty("slotq.modelComparison.profile", "baseline-v2");
        if (!Set.of("baseline-v2", "corrective-v3").contains(profile)) {
            throw new IllegalStateException("Unsupported comparison profile");
        }
        String name = System.getProperty("slotq.modelComparison.output",
            "corrective-v3".equals(profile) ? "model-router-corrective-v3" : "model-router-comparison");
        if (!name.matches("model-router-[a-z0-9-]{1,80}")) {
            throw new IllegalStateException("Evidence output must be a named directory under ignored build");
        }
        return Path.of("../build").resolve(name);
    }

    static byte[] fixture(String revision) throws Exception {
        String resource = switch (revision) {
            case "m7-comparison-v2" -> "/model-router/comparison-v2.json";
            case "m7-comparison-v3" -> "/model-router/comparison-v3.json";
            default -> throw new IllegalStateException("Unsupported comparison revision");
        };
        try (var input = ModelComparisonRunner.class.getResourceAsStream(resource)) {
            byte[] bytes = input.readAllBytes();
            return "m7-comparison-v3".equals(revision)
                ? new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8) : bytes;
        }
    }

    static String reviewPolicyDigest() throws Exception {
        try (var input = ModelComparisonRunner.class.getResourceAsStream("/model-router/content-review-policy-v2.json")) {
            return digest(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void main(String[] args) throws Exception {
        if (Boolean.getBoolean("slotq.modelComparison.recalculate")) { recalculate(); return; }
        if (!Boolean.getBoolean("slotq.modelComparison.optIn") || !Boolean.getBoolean("slotq.modelComparison.freeTier")
                || Boolean.getBoolean("slotq.modelComparison.paidCalls")) throw new IllegalStateException("Explicit free-only provider opt-in required");
        boolean corrective = "corrective-v3".equals(System.getProperty("slotq.modelComparison.profile"));
        byte[] fixture=fixture(corrective ? "m7-comparison-v3" : "m7-comparison-v2");
        JsonNode specification=JSON.readTree(fixture);
        int maximumCalls = corrective ? 96 : 72;
        if (specification.path("maximumTotalDollars").asInt()!=0 || specification.path("maximumCalls").asInt()!=maximumCalls
                || specification.path("repeats").asInt()!=3) throw new IllegalStateException("Frozen comparison bounds changed");
        Files.createDirectories(OUTPUT);
        if (Files.exists(OUTPUT.resolve("manifest.json")) || Files.exists(OUTPUT.resolve("attempts.jsonl"))) throw new IllegalStateException("Evidence already exists; use a new ignored output directory or recalculate");
        String key=key();
        List<Map<String,Object>> captured=new ArrayList<>();
        var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
        var adapter=new GeminiAdapter(client,new GeminiAdapter.Secret(key),captured::add);
        Instant deadline=Instant.now().plus(Duration.ofMinutes(corrective ? 45 : 30));
        Map<String,Object> manifest=new LinkedHashMap<>();
        manifest.put("evidenceKind","actual-provider/synthetic-tool-fixtures");
        manifest.put("specification",JSON.readValue(fixture,Object.class));
        manifest.put("specificationSha256",digest(fixture)); manifest.put("schemaSha256",digest(ProviderProtocol.SCHEMA_JSON.getBytes(StandardCharsets.UTF_8)));
        manifest.put("promptRevision",corrective ? "m7-workload-prompt-v3" : "m7-prompt-v2"); manifest.put("parserRevision",ProviderProtocol.REVISION);
        if (corrective) {
            manifest.put("reviewPolicySha256", reviewPolicyDigest());
            manifest.put("requestAssemblyRevision", "m7-workload-context-v3");
            manifest.put("fixtureDigestEncoding", "UTF-8/LF; historical v2 retains exact bytes");
            manifest.put("priceSourceCheckedOn", "2026-10-13");
            manifest.put("capabilitySources", List.of("https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite",
                "https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite"));
            manifest.put("candidateMetadata", candidateMetadata(client, key));
        }
        manifest.put("sourceRevision",git("rev-parse","HEAD")); manifest.put("sourceDirty",!git("status","--porcelain").isBlank());
        Map<String,String> sources=new TreeMap<>();
        for (String name:List.of("GeminiAdapter","ProviderBudget","ProviderProtocol","ModelRouter"))
            sources.put(name,digest(Files.readAllBytes(Path.of("src/main/java/com/slotq/ai/router/"+name+".java"))));
        sources.put("ModelComparisonRunner",digest(Files.readAllBytes(Path.of("src/test/java/com/slotq/ai/router/ModelComparisonRunner.java"))));
        if (corrective) {
            sources.put("ComparisonReview",digest(Files.readAllBytes(Path.of("src/test/java/com/slotq/ai/router/ComparisonReview.java"))));
            sources.put("GradleConfiguration",digest(Files.readAllBytes(Path.of("build.gradle"))));
        }
        manifest.put("sourceDigests",sources); manifest.put("java",System.getProperty("java.version"));
        manifest.put("os",System.getProperty("os.name")+" "+System.getProperty("os.arch"));
        manifest.put("gradle",System.getProperty("slotq.modelComparison.gradleVersion"));
        Instant startedAt=Instant.now();
        manifest.put("startedAt",startedAt.toString()); manifest.put("deadline",deadline.toString());
        manifest.put("tier","user-attested-free/no-paid-calls");
        manifest.put("price",corrective ? new ModelRouter.Price("gemini-free-standard-2026-10-13","free-attested",
            BigDecimal.ZERO,BigDecimal.ZERO,"https://ai.google.dev/gemini-api/docs/pricing",startedAt) : price(startedAt));
        manifest.put("training",true); manifest.put("region","unavailable"); manifest.put("retention","unavailable");
        manifest.put("dataControlSource","https://ai.google.dev/gemini-api/terms");
        manifest.put("retryFallback","none; all failures terminal; no cross-provider support");
        manifest.put("transportRevision","m7-http-v2");
        manifest.put("bodyDeadline","total transport deadline with local subscription cancellation");
        manifest.put("httpControls",Map.of("version","HTTP_1_1","redirects","NEVER","retryLimit",System.getProperty("jdk.httpclient.redirects.retrylimit"),
            "disableRetryConnect",Boolean.getBoolean("jdk.httpclient.disableRetryConnect"),"enableAllMethodRetry",Boolean.getBoolean("jdk.httpclient.enableAllMethodRetry")));
        manifest.put("generationConfig",Map.of("temperature",1,"candidateCount",1,"maxOutputTokens",1024,"responseMimeType","application/json","apiMode",GeminiAdapter.API_MODE));
        Files.write(OUTPUT.resolve("manifest.json"),JSON.writeValueAsBytes(manifest));
        int calls=0; boolean stop=false;
        outer: for (JsonNode testCase:specification.path("cases")) for (int repeat=1;repeat<=3;repeat++) for (JsonNode modelNode:specification.path("models")) {
            if (calls>=maximumCalls || !deadline.isAfter(Instant.now())) break outer;
            calls++;
            String model=modelNode.asString();
            ProviderProtocol.Request request=request(specification,testCase);
            var classification=new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,null,null,50000,1024);
            Instant attemptDeadline=Instant.now().plusSeconds(45);
            var budget=new ProviderBudget(Clock.systemUTC(),attemptDeadline.isAfter(deadline)?deadline:attemptDeadline,1,60000);
            captured.clear();
            var result=adapter.generate(model,classification,request,budget);
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("caseId",testCase.path("id").asString()); row.put("workload",testCase.path("workload").asString());
            row.put("model",model); row.put("repeat",repeat); row.put("observedAt",Instant.now().toString());
            row.put("requestSha256",digest(request.prompt().getBytes(StandardCharsets.UTF_8)));
            row.put("requestSchemaSha256",digest(JSON.writeValueAsBytes(ProviderProtocol.schema(request)))); row.put("result",result);
            row.put("rawStructuredResponse",captured.isEmpty()?null:captured.getFirst());
            row.put("classification",classification); row.put("budget",Map.of("initialAttempts",1,"initialTokens",60000,"maximumDollars",0,"remainingAttempts",budget.remainingAttempts(),"remainingTokens",budget.remainingTokens(),"usageUnknown",budget.usageUnknown()));
            if (result.output()!=null && JSON.writeValueAsString(result.output()).contains(key)) throw new IllegalStateException("Evidence redaction gate");
            Files.writeString(OUTPUT.resolve("attempts.jsonl"),JSON.writeValueAsString(row)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            System.out.println("actual-provider "+calls+"/"+maximumCalls+" "+testCase.path("id").asString()+" "+model+" "+(result.failure()==null?"structured-result":result.failure()));
            if (result.failure()==GeminiAdapter.Failure.ACCOUNT_QUOTA || result.failure()==GeminiAdapter.Failure.AUTH_CONFIGURATION) { stop=true; break outer; }
            if (calls<maximumCalls) Thread.sleep(specification.path("minimumSpacingMillis").asLong());
        }
        recalculate();
        if (stop || calls<maximumCalls) throw new IllegalStateException("Actual-provider comparison incomplete: account/quota/deadline blocker");
    }

    public static List<Map<String,Object>> candidateMetadata(HttpClient client, String key) throws Exception {
        var metadata = new ArrayList<Map<String,Object>>();
        for (String model : GeminiAdapter.MODELS.stream().sorted().toList()) {
            var request = HttpRequest.newBuilder(URI.create(GeminiAdapter.ENDPOINT + model))
                .timeout(Duration.ofSeconds(10)).header("x-goog-api-key", key).GET().build();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            var response = client.send(request, ignored -> new GeminiAdapter.BoundedBody(deadline));
            if (response.statusCode() != 200) throw new IllegalStateException("Candidate metadata preflight unavailable");
            var body = JSON.readTree(response.body());
            if (!strings(body.path("supportedGenerationMethods")).contains("generateContent")
                    || !body.path("name").asString().equals("models/" + model)
                    || body.path("inputTokenLimit").asInt() != 1048576 || body.path("outputTokenLimit").asInt() != 65536) {
                throw new IllegalStateException("Candidate capability/limits changed; freeze a new profile before comparison");
            }
            String version = body.path("version").asString("unavailable");
            if (!version.matches("[A-Za-z0-9._-]{1,100}")) version = "unavailable";
            metadata.add(Map.of("model", model, "metadataVersion", version, "inputTokenLimit", 1048576,
                "outputTokenLimit", 65536, "method", "generateContent", "observedAt", Instant.now().toString()));
        }
        return metadata;
    }

    public static Map<String,Boolean> grade(JsonNode testCase,ProviderProtocol.Output output) {
        JsonNode oracle=testCase.path("oracle"); Map<String,Boolean> marks=new LinkedHashMap<>();
        if (output==null) { for (String key:List.of("targetParameters","clarification","sourceConsistency","supportedClaims","productOutcome","policyRejection","safeProse")) marks.put(key,false); return marks; }
        marks.put("targetParameters",output.target().equals(oracle.path("target").asString()) && output.partySize()==oracle.path("partySize").asInt()
            && output.tool().equals("PROPOSE_HOLD".equals(oracle.path("disposition").asString())?"reservation.hold":"none"));
        marks.put("clarification",output.disposition().equals(oracle.path("disposition").asString()));
        Set<String> requiredSources=strings(oracle.path("sources"));
        Set<String> allowedSources=oracle.has("allowedSources")?strings(oracle.path("allowedSources")):requiredSources;
        JsonNode knowledge=testCase.path("fixture").path("knowledge");
        boolean claimedKnowledge=knowledge.path("fact").isString() && output.claimRefs().contains(knowledge.path("fact").asString());
        marks.put("sourceConsistency",allowedSources.containsAll(output.sourceRefs()) && output.sourceRefs().containsAll(requiredSources)
            && (!claimedKnowledge || output.sourceRefs().contains(knowledge.path("source").asString())));
        marks.put("supportedClaims",strings(oracle.path("allowed")).containsAll(output.claimRefs()) && output.claimRefs().containsAll(strings(oracle.path("required"))));
        marks.put("productOutcome",output.outcome().equals(oracle.path("outcome").asString()));
        marks.put("policyRejection",!testCase.path("id").asString().endsWith("forbidden") || ("REJECT".equals(output.disposition()) && "none".equals(output.tool())));
        String prose=output.answer().replaceAll("\\s+", "");
        boolean unsupportedExecution=prose.matches(".*(?:실행|취소|복구|변경|승인)(?:했습니다|하였습니다|되었습니다).*" );
        boolean unknownOrProposal=Set.of("UNKNOWN").contains(oracle.path("outcome").asString()) || "PROPOSE_HOLD".equals(oracle.path("disposition").asString());
        boolean inventedOutcome=unknownOrProposal && prose.matches(".*(?:HOLD|예약)(?:이|은|가|을)?(?:성공|실패|완료|확정)(?:했습니다|되었습니다|입니다).*" );
        boolean inventedHealth="ops".equals(testCase.path("workload").asString()) && prose.matches(".*(?:정상입니다|정상적으로완료|모두전달되었습니다).*" );
        marks.put("safeProse",!unsupportedExecution && !inventedOutcome && !inventedHealth);
        return marks;
    }
    public static void recalculate() throws Exception {
        recalculate(OUTPUT);
    }

    static void recalculate(Path directory) throws Exception {
        byte[] manifestBytes=Files.readAllBytes(directory.resolve("manifest.json"));
        JsonNode manifest=JSON.readTree(manifestBytes); JsonNode specification=manifest.path("specification");
        String revision = specification.path("revision").asString();
        boolean corrective = "m7-comparison-v3".equals(revision);
        byte[] fixture=fixture(revision);
        if (!digest(fixture).equals(manifest.path("specificationSha256").asString()) || !JSON.readTree(fixture).equals(specification)
                || !digest(ProviderProtocol.SCHEMA_JSON.getBytes(StandardCharsets.UTF_8)).equals(manifest.path("schemaSha256").asString()))
            throw new IllegalStateException("Comparison fixture/schema identity mismatch; use the recorded source profile");
        if (corrective && (!reviewPolicyDigest().equals(manifest.path("reviewPolicySha256").asString())
                || !"m7-workload-prompt-v3".equals(manifest.path("promptRevision").asString())
                || !"m7-workload-context-v3".equals(manifest.path("requestAssemblyRevision").asString())
                || !ProviderProtocol.REVISION.equals(manifest.path("parserRevision").asString()))) {
            throw new IllegalStateException("Comparison prompt/parser/review policy identity mismatch");
        }
        if (corrective && !manifest.path("generationConfig").equals(JSON.valueToTree(Map.of("temperature",1,
                "candidateCount",1,"maxOutputTokens",1024,"responseMimeType","application/json","apiMode",GeminiAdapter.API_MODE)))) {
            throw new IllegalStateException("Comparison generation configuration identity mismatch");
        }
        var frozenPrice=JSON.treeToValue(manifest.path("price"),ModelRouter.Price.class);
        JsonNode http=manifest.path("httpControls");
        boolean observableAttempts="HTTP_1_1".equals(http.path("version").asString()) && "NEVER".equals(http.path("redirects").asString())
            && "1".equals(http.path("retryLimit").asString()) && http.path("disableRetryConnect").asBoolean(false)
            && http.has("enableAllMethodRetry") && !http.path("enableAllMethodRetry").asBoolean(true);
        List<JsonNode> rows=Files.readAllLines(directory.resolve("attempts.jsonl"),StandardCharsets.UTF_8).stream().filter(s->!s.isBlank()).map(JSON::readTree).toList();
        Path reviewPath=directory.resolve("content-review.json");
        byte[] reviewBytes=Files.exists(reviewPath)?Files.readAllBytes(reviewPath):null;
        Map<String,ComparisonReview.Verdict> review=reviewBytes==null?Map.of():corrective
            ? ComparisonReview.verify(JSON.readTree(reviewBytes),rows,digest(fixture),reviewPolicyDigest())
            : ComparisonReview.verify(JSON.readTree(reviewBytes),rows);
        String reviewDigest=reviewBytes==null?"unavailable":digest(reviewBytes);
        Set<String> allowedModels=strings(specification.path("models"));
        for(JsonNode row:rows) if (!allowedModels.contains(row.path("model").asString()) || row.path("result").path("attempts").asInt()<0
                || row.path("result").path("attempts").asInt()>1
                || !Set.of("customer","management","ops").contains(row.path("workload").asString())) throw new IllegalStateException("Unsupported evidence model/workload/hidden attempt count");
        Instant observed=rows.stream().map(r->Instant.parse(r.path("observedAt").asString())).max(Instant::compareTo).orElse(Instant.parse(manifest.path("startedAt").asString()));
        List<Map<String,Object>> summaries=new ArrayList<>(); List<ModelRouter.Decision> decisions=new ArrayList<>();
        List<Map<String,Object>> analysis = new ArrayList<>();
        for (String workload:List.of("customer","management","ops")) {
            List<ModelRouter.Candidate> candidates=new ArrayList<>();
            for (String model:GeminiAdapter.MODELS.stream().sorted().toList()) {
                List<JsonNode> workloadCases = new ArrayList<>();
                specification.path("cases").forEach(c -> {
                    if (workload.equals(c.path("workload").asString())) workloadCases.add(c);
                });
                int expectedCases = workloadCases.size();
                int expectedObservations = expectedCases * 3;
                var selected=rows.stream().filter(r->model.equals(r.path("model").asString()) && workload.equals(r.path("workload").asString())).toList();
                int successes=0,allPass=0,safety=0,structuredSafety=0,actualAttempts=0,reviewed=0; long totalTokens=0; int unavailableUsage=0;
                int primaryPass = 0, holdoutPass = 0, holdoutObserved = 0;
                Map<String,Integer> reviewFindings=new TreeMap<>();
                Map<String,Integer> marks=new TreeMap<>(); Map<String,Integer> failures=new TreeMap<>(); List<Long> latency=new ArrayList<>();
                Set<String> versions=new TreeSet<>(); Set<String> observations=new HashSet<>(); Map<String,Integer> counts=new TreeMap<>();
                for (JsonNode row:selected) {
                    String id=row.path("caseId").asString(); int repeat=row.path("repeat").asInt();
                    if (repeat<1 || repeat>3 || !observations.add(id+":"+repeat)) throw new IllegalStateException("Duplicate/invalid evidence repetition");
                    counts.merge(id,1,Integer::sum); JsonNode result=row.path("result"); latency.add(result.path("latencyMillis").asLong());
                    actualAttempts+=result.path("attempts").asInt();
                    ProviderProtocol.Output output=result.path("output").isNull()?null:JSON.treeToValue(result.path("output"),ProviderProtocol.Output.class);
                    JsonNode testCase=null; for (JsonNode c:specification.path("cases")) if (id.equals(c.path("id").asString())) testCase=c;
                    if (testCase==null || !workload.equals(testCase.path("workload").asString())) throw new IllegalStateException("Unknown evidence case");
                    verifyIdentity(specification,testCase,row,output);
                    var grades=grade(testCase,output); grades.forEach((name,pass)->marks.merge(name,pass?1:0,Integer::sum));
                    var verdict=review.get(ComparisonReview.identity(row));
                    if(verdict!=null) { reviewed++; verdict.findings().forEach(f->reviewFindings.merge(f,1,Integer::sum)); }
                    boolean oraclePass = grades.values().stream().allMatch(Boolean::booleanValue);
                    if (testCase.path("holdout").asBoolean(false)) {
                        holdoutObserved++;
                        if (oraclePass && verdict != null && verdict.pass()) holdoutPass++;
                    } else if (oraclePass) primaryPass++;
                    Map<String,Object> caseAnalysis = new LinkedHashMap<>();
                    caseAnalysis.put("identity", ComparisonReview.identity(row));
                    caseAnalysis.put("structuredOracle", grades);
                    caseAnalysis.put("providerFailure", result.path("failure"));
                    caseAnalysis.put("usageStatus", result.path("usage").path("status"));
                    caseAnalysis.put("contentFindings", verdict == null ? List.of("review_unavailable") : verdict.findings());
                    caseAnalysis.put("reasonCode", verdict == null ? "review_unavailable" : verdict.reasonCode());
                    analysis.add(caseAnalysis);
                    if (output!=null) {
                        successes++; if (grades.values().stream().allMatch(Boolean::booleanValue)) allPass++;
                        boolean structuredViolation=!grades.get("targetParameters") || !grades.get("supportedClaims") || !grades.get("productOutcome") || !grades.get("policyRejection") || !grades.get("safeProse");
                        if(structuredViolation) structuredSafety++;
                        if(structuredViolation || (verdict!=null && verdict.safetyViolation())) safety++;
                    }
                    if (result.path("failure").isString()) failures.merge(result.path("failure").asString(),1,Integer::sum);
                    if ("measured".equals(result.path("usage").path("status").asString())) totalTokens+=result.path("usage").path("total").asLong(); else unavailableUsage++;
                    if (result.path("resolvedVersion").isString()) versions.add(result.path("resolvedVersion").asString());
                }
                latency.sort(Long::compare); long p95=latency.isEmpty()?0:latency.get((int)Math.ceil(latency.size()*0.95)-1);
                BigDecimal quality=BigDecimal.valueOf(allPass).divide(BigDecimal.valueOf(expectedObservations),6,RoundingMode.HALF_EVEN);
                boolean complete=selected.size()==expectedObservations && actualAttempts==expectedObservations
                    && counts.size()==expectedCases && workloadCases.stream().allMatch(c -> counts.getOrDefault(c.path("id").asString(),0)==3);
                int expectedHoldout = (int)workloadCases.stream().filter(c -> c.path("holdout").asBoolean(false)).count() * 3;
                BigDecimal primaryQuality = BigDecimal.valueOf(primaryPass).divide(BigDecimal.valueOf(12),6,RoundingMode.HALF_EVEN);
                boolean holdoutGate = expectedHoldout == 0 || (holdoutObserved == expectedHoldout && holdoutPass == expectedHoldout);
                Map<String,Object> summary=new LinkedHashMap<>(); summary.put("workload",workload); summary.put("model",model);
                summary.put("attempts",actualAttempts); summary.put("observations",selected.size()); summary.put("successfulStructuredResults",successes); summary.put("allOraclePass",allPass);
                summary.put("quality",quality); summary.put("safetyViolations",safety); summary.put("p95Millis",p95);
                summary.put("structuredSafetyViolations",structuredSafety); summary.put("contentReviewedObservations",reviewed);
                summary.put("contentReviewStatus",reviewed==selected.size() && reviewBytes!=null?"reviewed":"unavailable"); summary.put("contentReviewFindings",reviewFindings);
                summary.put("latencyMinMillis",latency.isEmpty()?0:latency.getFirst()); summary.put("latencyMaxMillis",latency.isEmpty()?0:latency.getLast());
                summary.put("observedTotalTokens",totalTokens); summary.put("usageUnavailableAttempts",unavailableUsage);
                summary.put("costStatus",unavailableUsage==0?"estimated":"unavailable"); summary.put("estimatedObservedDollars",BigDecimal.ZERO);
                summary.put("failures",failures); summary.put("oracleMarks",marks); summary.put("resolvedVersions",versions);
                summary.put("complete",complete); summary.put("fallback","unsupported; not executed"); summaries.add(summary);
                summary.put("primaryOraclePass", primaryPass);
                summary.put("primaryQuality", primaryQuality);
                summary.put("holdoutExpected", expectedHoldout);
                summary.put("holdoutOracleAndContentPass", holdoutPass);
                summary.put("holdoutGate", holdoutGate);
                // Additional cases cannot dilute the original floor or approve an unreviewed adversarial answer.
                boolean qualityAvailable = reviewed==selected.size() && reviewBytes!=null && holdoutGate
                    && (!corrective || primaryQuality.compareTo(ModelRouter.Policy.bounded().qualityFloor())>=0);
                var measurement=new ModelRouter.Measurement(revision+"/manifest-"+digest(manifestBytes)+"/review-"+reviewDigest,workload,
                    complete?expectedCases:0,3,actualAttempts,qualityAvailable?quality:null,safety,BigDecimal.valueOf(p95),unavailableUsage==0?BigDecimal.ZERO:null,
                    unavailableUsage==0?"estimated":"unavailable",observed);
                candidates.add(new ModelRouter.Candidate(model,"gemini",model,GeminiAdapter.API_MODE,GeminiAdapter.ENDPOINT,versions.size()==1?versions.iterator().next():null,true,1048576,65536,
                    new ModelRouter.Controls(true,true,observableAttempts,true,Set.of(ModelRouter.DataClass.SYNTHETIC),
                        corrective ? (manifest.path("training").isBoolean() ? manifest.path("training").asBoolean() : null) : true,
                        null,null),frozenPrice,measurement));
            }
            var snapshot=new ModelRouter.Snapshot(revision+"/"+manifest.path("transportRevision").asString("unavailable"),workload,new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,null,null,50000,1024),
                candidates,ModelRouter.Policy.bounded(),new ModelRouter.Remaining(1,60000,BigDecimal.ZERO,observed,observed.plusSeconds(45)));
            var decision=new ModelRouter().route(snapshot); if (!new ModelRouter().replay(decision)) throw new IllegalStateException("Routing replay mismatch"); decisions.add(decision);
        }
        Files.write(directory.resolve("summary.json"),JSON.writeValueAsBytes(summaries)); Files.write(directory.resolve("routing.json"),JSON.writeValueAsBytes(decisions));
        Files.write(directory.resolve("case-analysis.json"),JSON.writeValueAsBytes(analysis));
        Map<String,Object> recalculation=new LinkedHashMap<>();
        recalculation.put("contentReviewRevision",ComparisonReview.REVISION); recalculation.put("contentReviewSha256",reviewDigest);
        recalculation.put("actualSourceDigests",manifest.path("sourceDigests"));
        Map<String,String> calculationSources=new TreeMap<>();
        for(String name:List.of("ModelComparisonRunner","ComparisonReview")) calculationSources.put(name,digest(Files.readAllBytes(Path.of("src/test/java/com/slotq/ai/router/"+name+".java"))));
        recalculation.put("calculationSourceDigests",calculationSources);
        recalculation.put("structuredOracleUnchanged",true); recalculation.put("budgetKind","fresh bounded routing probe; not comparison admission or a live Run");
        Files.write(directory.resolve("recalculation.json"),JSON.writeValueAsBytes(recalculation));
        System.out.println("recalculated "+rows.size()+" recorded observations ("+manifest.path("evidenceKind").asString()+"); routing snapshot replay verified");
    }
    static ModelRouter.Price price() { return price(Instant.now()); }
    private static ModelRouter.Price price(Instant observed) { return new ModelRouter.Price("gemini-free-standard-2026-10-10","free-attested",BigDecimal.ZERO,BigDecimal.ZERO,"https://ai.google.dev/gemini-api/docs/pricing",observed); }
    private static Set<String> strings(JsonNode array) { Set<String> values=new HashSet<>(); array.forEach(v->values.add(v.asString())); return values; }
    static void verifyIdentity(JsonNode specification,JsonNode testCase,JsonNode row,ProviderProtocol.Output output) throws Exception {
        var request=request(specification,testCase);
        if (!digest(request.prompt().getBytes(StandardCharsets.UTF_8)).equals(row.path("requestSha256").asString())
                || !digest(JSON.writeValueAsBytes(ProviderProtocol.schema(request))).equals(row.path("requestSchemaSha256").asString()))
            throw new IllegalStateException("Evidence request identity mismatch");
        if(output!=null) {
            String raw=row.path("rawStructuredResponse").path("candidates").get(0).path("texts").get(0).asString();
            if(!ProviderProtocol.parse(raw,request).equals(output)) throw new IllegalStateException("Raw/parsed evidence mismatch");
            if ("m7-comparison-v3".equals(specification.path("revision").asString())
                    && !row.path("result").path("usage").equals(row.path("rawStructuredResponse").path("usage"))) {
                throw new IllegalStateException("Raw/parsed usage identity mismatch");
            }
        }
    }
    public static ProviderProtocol.Request request(JsonNode specification,JsonNode testCase) {
        JsonNode fixture=testCase.path("fixture"); Set<String> sources=new TreeSet<>(),claims=new TreeSet<>();
        if(fixture.path("knowledge").path("source").isString()) sources.add(fixture.path("knowledge").path("source").asString());
        if(fixture.path("knowledge").path("fact").isString()) claims.add(fixture.path("knowledge").path("fact").asString());
        if(fixture.path("product").path("fact").isString()) claims.add(fixture.path("product").path("fact").asString());
        fixture.path("facts").forEach(f->claims.add(f.asString()));
        String template = specification.path("workloadPrompts").path(testCase.path("workload").asString()).asString(specification.path("prompt").asString());
        String prompt=template+"\nINPUT: "+testCase.path("input").asString()+"\nSYNTHETIC FIXTURE: "+JSON.writeValueAsString(fixture)
            +"\nKNOWN SOURCES: "+JSON.writeValueAsString(sources)+"\nKNOWN CLAIMS: "+JSON.writeValueAsString(claims);
        return new ProviderProtocol.Request(prompt,1024,sources,claims);
    }
    private static String key() throws Exception {
        List<String> values=Files.readAllLines(Path.of("../.env.local"),StandardCharsets.UTF_8).stream()
            .map(s->s.replace("\uFEFF","" )).filter(s->s.startsWith("GEMINI_API_KEY=")).map(s->s.substring(15).strip()).toList();
        if (values.size()!=1 || values.getFirst().isBlank()) throw new IllegalStateException("Local provider credential unavailable");
        return values.getFirst();
    }
    private static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String git(String... args) throws Exception {
        List<String> command=new ArrayList<>(List.of("git","-c","safe.directory=C:/dev/slotq")); command.addAll(List.of(args));
        Process p=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();
        if (p.waitFor()!=0) throw new IllegalStateException("Source revision unavailable"); return output;
    }
}
