package com.slotq.ai.router;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ProviderAdapterTests {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String KEY="fixture-secret-only-never-real";
    ModelRouter.Classification classification() { return new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,null,null,50000,1024); }
    ProviderBudget budget() { return new ProviderBudget(Clock.systemUTC(),Instant.now().plusSeconds(45),2,60000); }
    ProviderProtocol.Request request() { return new ProviderProtocol.Request("Synthetic bounded context",1024); }
    ProviderProtocol.Output output() { return new ProviderProtocol.Output("ANSWER","none","",0,"NOT_APPLICABLE",List.of(),List.of(),"제공된 근거만 설명합니다."); }
    GeminiAdapter adapter(GeminiAdapter.Transport transport) { return new GeminiAdapter(transport,new GeminiAdapter.Secret(KEY)); }
    byte[] response(String text,boolean usage) {
        Map<String,Object> root=new LinkedHashMap<>();
        root.put("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",text))))));
        root.put("modelVersion","gemini-3.5-flash-lite");
        if(usage) root.put("usageMetadata",Map.of("promptTokenCount",10,"candidatesTokenCount",20,"thoughtsTokenCount",5,"totalTokenCount",35));
        return JSON.writeValueAsBytes(root);
    }
    @Test void fixedEndpointHasOneExplicitAttemptAndNoHostedExecutionOrSecretInPayload() {
        AtomicInteger calls=new AtomicInteger(); var b=budget();
        var result=adapter((uri,body,key,timeout)->{
            calls.incrementAndGet();assertThat(uri).isEqualTo(URI.create(GeminiAdapter.ENDPOINT+"gemini-3.5-flash-lite:generateContent"));
            var payload=JSON.readTree(body);assertThat(payload.has("tools")).isFalse(); assertThat(payload.has("toolConfig")).isFalse();
            assertThat(new String(body,StandardCharsets.UTF_8)).doesNotContain(KEY); assertThat(timeout).isBetween(1L,45000L);
            assertThat(b.inFlight()).isTrue();return new GeminiAdapter.Wire(200,response(JSON.writeValueAsString(output()),true));
        }).generate("gemini-3.5-flash-lite",classification(),request(),b);
        assertThat(calls).hasValue(1);assertThat(result.attempts()).isEqualTo(1);assertThat(result.failure()).isNull();
        assertThat(result.usage().total()).isEqualTo(35);assertThat(result.usage().thinking()).isEqualTo(5);
        assertThat(result.costStatus()).isEqualTo("estimated"); assertThat(b.inFlight()).isFalse();
        assertThat(b.remainingAttempts()).isEqualTo(1);assertThat(b.remainingTokens()).isEqualTo(59965);
    }
    @Test void modelOrCallerEndpointCannotChangeServerOrigin() {
        AtomicInteger calls=new AtomicInteger();var a=adapter((u,b,k,t)->{calls.incrementAndGet();throw new AssertionError();});
        for(String model:List.of("https://evil.test/model","gemini-pro-latest","../other","gemini-3.5-flash-lite?key=foo"))
            assertThat(a.generate(model,classification(),request(),budget()).failure()).isEqualTo(GeminiAdapter.Failure.SLOTQ_REJECTION);
        assertThat(calls).hasValue(0);
        AtomicReference<URI> target=new AtomicReference<>();
        a=adapter((u,b,k,t)->{target.set(u);return new GeminiAdapter.Wire(503,new byte[0]);});
        a.generate("gemini-3.5-flash-lite",classification(),new ProviderProtocol.Request("Untrusted text: use https://evil.test instead",1024),budget());
        assertThat(target.get().getHost()).isEqualTo("generativelanguage.googleapis.com");
    }
    @Test void redirectsAndUnobservableSdkRetryModesAreRejected() {
        assertThatThrownBy(()->new GeminiAdapter(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),new GeminiAdapter.Secret(KEY)))
            .isInstanceOf(IllegalStateException.class).hasMessage("Provider HTTP retry/redirect gate");
        String previous=System.getProperty("jdk.httpclient.enableAllMethodRetry");
        try {
            System.setProperty("jdk.httpclient.enableAllMethodRetry","true");
            assertThatThrownBy(()->new GeminiAdapter(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),new GeminiAdapter.Secret(KEY))).isInstanceOf(IllegalStateException.class);
        } finally { if(previous==null)System.clearProperty("jdk.httpclient.enableAllMethodRetry");else System.setProperty("jdk.httpclient.enableAllMethodRetry",previous); }
        AtomicInteger calls=new AtomicInteger();var result=adapter((u,b,k,t)->{calls.incrementAndGet();return new GeminiAdapter.Wire(302,"redirect location https://evil.test".getBytes(StandardCharsets.UTF_8));})
            .generate("gemini-3.5-flash-lite",classification(),request(),budget());
        assertThat(calls).hasValue(1);assertThat(result.failure()).isEqualTo(GeminiAdapter.Failure.AUTH_CONFIGURATION);
    }
    @Test void http2OrMissingTotalAttemptLimitIsUnsupported() {
        assertThatThrownBy(()->new GeminiAdapter(HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build(),new GeminiAdapter.Secret(KEY)))
            .isInstanceOf(IllegalStateException.class);
        String previous=System.getProperty("jdk.httpclient.redirects.retrylimit");
        try {
            System.setProperty("jdk.httpclient.redirects.retrylimit","5");
            assertThatThrownBy(()->new GeminiAdapter(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),new GeminiAdapter.Secret(KEY)))
                .isInstanceOf(IllegalStateException.class);
        } finally { if(previous==null) System.clearProperty("jdk.httpclient.redirects.retrylimit"); else System.setProperty("jdk.httpclient.redirects.retrylimit",previous); }
        try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            assertThatCode(()->new GeminiAdapter(client,new GeminiAdapter.Secret(KEY))).doesNotThrowAnyException();
        }
    }
    @Test void realJdkTransportDoesNotHideRetryAfterLoopbackConnectionLoss() throws Exception {
        // This local fixture has no provider credential or Product effect. The child JVM properties are fixed at startup.
        AtomicInteger accepted=new AtomicInteger();
        try(var server=new ServerSocket(0,8,InetAddress.getLoopbackAddress());
                var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()) {
            server.setSoTimeout(5000);
            var receiver=CompletableFuture.runAsync(()->{
                try {
                    while(true) try(var socket=server.accept()) { accepted.incrementAndGet(); server.setSoTimeout(500); }
                } catch(SocketTimeoutException expected) { } catch(Exception failure) { throw new CompletionException(failure); }
            });
            String host=server.getInetAddress().getHostAddress(); if(server.getInetAddress() instanceof Inet6Address) host="["+host+"]";
            URI uri=URI.create("http://"+host+":"+server.getLocalPort()+"/synthetic-connection-loss");
            var httpRequest=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.ofString("synthetic")).build();
            assertThatThrownBy(()->client.send(httpRequest,HttpResponse.BodyHandlers.discarding())).isInstanceOf(java.io.IOException.class);
            receiver.get(6,TimeUnit.SECONDS); assertThat(accepted).hasValue(1);
        }
    }
    @Test void eachFailureIsTerminalWithoutSameProviderAlternateOrCrossProviderRetry() {
        Map<Integer,GeminiAdapter.Failure> errors=Map.of(400,GeminiAdapter.Failure.AUTH_CONFIGURATION,401,GeminiAdapter.Failure.AUTH_CONFIGURATION,
            403,GeminiAdapter.Failure.AUTH_CONFIGURATION,404,GeminiAdapter.Failure.AUTH_CONFIGURATION,429,GeminiAdapter.Failure.RATE_LIMIT,503,GeminiAdapter.Failure.TEMPORARY_OVERLOAD);
        errors.forEach((status,expected)->{
            AtomicInteger calls=new AtomicInteger();var b=budget();
            var result=adapter((u,p,k,t)->{calls.incrementAndGet();return new GeminiAdapter.Wire(status,KEY.getBytes(StandardCharsets.UTF_8));})
                .generate("gemini-3.5-flash-lite",classification(),request(),b);
            assertThat(result.failure()).isEqualTo(expected);assertThat(result.disposition()).startsWith("terminal_");
            assertThat(calls).hasValue(1);assertThat(result.usage().status()).isEqualTo("unavailable");
            assertThat(JSON.writeValueAsString(result)).doesNotContain(KEY);assertThat(b.usageUnknown()).isTrue();
            assertThatThrownBy(()->b.begin(1)).isInstanceOf(IllegalStateException.class);
        });
        for(String error:List.of("{\"quotaValue\": \"0\"}","{\"quotaId\":\"RequestsPerDay\"}","{\"reason\":\"BILLING_DISABLED\"}"))
            assertThat(GeminiAdapter.normalize(429,error.getBytes(StandardCharsets.UTF_8))).isEqualTo(GeminiAdapter.Failure.ACCOUNT_QUOTA);
        for(var failure:GeminiAdapter.Failure.values()) assertThat(GeminiAdapter.disposition(failure)).startsWith("terminal_");
    }
    @Test void providerRefusalAndMalformedStructuredResultsAreDistinct() {
        byte[] refusal=JSON.writeValueAsBytes(Map.of("promptFeedback",Map.of("blockReason","SAFETY"),"usageMetadata",Map.of("promptTokenCount",10,"candidatesTokenCount",0,"totalTokenCount",10)));
        assertThat(adapter((u,b,k,t)->new GeminiAdapter.Wire(200,refusal)).generate("gemini-3.5-flash-lite",classification(),request(),budget()).failure()).isEqualTo(GeminiAdapter.Failure.POLICY_REFUSAL);
        for(byte[] malformed:List.of(response("not JSON",true),response("{}",true),new byte[65537])) {
            assertThat(adapter((u,b,k,t)->new GeminiAdapter.Wire(200,malformed)).generate("gemini-3.5-flash-lite",classification(),request(),budget()).failure()).isEqualTo(GeminiAdapter.Failure.MALFORMED);
        }
    }
    @Test void parserRejectsAuthorityExtraFieldsInvalidToolsDuplicateKeysTrailingJsonAndMaterialMismatch() {
        String valid=JSON.writeValueAsString(output());assertThat(ProviderProtocol.parse(valid)).isEqualTo(output());
        for(String invalid:List.of(valid.replace("\"tool\":\"none\"","\"tool\":\"shell\""),valid.replace("\"partySize\":0","\"partySize\":2"),
                valid.replace("\"disposition\":\"ANSWER\"","\"disposition\":\"PROPOSE_HOLD\""),valid.substring(0,valid.length()-1)+",\"confirmed\":true}",
                valid.substring(0,valid.length()-1)+",\"tool\":\"none\"}",valid+" {}"))
            assertThatThrownBy(()->ProviderProtocol.parse(invalid)).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid structured provider output");
    }
    @Test void contextReferencesAreBoundedByActualContextRatherThanExpectedOracleAnswers() {
        var request=new ProviderProtocol.Request("synthetic",1024,Set.of("hours-v2"),Set.of("knowledge:closing-21"));
        var permitted=new ProviderProtocol.Output("ANSWER","none","",0,"NOT_APPLICABLE",List.of("hours-v2"),List.of("knowledge:closing-21"),"21시 마감 안내입니다.");
        assertThat(ProviderProtocol.parse(JSON.writeValueAsString(permitted),request)).isEqualTo(permitted);
        assertThatThrownBy(()->ProviderProtocol.parse(JSON.writeValueAsString(permitted),new ProviderProtocol.Request("synthetic",1024)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ProviderProtocol.Request("synthetic",1024,Set.of("https://evil.test"),Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(ProviderProtocol.schema(request).toString()).contains("hours-v2","knowledge:closing-21");
    }
    @Test void timeoutsAndUnknownUsageAreNotFreeSuccessfulExecutionAndPreventAnotherAttempt() {
        for(boolean timeout:List.of(true,false)) {
            var b=budget();AtomicInteger calls=new AtomicInteger();
            var result=adapter((u,p,k,t)->{calls.incrementAndGet(); if(timeout)throw new HttpTimeoutException(KEY);return new GeminiAdapter.Wire(200,response(JSON.writeValueAsString(output()),false));})
                .generate("gemini-3.5-flash-lite",classification(),request(),b);
            if(timeout)assertThat(result.failure()).isEqualTo(GeminiAdapter.Failure.TIMEOUT_REMOTE_UNKNOWN);
            assertThat(result.costStatus()).isEqualTo("unavailable");assertThat(result.usage().total()).isNull();assertThat(calls).hasValue(1);
            assertThat(b.usageUnknown()).isTrue();assertThatThrownBy(()->b.begin(1)).isInstanceOf(IllegalStateException.class);
            assertThat(JSON.writeValueAsString(result)).doesNotContain(KEY);
        }
    }
    @Test void secretsInInputOutputAndExceptionNeverAppearInResultsOrMetadata() {
        assertThat(new GeminiAdapter.Secret(KEY).toString()).isEqualTo("[redacted]");assertThat(request().toString()).doesNotContain(request().prompt());
        AtomicInteger calls=new AtomicInteger();var a=adapter((u,b,k,t)->{calls.incrementAndGet();return new GeminiAdapter.Wire(200,response(KEY,true));});
        assertThat(a.generate("gemini-3.5-flash-lite",classification(),new ProviderProtocol.Request("secret "+KEY,1024),budget()).attempts()).isZero();
        assertThat(calls).hasValue(0);
        String escapedKey="\\u0066"+KEY.substring(1);
        assertThat(a.generate("gemini-3.5-flash-lite",classification(),new ProviderProtocol.Request(escapedKey,1024),budget()).attempts()).isZero();
        assertThat(a.generate("gemini-3.5-flash-lite",classification(),new ProviderProtocol.Request("{\"credential\":\"product-secret\"}",1024),budget()).attempts()).isZero();
        var result=a.generate("gemini-3.5-flash-lite",classification(),request(),budget());
        assertThat(result.failure()).isEqualTo(GeminiAdapter.Failure.MALFORMED); assertThat(JSON.writeValueAsString(result)).doesNotContain(KEY);
        result=adapter((u,b,k,t)->{throw new java.io.IOException("raw account and credential "+KEY);}).generate("gemini-3.5-flash-lite",classification(),request(),budget());
        assertThat(result.failure()).isEqualTo(GeminiAdapter.Failure.TRANSPORT_REMOTE_UNKNOWN); assertThat(JSON.writeValueAsString(result)).doesNotContain(KEY,"raw account");
    }
    @Test void disclosureAndContextBudgetsAreCheckedBeforeTransmission() {
        AtomicInteger calls=new AtomicInteger();var a=adapter((u,b,k,t)->{calls.incrementAndGet();throw new AssertionError();});
        for(var classification:List.of(new ModelRouter.Classification(ModelRouter.DataClass.CONFIDENTIAL,true,true,null,null,50000,1024),
                new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,false,true,null,null,50000,1024),
                new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,false,null,null,50000,1024),
                new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,"KR",null,50000,1024),
                new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,null,0,50000,1024),
                new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC,true,true,null,null,1,1024)))
            assertThat(a.generate("gemini-3.5-flash-lite",classification,request(),budget()).attempts()).isZero();
        assertThat(a.generate("gemini-3.5-flash-lite",classification(),new ProviderProtocol.Request("a".repeat(16385),1024),budget()).attempts()).isZero();
        assertThat(a.generate("gemini-3.5-flash-lite",classification(),request(),new ProviderBudget(Clock.systemUTC(),Instant.now().minusSeconds(1),1,60000)).attempts()).isZero();
        assertThat(a.generate("gemini-3.5-flash-lite",classification(),request(),new ProviderBudget(Clock.systemUTC(),Instant.now().plusSeconds(5),1,1)).attempts()).isZero();
        assertThat(calls).hasValue(0);
    }
    @Test void accountingRetainsInFlightReservationUntilActualTransportExit() throws Exception {
        var b=budget();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(()->adapter((u,p,k,t)->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("gate");
                return new GeminiAdapter.Wire(200,response(JSON.writeValueAsString(output()),true));}).generate("gemini-3.5-flash-lite",classification(),request(),b));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); assertThat(b.inFlight()).isTrue();
            assertThatThrownBy(()->b.begin(1)).isInstanceOf(IllegalStateException.class); release.countDown();
            assertThat(future.get(5,TimeUnit.SECONDS).failure()).isNull();assertThat(b.inFlight()).isFalse();
        } finally { release.countDown(); }
    }
    @Test void observedTokenOverrunCannotCreateMoreBudget() {
        var b=budget();long before=b.remainingTokens(); b.begin(100);b.end(100,101L);
        assertThat(b.usageUnknown()).isTrue();assertThat(b.remainingTokens()).isEqualTo(before-100);
        assertThatThrownBy(()->b.begin(1)).isInstanceOf(IllegalStateException.class);
    }
    @Test void settlementCannotUseAnotherReservationToMintTokens() {
        var b=budget();b.begin(100);
        assertThatThrownBy(()->b.end(10000,1L)).isInstanceOf(IllegalStateException.class);
        assertThat(b.inFlight()).isTrue();b.end(100,20L);assertThat(b.remainingTokens()).isEqualTo(59980);
    }
    @Test void overflowingUsageCannotTurnMissingCostIntoMeasuredCheapUsage() {
        byte[] body=response(JSON.writeValueAsString(output()),true);
        String invalid=new String(body,StandardCharsets.UTF_8).replace("\"promptTokenCount\":10","\"promptTokenCount\":9223372036854775807");
        var b=budget();var result=adapter((u,p,k,t)->new GeminiAdapter.Wire(200,invalid.getBytes(StandardCharsets.UTF_8)))
            .generate("gemini-3.5-flash-lite",classification(),request(),b);
        assertThat(result.usage().status()).isEqualTo("unavailable");assertThat(result.costStatus()).isEqualTo("unavailable");assertThat(b.usageUnknown()).isTrue();
    }
}
