package com.slotq.ai.router;

import java.math.*;
import java.net.http.HttpClient;
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
    private static final Path OUTPUT=Path.of("../build/model-router-comparison");
    public static void main(String[] args) throws Exception {
        if (Boolean.getBoolean("slotq.modelComparison.recalculate")) { recalculate(); return; }
        if (!Boolean.getBoolean("slotq.modelComparison.optIn") || !Boolean.getBoolean("slotq.modelComparison.freeTier")
                || Boolean.getBoolean("slotq.modelComparison.paidCalls")) throw new IllegalStateException("Explicit free-only provider opt-in required");
        byte[] fixture=ModelComparisonRunner.class.getResourceAsStream("/model-router/comparison-v2.json").readAllBytes();
        JsonNode specification=JSON.readTree(fixture);
        if (specification.path("maximumTotalDollars").asInt()!=0 || specification.path("maximumCalls").asInt()!=72
                || specification.path("repeats").asInt()!=3) throw new IllegalStateException("Frozen comparison bounds changed");
        Files.createDirectories(OUTPUT);
        if (Files.exists(OUTPUT.resolve("attempts.jsonl"))) throw new IllegalStateException("Evidence already exists; use a new ignored output directory or recalculate");
        String key=key();
        List<Map<String,Object>> captured=new ArrayList<>();
        var adapter=new GeminiAdapter(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build(),new GeminiAdapter.Secret(key),captured::add);
        Instant deadline=Instant.now().plus(Duration.ofMinutes(30));
        Map<String,Object> manifest=new LinkedHashMap<>();
        manifest.put("evidenceKind","actual-provider/synthetic-tool-fixtures");
        manifest.put("specification",JSON.readValue(fixture,Object.class));
        manifest.put("specificationSha256",digest(fixture)); manifest.put("schemaSha256",digest(ProviderProtocol.SCHEMA_JSON.getBytes(StandardCharsets.UTF_8)));
        manifest.put("promptRevision","m7-prompt-v2"); manifest.put("parserRevision",ProviderProtocol.REVISION);
        manifest.put("sourceRevision",git("rev-parse","HEAD")); manifest.put("sourceDirty",!git("status","--porcelain").isBlank());
        Map<String,String> sources=new TreeMap<>();
        for (String name:List.of("GeminiAdapter","ProviderBudget","ProviderProtocol","ModelRouter"))
            sources.put(name,digest(Files.readAllBytes(Path.of("src/main/java/com/slotq/ai/router/"+name+".java"))));
        sources.put("ModelComparisonRunner",digest(Files.readAllBytes(Path.of("src/test/java/com/slotq/ai/router/ModelComparisonRunner.java"))));
        manifest.put("sourceDigests",sources); manifest.put("java",System.getProperty("java.version"));
        manifest.put("os",System.getProperty("os.name")+" "+System.getProperty("os.arch"));
        manifest.put("gradle",System.getProperty("slotq.modelComparison.gradleVersion"));
        Instant startedAt=Instant.now();
        manifest.put("startedAt",startedAt.toString()); manifest.put("deadline",deadline.toString());
        manifest.put("tier","user-attested-free/no-paid-calls"); manifest.put("price",price(startedAt));
        manifest.put("training",true); manifest.put("region","unavailable"); manifest.put("retention","unavailable");
        manifest.put("dataControlSource","https://ai.google.dev/gemini-api/terms");
        manifest.put("retryFallback","none; all failures terminal; no cross-provider support");
        manifest.put("transportRevision","m7-http-v1");
        manifest.put("httpControls",Map.of("version","HTTP_1_1","redirects","NEVER","retryLimit",System.getProperty("jdk.httpclient.redirects.retrylimit"),
            "disableRetryConnect",Boolean.getBoolean("jdk.httpclient.disableRetryConnect"),"enableAllMethodRetry",Boolean.getBoolean("jdk.httpclient.enableAllMethodRetry")));
        manifest.put("generationConfig",Map.of("temperature",1,"candidateCount",1,"maxOutputTokens",1024,"responseMimeType","application/json","apiMode",GeminiAdapter.API_MODE));
        Files.write(OUTPUT.resolve("manifest.json"),JSON.writeValueAsBytes(manifest));
        int calls=0; boolean stop=false;
        outer: for (JsonNode testCase:specification.path("cases")) for (int repeat=1;repeat<=3;repeat++) for (JsonNode modelNode:specification.path("models")) {
            if (++calls>72 || !deadline.isAfter(Instant.now())) break outer;
            String model=modelNode.asString();
            ProviderProtocol.Request request=request(specification,testCase);
            var classification=new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,null,null,50000,1024);
            var budget=new ProviderBudget(Clock.systemUTC(),Instant.now().plusSeconds(45),1,60000);
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
            System.out.println("actual-provider "+calls+"/72 "+testCase.path("id").asString()+" "+model+" "+(result.failure()==null?"structured-result":result.failure()));
            if (result.failure()==GeminiAdapter.Failure.ACCOUNT_QUOTA || result.failure()==GeminiAdapter.Failure.AUTH_CONFIGURATION) { stop=true; break outer; }
            if (calls<72) Thread.sleep(7000);
        }
        recalculate();
        if (stop || calls<72) throw new IllegalStateException("Actual-provider comparison incomplete: account/quota/deadline blocker");
    }

    static Map<String,Boolean> grade(JsonNode testCase,ProviderProtocol.Output output) {
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
        byte[] manifestBytes=Files.readAllBytes(OUTPUT.resolve("manifest.json"));
        JsonNode manifest=JSON.readTree(manifestBytes); JsonNode specification=manifest.path("specification");
        byte[] fixture=ModelComparisonRunner.class.getResourceAsStream("/model-router/comparison-v2.json").readAllBytes();
        if (!digest(fixture).equals(manifest.path("specificationSha256").asString()) || !JSON.readTree(fixture).equals(specification)
                || !digest(ProviderProtocol.SCHEMA_JSON.getBytes(StandardCharsets.UTF_8)).equals(manifest.path("schemaSha256").asString()))
            throw new IllegalStateException("Comparison fixture/schema identity mismatch; use the recorded source profile");
        var frozenPrice=JSON.treeToValue(manifest.path("price"),ModelRouter.Price.class);
        JsonNode http=manifest.path("httpControls");
        boolean observableAttempts="HTTP_1_1".equals(http.path("version").asString()) && "NEVER".equals(http.path("redirects").asString())
            && "1".equals(http.path("retryLimit").asString()) && http.path("disableRetryConnect").asBoolean(false)
            && http.has("enableAllMethodRetry") && !http.path("enableAllMethodRetry").asBoolean(true);
        List<JsonNode> rows=Files.readAllLines(OUTPUT.resolve("attempts.jsonl"),StandardCharsets.UTF_8).stream().filter(s->!s.isBlank()).map(JSON::readTree).toList();
        Path reviewPath=OUTPUT.resolve("content-review.json");
        byte[] reviewBytes=Files.exists(reviewPath)?Files.readAllBytes(reviewPath):null;
        Map<String,ComparisonReview.Verdict> review=reviewBytes==null?Map.of():ComparisonReview.verify(JSON.readTree(reviewBytes),rows);
        String reviewDigest=reviewBytes==null?"unavailable":digest(reviewBytes);
        Set<String> allowedModels=strings(specification.path("models"));
        for(JsonNode row:rows) if (!allowedModels.contains(row.path("model").asString()) || row.path("result").path("attempts").asInt()<0
                || row.path("result").path("attempts").asInt()>1) throw new IllegalStateException("Unsupported evidence model/hidden attempt count");
        Instant observed=rows.stream().map(r->Instant.parse(r.path("observedAt").asString())).max(Instant::compareTo).orElse(Instant.parse(manifest.path("startedAt").asString()));
        List<Map<String,Object>> summaries=new ArrayList<>(); List<ModelRouter.Decision> decisions=new ArrayList<>();
        for (String workload:List.of("customer","management","ops")) {
            List<ModelRouter.Candidate> candidates=new ArrayList<>();
            for (String model:GeminiAdapter.MODELS.stream().sorted().toList()) {
                var selected=rows.stream().filter(r->model.equals(r.path("model").asString()) && workload.equals(r.path("workload").asString())).toList();
                int successes=0,allPass=0,safety=0,structuredSafety=0,actualAttempts=0,reviewed=0; long totalTokens=0; int unavailableUsage=0;
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
                BigDecimal quality=BigDecimal.valueOf(allPass).divide(BigDecimal.valueOf(12),6,RoundingMode.HALF_EVEN);
                boolean complete=selected.size()==12 && actualAttempts==12 && counts.size()==4 && counts.values().stream().allMatch(n->n==3);
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
                var measurement=new ModelRouter.Measurement("m7-comparison-v2/manifest-"+digest(manifestBytes)+"/review-"+reviewDigest,workload,
                    complete?4:0,3,actualAttempts,reviewed==selected.size() && reviewBytes!=null?quality:null,safety,BigDecimal.valueOf(p95),unavailableUsage==0?BigDecimal.ZERO:null,
                    unavailableUsage==0?"estimated":"unavailable",observed);
                candidates.add(new ModelRouter.Candidate(model,"gemini",model,GeminiAdapter.API_MODE,GeminiAdapter.ENDPOINT,versions.size()==1?versions.iterator().next():null,true,1048576,65536,
                    new ModelRouter.Controls(true,true,observableAttempts,true,Set.of(ModelRouter.DataClass.SYNTHETIC),true,null,null),frozenPrice,measurement));
            }
            var snapshot=new ModelRouter.Snapshot("m7-comparison-v2/"+manifest.path("transportRevision").asString("unavailable"),workload,new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,null,null,50000,1024),
                candidates,ModelRouter.Policy.bounded(),new ModelRouter.Remaining(1,60000,BigDecimal.ZERO,observed,observed.plusSeconds(45)));
            var decision=new ModelRouter().route(snapshot); if (!new ModelRouter().replay(decision)) throw new IllegalStateException("Routing replay mismatch"); decisions.add(decision);
        }
        Files.write(OUTPUT.resolve("summary.json"),JSON.writeValueAsBytes(summaries)); Files.write(OUTPUT.resolve("routing.json"),JSON.writeValueAsBytes(decisions));
        Map<String,Object> recalculation=new LinkedHashMap<>();
        recalculation.put("contentReviewRevision",ComparisonReview.REVISION); recalculation.put("contentReviewSha256",reviewDigest);
        recalculation.put("actualSourceDigests",manifest.path("sourceDigests"));
        Map<String,String> calculationSources=new TreeMap<>();
        for(String name:List.of("ModelComparisonRunner","ComparisonReview")) calculationSources.put(name,digest(Files.readAllBytes(Path.of("src/test/java/com/slotq/ai/router/"+name+".java"))));
        recalculation.put("calculationSourceDigests",calculationSources);
        recalculation.put("structuredOracleUnchanged",true); recalculation.put("budgetKind","fresh bounded routing probe; not comparison admission or a live Run");
        Files.write(OUTPUT.resolve("recalculation.json"),JSON.writeValueAsBytes(recalculation));
        System.out.println("recalculated "+rows.size()+" actual-provider records; routing snapshot replay verified");
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
        }
    }
    static ProviderProtocol.Request request(JsonNode specification,JsonNode testCase) {
        JsonNode fixture=testCase.path("fixture"); Set<String> sources=new TreeSet<>(),claims=new TreeSet<>();
        if(fixture.path("knowledge").path("source").isString()) sources.add(fixture.path("knowledge").path("source").asString());
        if(fixture.path("knowledge").path("fact").isString()) claims.add(fixture.path("knowledge").path("fact").asString());
        if(fixture.path("product").path("fact").isString()) claims.add(fixture.path("product").path("fact").asString());
        fixture.path("facts").forEach(f->claims.add(f.asString()));
        String prompt=specification.path("prompt").asString()+"\nINPUT: "+testCase.path("input").asString()+"\nSYNTHETIC FIXTURE: "+JSON.writeValueAsString(fixture)
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
