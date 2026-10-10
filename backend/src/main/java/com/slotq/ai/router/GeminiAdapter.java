package com.slotq.ai.router;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;

/** Fixed REST origin, one observable HTTP attempt, no hosted tools, redirect, retry or fallback. */
public final class GeminiAdapter {
    public static final String ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/";
    public static final String API_MODE = "generateContent-standard-free";
    public static final Set<String> MODELS = Set.of("gemini-3.5-flash-lite", "gemini-3.1-flash-lite");
    private static final int MAX_BODY = 65536;
    private static final Pattern SECRET = Pattern.compile("AIza[A-Za-z0-9_-]{20,}|(?i:Bearer\\s+\\S+|(?:api[_-]?key|idempotency[_-]?key|credential|password)[\"']?\\s*[:=]\\s*[\"']?\\S+)");
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(32).maxStringLength(16384).maxNumberLength(32).build()).build()).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Transport transport;
    private final Secret credential;
    private final Consumer<Map<String,Object>> evidence;
    public static final class Secret {
        private final String value;
        public Secret(String value) {
            if (value == null || value.isBlank() || value.length() > 256 || value.contains("\n") || value.contains("\r"))
                throw new IllegalArgumentException("Provider credential unavailable");
            this.value=value;
        }
        @Override public String toString() { return "[redacted]"; }
    }
    public enum Failure { TEMPORARY_OVERLOAD, RATE_LIMIT, AUTH_CONFIGURATION, ACCOUNT_QUOTA, POLICY_REFUSAL,
        TIMEOUT_REMOTE_UNKNOWN, TRANSPORT_REMOTE_UNKNOWN, MALFORMED, QUALITY_FAILURE, SLOTQ_REJECTION }
    public record Usage(Long input, Long output, Long thinking, Long total, String status) { }
    public record Result(ProviderProtocol.Output output, Failure failure, int attempts, long latencyMillis,
                         Usage usage, String costStatus, String resolvedVersion, Integer httpStatus,
                         String disposition) { }
    record Wire(int status, byte[] body) { }
    @FunctionalInterface interface Transport { Wire send(URI uri, byte[] body, Secret credential, long timeoutMillis) throws Exception; }
    public GeminiAdapter(HttpClient client, Secret credential) { this(transport(client), credential); }
    GeminiAdapter(HttpClient client, Secret credential, Consumer<Map<String,Object>> evidence) { this(transport(client),credential,evidence); }
    GeminiAdapter(Transport transport, Secret credential) { this(transport,credential,ignored -> { }); }
    private GeminiAdapter(Transport transport, Secret credential, Consumer<Map<String,Object>> evidence) {
        this.transport=Objects.requireNonNull(transport); this.credential=Objects.requireNonNull(credential); this.evidence=Objects.requireNonNull(evidence);
    }

    public Result generate(String model, ModelRouter.Classification disclosure, ProviderProtocol.Request request, ProviderBudget budget) {
        Usage unknown = new Usage(null,null,null,null,"unavailable");
        if (!MODELS.contains(model) || disclosure == null || disclosure.dataClass() != ModelRouter.DataClass.SYNTHETIC
                || !disclosure.disclosureApproved() || !disclosure.trainingAllowed() || disclosure.requiredRegion() != null
                || disclosure.maximumRetentionDays() != null || request == null || request.prompt() == null
                || request.maximumOutputTokens() < 1 || request.maximumOutputTokens() > 2048 || unsafe(request.prompt()))
            return failed(Failure.SLOTQ_REJECTION,0,0,unknown,null,null);
        byte[] body = json.writeValueAsBytes(Map.of("contents",List.of(Map.of("role","user","parts",List.of(Map.of("text",request.prompt())))),
            "generationConfig",Map.of("temperature",1,"candidateCount",1,"maxOutputTokens",request.maximumOutputTokens(),
                "responseMimeType","application/json","responseJsonSchema",ProviderProtocol.schema(request))));
        // Conservative byte-based reservation includes the serialized schema and envelope; observed overrun poisons the budget.
        long reserved = (long)body.length * 2 + 1024 + request.maximumOutputTokens();
        if (body.length > 16384 || unsafe(new String(body,StandardCharsets.UTF_8))
                || disclosure.inputTokens() < reserved-request.maximumOutputTokens()
                || disclosure.outputTokens() < request.maximumOutputTokens()) return failed(Failure.SLOTQ_REJECTION,0,0,unknown,null,null);
        long timeout;
        try { timeout=budget.begin(reserved); } catch (RuntimeException rejected) { return failed(Failure.SLOTQ_REJECTION,0,0,unknown,null,null); }
        long started=System.nanoTime(); Usage usage=unknown; String version=null; Integer status=null;
        try {
            Wire wire=transport.send(URI.create(ENDPOINT+model+":generateContent"),body,credential,timeout); status=wire.status();
            if (wire.body().length>MAX_BODY) return failed(Failure.MALFORMED,1,elapsed(started),usage,version,status);
            if (status != 200) return failed(normalize(status, wire.body()),1,elapsed(started),usage,version,status);
            String payload=new String(wire.body(),StandardCharsets.UTF_8);
            if (unsafe(payload)) return failed(Failure.MALFORMED,1,elapsed(started),usage,version,status);
            JsonNode root=json.readTree(payload); usage=usage(root.path("usageMetadata"));
            version=root.path("modelVersion").isString()?root.path("modelVersion").asString():null;
            if (version != null && (version.length()>100 || !version.matches("[A-Za-z0-9._-]+"))) version=null;
            // Only the opt-in harness installs a sink. No response/account identifier, errors, headers or credentials are captured.
            Map<String,Object> captured=new LinkedHashMap<>(); captured.put("modelVersion",version); captured.put("usage",usage);
            List<Map<String,Object>> capturedCandidates=new ArrayList<>();
            for (JsonNode c:root.path("candidates")) {
                List<String> texts=new ArrayList<>(); for (JsonNode part:c.path("content").path("parts"))
                    if (part.path("text").isString() && !part.path("thought").asBoolean(false)) texts.add(part.path("text").asString());
                capturedCandidates.add(Map.of("finishReason",c.path("finishReason").asString("unavailable"),"texts",texts));
            }
            captured.put("candidates",capturedCandidates);
            evidence.accept(Collections.unmodifiableMap(captured));
            if (root.path("promptFeedback").has("blockReason")) return failed(Failure.POLICY_REFUSAL,1,elapsed(started),usage,version,status);
            JsonNode candidates=root.path("candidates");
            if (!candidates.isArray() || candidates.size()!=1) return failed(Failure.MALFORMED,1,elapsed(started),usage,version,status);
            JsonNode candidate=candidates.get(0);
            String finish=candidate.path("finishReason").asString("");
            if (Set.of("SAFETY","RECITATION","BLOCKLIST","PROHIBITED_CONTENT","SPII").contains(finish))
                return failed(Failure.POLICY_REFUSAL,1,elapsed(started),usage,version,status);
            if (!"STOP".equals(finish)) return failed(Failure.MALFORMED,1,elapsed(started),usage,version,status);
            JsonNode parts=candidate.path("content").path("parts");
            if (!parts.isArray() || parts.size()!=1 || !parts.get(0).path("text").isString()
                    || parts.get(0).path("thought").asBoolean(false) || parts.get(0).has("functionCall"))
                return failed(Failure.MALFORMED,1,elapsed(started),usage,version,status);
            var output=ProviderProtocol.parse(parts.get(0).path("text").asString(),request);
            if (unsafe(json.writeValueAsString(output))) return failed(Failure.MALFORMED,1,elapsed(started),usage,version,status);
            return new Result(output,null,1,elapsed(started),usage,"measured".equals(usage.status())?"estimated":"unavailable",version,status,"terminal_success");
        } catch (HttpTimeoutException failure) { return failed(Failure.TIMEOUT_REMOTE_UNKNOWN,1,elapsed(started),usage,version,status);
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); return failed(Failure.TRANSPORT_REMOTE_UNKNOWN,1,elapsed(started),usage,version,status);
        } catch (IllegalArgumentException failure) { return failed(Failure.MALFORMED,1,elapsed(started),usage,version,status);
        } catch (Exception failure) { return failed(status == null ? Failure.TRANSPORT_REMOTE_UNKNOWN : Failure.MALFORMED,1,elapsed(started),usage,version,status);
        } finally { budget.end(reserved,usage.total()); }
    }
    public static String disposition(Failure failure) {
        return switch (failure) {
            case POLICY_REFUSAL -> "terminal_refusal";
            case AUTH_CONFIGURATION, ACCOUNT_QUOTA -> "terminal_configuration_unavailable";
            case SLOTQ_REJECTION -> "terminal_slotq_rejection";
            case QUALITY_FAILURE -> "terminal_quality_failure";
            default -> "terminal_unavailable";
        };
    }
    static Failure normalize(int status, byte[] body) {
        if (status==401 || status==403 || status==400 || status==404) return Failure.AUTH_CONFIGURATION;
        if (status==429) {
            // Only classification markers are inspected. Uncontrolled error text never leaves this boundary.
            String text=new String(body,StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (text.contains("perday") || text.contains("per_day") || text.contains("billing")
                    || text.contains("quotaValue\":\"0\"".toLowerCase(Locale.ROOT)) || text.contains("quotaValue\":0".toLowerCase(Locale.ROOT)))
                return Failure.ACCOUNT_QUOTA;
            return Failure.RATE_LIMIT;
        }
        if (status>=500) return Failure.TEMPORARY_OVERLOAD;
        return Failure.AUTH_CONFIGURATION;
    }
    private boolean unsafe(String content) {
        for(int pass=0;pass<3;pass++) {
            if (content.contains(credential.value) || SECRET.matcher(content).find()) return true;
            String decoded=UNICODE_ESCAPE.matcher(content).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(String.valueOf((char)Integer.parseInt(m.group(1),16))));
            if (decoded.equals(content)) return false; content=decoded;
        }
        return content.contains(credential.value) || SECRET.matcher(content).find();
    }
    private static long elapsed(long start) { return Math.max(1,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)); }
    private static Result failed(Failure failure,int attempts,long latency,Usage usage,String version,Integer status) {
        return new Result(null,failure,attempts,latency,usage,"measured".equals(usage.status())?"estimated":"unavailable",version,status,disposition(failure));
    }
    private static Usage usage(JsonNode node) {
        Long input=number(node,"promptTokenCount"), output=number(node,"candidatesTokenCount"), total=number(node,"totalTokenCount");
        Long thinking=number(node,"thoughtsTokenCount");
        if (input==null || output==null || total==null || input>1048576 || output>65536 || total>1200000
                || (thinking!=null && thinking>65536) || total<input+output || (thinking!=null && total<input+output+thinking))
            return new Usage(null,null,null,null,"unavailable");
        return new Usage(input,output,thinking,total,"measured");
    }
    private static Long number(JsonNode node,String key) { var n=node.path(key); return n.isIntegralNumber() && n.canConvertToLong() && n.asLong()>=0 ? n.asLong() : null; }
    static Transport transport(HttpClient client) {
        if (client.followRedirects()!=HttpClient.Redirect.NEVER || client.version()!=HttpClient.Version.HTTP_1_1
                || !"1".equals(System.getProperty("jdk.httpclient.redirects.retrylimit")) || !Boolean.getBoolean("jdk.httpclient.disableRetryConnect")
                || Boolean.getBoolean("jdk.httpclient.enableAllMethodRetry") || client.authenticator().isPresent()
                || client.cookieHandler().isPresent() || !System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank())
            throw new IllegalStateException("Provider HTTP retry/redirect gate");
        return (uri,body,credential,timeout) -> {
            long deadlineNanos=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeout);
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofMillis(timeout))
                .header("x-goog-api-key",credential.value).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var subscriber=new java.util.concurrent.atomic.AtomicReference<BoundedBody>();
            try {
                var response=client.send(request, ignored -> { var bodyReader=new BoundedBody(deadlineNanos); subscriber.set(bodyReader); return bodyReader; });
                return new Wire(response.statusCode(),response.body());
            } catch(IOException failure) {
                var bodyReader=subscriber.get();
                if(bodyReader!=null && bodyReader.timedOut) throw new HttpTimeoutException("Provider response deadline");
                throw failure;
            }
        };
    }
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>(); final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final CompletableFuture<byte[]> completion; volatile Flow.Subscription subscription; volatile boolean timedOut;
        BoundedBody(long deadlineNanos) {
            completion=result.orTimeout(Math.max(1,deadlineNanos-System.nanoTime()),TimeUnit.NANOSECONDS).handle((body,error)->{
                if(error==null) return body;
                // Completion is published only after local subscription cancellation; remote completion remains unknown.
                timedOut=error instanceof TimeoutException;
                var active=subscription; if(active!=null) active.cancel();
                if(error instanceof TimeoutException) throw new CompletionException(new HttpTimeoutException("Provider response deadline"));
                throw new CompletionException(error);
            });
        }
        public CompletionStage<byte[]> getBody() { return completion; }
        public void onSubscribe(Flow.Subscription s) { subscription=s; if(result.isDone()) s.cancel(); else s.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk:chunks) {
                if(result.isDone()) return;
                if (bytes.size()+chunk.remaining()>MAX_BODY) { subscription.cancel(); result.completeExceptionally(new IOException("Provider body bound")); return; }
                byte[] data=new byte[chunk.remaining()]; chunk.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
