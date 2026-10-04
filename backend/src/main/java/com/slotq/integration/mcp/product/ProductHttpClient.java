package com.slotq.integration.mcp.product;

import com.slotq.auth.access.*;
import com.slotq.mcp.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import tools.jackson.databind.json.JsonMapper;

/** One fixed-origin request per explicit invocation. Neither exceptions nor timeouts trigger POST retries. */
public final class ProductHttpClient {
    private static final int MAX_BODY=65536;
    private final HttpClient http;
    private final ProductCredentialAccess credentials;
    private final JsonMapper json=JsonMapper.builder().build();
    public ProductHttpClient(HttpClient http,ProductCredentialAccess credentials) {
        if(http.followRedirects()!=HttpClient.Redirect.NEVER || !Boolean.getBoolean("jdk.httpclient.disableRetryConnect")
                || Boolean.getBoolean("jdk.httpclient.enableAllMethodRetry"))throw new IllegalStateException("Product HTTP retry/redirect gate");
        this.http=http;this.credentials=credentials;
    }
    public Result exchange(ProductHttpBinding.Prepared prepared,String query,Map<String,Object> body) {
        URI uri=query==null?prepared.uri():URI.create(prepared.uri()+"?"+query);
        var builder=HttpRequest.newBuilder(uri).timeout(prepared.responseTimeout())
            .header("Authorization","Bearer "+prepared.credential().value()).header("Accept","application/json");
        if(body==null)builder.GET();else builder.header("Content-Type","application/json")
            .header("Idempotency-Key",(String)body.get("idempotencyKey"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("slotInventoryId",body.get("slotInventoryId"),"partySize",body.get("partySize")))));
        Capture capture=new Capture(prepared.uri());
        try {
            var response=http.send(builder.build(),info->{capture.headers(info);return new BoundedBody();});
            Object data=response.body().length==0?null:json.readValue(response.body(),Object.class);
            if(response.statusCode()==201 && data instanceof Map<?,?> dto) {
                UUID id=UUID.fromString(String.valueOf(dto.get("id")));
                if(capture.target!=null && !capture.target.equals(id))throw new IOException("Product identity mismatch");
                capture.target=id;
            }
            return new Result(response.statusCode(),data,capture.target,capture.requestId,false);
        } catch(Exception failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            return new Result(null,null,capture.target,capture.requestId,true);
        } finally {
            // A response timeout is not remote exit. Co-located Auth's servlet lease is released in its finally.
            // The MCP runner retains its quota permit until that work actually leaves the Product request.
            credentials.awaitRequestEnd(prepared.credential());
        }
    }
    public record Result(Integer status,Object data,UUID knownTarget,UUID requestId,boolean unknown){}
    private static final class Capture {
        final URI route;UUID target,requestId;
        Capture(URI route){this.route=route;}
        void headers(HttpResponse.ResponseInfo info) {
            info.headers().firstValue("X-Request-ID").ifPresent(value->{try{requestId=UUID.fromString(value);}catch(IllegalArgumentException ignored){}});
            if(info.statusCode()!=201)return;
            info.headers().firstValue("Location").ifPresent(value->{
                try {
                    URI location=route.resolve(value);
                    String prefix=route.getPath().substring(0,route.getPath().length()-"holds".length());
                    if(!Objects.equals(location.getScheme(),route.getScheme()) || !Objects.equals(location.getAuthority(),route.getAuthority())
                            || location.getQuery()!=null || location.getFragment()!=null || !location.getPath().startsWith(prefix))return;
                    String id=location.getPath().substring(prefix.length());
                    if(id.length()==36)target=UUID.fromString(id);
                }catch(IllegalArgumentException ignored){}
            });
        }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<ByteBuffer> items){
            for(ByteBuffer item:items) {
                if(bytes.size()+item.remaining()>MAX_BODY){subscription.cancel();result.completeExceptionally(new IOException("Product response bound"));return;}
                byte[] chunk=new byte[item.remaining()];item.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error){result.completeExceptionally(error);}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
