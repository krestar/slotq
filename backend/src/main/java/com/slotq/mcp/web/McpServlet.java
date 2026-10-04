package com.slotq.mcp.web;

import com.slotq.auth.access.*;
import com.slotq.mcp.*;
import jakarta.servlet.http.*;
import jakarta.servlet.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Tools-only Streamable HTTP using stable SDK wire types. JSON responses, optional GET stream rejected (405).
 * Sessions represent lifecycle only; every request separately authenticates and revalidates its Actor. */
public final class McpServlet extends HttpServlet {
    public static final String REVISION="2025-11-25";
    private final ActorAccess authority;
    private final McpEngine engine;
    private final Clock clock;
    private final String origin;
    private final Semaphore ingress;
    private final Map<String,Session> sessions=new HashMap<>();
    private final int sessionLimit;
    private double ingressTokens=100;
    private long ingressUpdated=System.nanoTime();
    private final ThreadPoolExecutor requests;
    private final Duration requestBudget;
    private final JsonMapper json=JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(16384).maxNumberLength(64).build())
        .build()).build();
    public McpServlet(ActorAccess authority,McpEngine engine,Clock clock,String origin,int ingress,int sessions) {
        this(authority,engine,clock,origin,ingress,sessions,Duration.ofSeconds(30));
    }
    public McpServlet(ActorAccess authority,McpEngine engine,Clock clock,String origin,int ingress,int sessions,Duration budget) {
        this.authority=authority;this.engine=engine;this.clock=clock;this.origin=origin;
        if(ingress<1 || ingress>32 || sessions<1 || sessions>4096) throw new IllegalArgumentException("Ingress/session bounds");
        this.ingress=new Semaphore(ingress);this.sessionLimit=sessions;
        if(budget.isZero() || budget.isNegative() || budget.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("Request budget bound");
        this.requestBudget=budget;
        requests=new ThreadPoolExecutor(ingress,ingress,0,TimeUnit.SECONDS,new SynchronousQueue<>(),
            Thread.ofPlatform().daemon().name("mcp-request-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    }
    @Override protected void service(HttpServletRequest request,HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control","no-store");
        if(!ingressRate() || !ingress.tryAcquire()) {response.setStatus(429);return;}
        // Non-blocking body reads: a slow trickle cannot occupy servlet threads or extend the absolute budget.
        Instant admitted=clock.instant();
        AsyncContext async=request.startAsync();async.setTimeout(Math.max(1,requestBudget.toMillis()));
        AtomicBoolean finished=new AtomicBoolean(),submitted=new AtomicBoolean(),released=new AtomicBoolean();
        Runnable release=()->{if(released.compareAndSet(false,true))ingress.release();};
        Runnable complete=()->{if(finished.compareAndSet(false,true))async.complete();};
        async.addListener(new AsyncListener() {
            public void onStartAsync(AsyncEvent e) { }
            public void onComplete(AsyncEvent e) { }
            public void onError(AsyncEvent e) {if(!submitted.get())release.run();complete.run();}
            public void onTimeout(AsyncEvent e) throws IOException {
                synchronized(response) {if(!response.isCommitted()) {
                    response.setStatus(503);response.setContentType("application/json");
                    response.getOutputStream().write("{\"category\":\"timeout\",\"outcome\":\"unknown\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    response.flushBuffer();
                }}
                if(!submitted.get())release.run();complete.run();
            }
        });
        ServletInputStream input=request.getInputStream();ByteArrayOutputStream body=new ByteArrayOutputStream();
        input.setReadListener(new ReadListener() {
            public void onDataAvailable() throws IOException {
                byte[] buffer=new byte[4096];
                while(!finished.get() && input.isReady() && !input.isFinished()) {
                    int count=input.read(buffer);if(count<0)break;
                    if(body.size()+count>65536) {response.setStatus(413);release.run();complete.run();return;}
                    body.write(buffer,0,count);
                }
            }
            public void onAllDataRead() {
                if(finished.get())return;
                submitted.set(true);
                try {
                    requests.execute(()-> {
                        try {if(!finished.get())handle(request,response,body.toByteArray(),admitted);}
                        catch(IOException ignored) { /* Disconnect is never proof of Product rollback. */ }
                        finally {release.run();complete.run();}
                    });
                } catch(RejectedExecutionException saturated) {response.setStatus(429);release.run();complete.run();}
            }
            public void onError(Throwable error) {if(!submitted.get())release.run();complete.run();}
        });
    }
    private void handle(HttpServletRequest request,HttpServletResponse response,byte[] body,Instant admittedAt) throws IOException {
        response.setHeader("Cache-Control","no-store");
        Object replyId=null;
        try {
            if(clock.instant().isBefore(admittedAt) || !clock.instant().isBefore(admittedAt.plus(requestBudget))) {
                response.setStatus(503);return;
            }
            if(!request.isSecure() || !origin.equals("https://"+request.getHeader("Host"))
                || (request.getHeader("Origin")!=null && !origin.equals(request.getHeader("Origin")))) {response.setStatus(403);return;}
            String authorization=request.getHeader("Authorization");
            if(authorization==null || !authorization.startsWith("Bearer ") || authorization.length()>520) {response.setStatus(401);return;}
            DelegatedActor actor=authority.authenticateMcp(authorization.substring(7));
            String id=request.getHeader("Mcp-Session-Id");
            String revision=request.getHeader("MCP-Protocol-Version");
            if(revision!=null && !REVISION.equals(revision)) {response.setStatus(400);return;}
            if(request.getMethod().equals("GET")) {
                if(id!=null)requireSession(id,actor,false);
                response.setStatus(405);return;
            }
            if(request.getMethod().equals("DELETE")) {
                if(!REVISION.equals(revision)) {response.setStatus(400);return;}
                requireSession(id,actor,false); synchronized(sessions){sessions.remove(id);} response.setStatus(200);return;
            }
            if(!request.getMethod().equals("POST")) {response.setStatus(405);return;}
            if(request.getContentType()==null || !request.getContentType().split(";")[0].equals("application/json")) {response.setStatus(415);return;}
            String accept=request.getHeader("Accept");
            if(accept==null || !accept.contains("application/json") || !accept.contains("text/event-stream")) {response.setStatus(406);return;}
            Map<String,Object> rpc;
            try {rpc=json.readValue(body,new TypeReference<Map<String,Object>>(){});}
            catch(RuntimeException invalid) {response.setStatus(400);rpcError(response,null,-32700,"Parse error");return;}
            if(rpc==null) {response.setStatus(400);rpcError(response,null,-32600,"Invalid request");return;}
            Object rpcId=rpc.get("id");
            if(!"2.0".equals(rpc.get("jsonrpc")) || !(rpc.get("method") instanceof String)
                || !Set.of("jsonrpc","method","id","params").containsAll(rpc.keySet())
                || (rpcId!=null && !(rpcId instanceof String) && !(rpcId instanceof Integer) && !(rpcId instanceof Long))
                || (rpcId instanceof String s && s.length()>64)) {response.setStatus(400);rpcError(response,null,-32600,"Invalid request");return;}
            if(rpc.containsKey("id") && rpcId==null) {response.setStatus(400);rpcError(response,null,-32600,"Invalid request");return;}
            replyId=rpcId;
            Map<String,Object> params=object(rpc.getOrDefault("params",Map.of()));
            String method=(String)rpc.get("method");
            if(method.equals("initialize")) {
                if(rpcId==null || id!=null || !params.keySet().equals(Set.of("protocolVersion","capabilities","clientInfo"))
                    || !(params.get("protocolVersion") instanceof String))
                    throw new McpFailure(McpFailure.Reason.PROTOCOL);
                // Client capabilities are bounded metadata, not server features or authority. None are used.
                object(params.get("capabilities"));
                Map<String,Object> info=object(params.get("clientInfo"));
                if(!(info.get("name") instanceof String) || !(info.get("version") instanceof String)
                    || !Set.of("name","version","title","description","websiteUrl","icons").containsAll(info.keySet()))
                    throw new McpFailure(McpFailure.Reason.PROTOCOL);
                String session=initialize(actor);
                response.setHeader("Mcp-Session-Id",session);
                // Our only supported revision is returned even if the client's proposal differs.
                // The client decides whether to accept it; subsequent HTTP headers must use REVISION.
                result(response,rpcId,Map.of("protocolVersion",REVISION,"capabilities",Map.of("tools",Map.of("listChanged",false)),
                    "serverInfo",Map.of("name","slotq","version","1.0")));
                return;
            }
            if(!REVISION.equals(request.getHeader("MCP-Protocol-Version"))) {response.setStatus(400);return;}
            Session session=requireSession(id,actor,!method.equals("notifications/initialized"));
            if(method.equals("notifications/initialized")) {
                if(rpcId!=null || !params.isEmpty()) throw new McpFailure(McpFailure.Reason.PROTOCOL);
                synchronized(sessions){session.initialized=true;} response.setStatus(202);return;
            }
            if(rpcId==null) {response.setStatus(202);return;}
            UUID correlation;
            try {correlation=UUID.fromString(response.getHeader("X-Request-ID"));}
            catch(RuntimeException absent){correlation=UUID.randomUUID();response.setHeader("X-Request-ID",correlation.toString());}
            RequestContext context=engine.context(actor,correlation,admittedAt);
            switch(method) {
                case "ping" -> {if(!params.isEmpty()) throw new McpFailure(McpFailure.Reason.PROTOCOL);result(response,rpcId,Map.of());}
                case "tools/list" -> {if(!params.isEmpty()) throw new McpFailure(McpFailure.Reason.PROTOCOL);
                    result(response,rpcId,Map.of("tools",engine.list(context)));}
                case "tools/call" -> {
                    if(!(params.get("name") instanceof String name))
                        throw new McpFailure(McpFailure.Reason.PROTOCOL);
                    Object args=Set.of("name","arguments").containsAll(params.keySet())
                        ?params.getOrDefault("arguments",Map.of()):null;
                    result(response,rpcId,engine.call(context,name,args));
                }
                default -> rpcError(response,rpcId,-32601,"Method not found");
            }
        } catch(SessionFailure failure) {
            response.setStatus(failure.status);
        } catch(AccessFailure failure) {
            response.setStatus(failure.reason()==AccessFailure.Reason.UNAVAILABLE?503:failure.reason()==AccessFailure.Reason.FORBIDDEN?403:401);
        } catch(McpFailure failure) {
            // RPC failures must not echo client fields or provider exception messages.
            if(replyId==null)response.setStatus(400);
            rpcError(response,replyId,failure.reason()==McpFailure.Reason.UNKNOWN_TOOL?-32602:-32600,failure.reason().name());
        } catch(RuntimeException unexpected) {rpcError(response,replyId,-32603,"Unavailable");}
    }
    @Override public void destroy() {requests.shutdown();super.destroy();}
    private synchronized boolean ingressRate() {
        long now=System.nanoTime();
        ingressTokens=Math.min(100,ingressTokens+(now-ingressUpdated)/1_000_000_000.0*50);ingressUpdated=now;
        if(ingressTokens<1)return false;ingressTokens--;return true;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        if(!(value instanceof Map<?,?> map)) throw new McpFailure(McpFailure.Reason.PROTOCOL);
        return (Map<String,Object>)map;
    }
    private String initialize(DelegatedActor actor) {
        synchronized(sessions) {
            sessions.values().removeIf(s->!clock.instant().isBefore(s.expires));
            if(sessions.size()>=sessionLimit || sessions.values().stream().filter(s->s.principal.equals(actor.original().principalId().value())).count()>=4)
                throw new McpFailure(McpFailure.Reason.RATE_LIMITED);
            String id=UUID.randomUUID().toString();sessions.put(id,new Session(actor));return id;
        }
    }
    private Session requireSession(String id,DelegatedActor actor,boolean initialized) {
        synchronized(sessions) {
            if(id==null)throw new SessionFailure(400);
            Session session=sessions.get(id);
            if(session==null || !clock.instant().isBefore(session.expires) || !session.delegation.equals(actor.delegationId())
                || !session.principal.equals(actor.original().principalId().value()))throw new SessionFailure(404);
            if(initialized && !session.initialized)throw new McpFailure(McpFailure.Reason.PROTOCOL);
            return session;
        }
    }
    private void result(HttpServletResponse response,Object id,Object result) throws IOException {
        write(response,Map.of("jsonrpc","2.0","id",id,"result",result));
    }
    private void rpcError(HttpServletResponse response,Object id,int code,String message) throws IOException {
        Map<String,Object> error=new LinkedHashMap<>();error.put("jsonrpc","2.0");error.put("id",id);
        error.put("error",Map.of("code",code,"message",message));write(response,error);
    }
    private void write(HttpServletResponse response,Object value) throws IOException {
        synchronized(response) {
        if(response.isCommitted())return;
        byte[] bytes=json.writeValueAsBytes(value);
        if(bytes.length>131072) {response.setStatus(500);return;}
        response.setContentType("application/json");response.getOutputStream().write(bytes);response.flushBuffer();
        }
    }
    private static final class Session {
        final UUID delegation,principal;final Instant expires;boolean initialized;
        Session(DelegatedActor a){delegation=a.delegationId();principal=a.original().principalId().value();expires=a.expiresAt();}
    }
    private static final class SessionFailure extends RuntimeException {
        final int status;
        SessionFailure(int status) { super(null,null,false,false);this.status=status; }
    }
}
