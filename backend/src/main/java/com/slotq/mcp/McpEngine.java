package com.slotq.mcp;

import com.slotq.auth.access.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;

/** No SDK scheduler, durable tasks, retry or queue. Permits live in the actual runner's finally block. */
public final class McpEngine implements AutoCloseable {
    private final ActorAccess authority;
    private final ToolRegistry registry;
    private final LocalAdmission admission;
    private final McpAudit audit;
    private final Clock clock;
    private final Duration budget;
    private final ExecutorService workers;
    private final Semaphore workerCapacity;
    private final int workerLimit;
    private final JsonMapper json=JsonMapper.builder().build();
    public McpEngine(ActorAccess authority, ToolRegistry registry, LocalAdmission admission, McpAudit audit,
            Clock clock, Duration budget, int concurrency) {
        this(authority,registry,admission,audit,clock,budget,concurrency,
            Thread.ofPlatform().daemon().name("mcp-handler-",0).factory());
    }
    McpEngine(ActorAccess authority, ToolRegistry registry, LocalAdmission admission, McpAudit audit,
            Clock clock, Duration budget, int concurrency, ThreadFactory workerThreads) {
        if(budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(30))>0
                || concurrency<1 || concurrency>32) throw new IllegalArgumentException("MCP execution bound");
        this.authority=authority; this.registry=registry; this.admission=admission; this.audit=audit;
        this.clock=clock; this.budget=budget;
        workerLimit=concurrency;
        workerCapacity=new Semaphore(concurrency);
        // No handoff queue: only admitted work gets a thread. Completion never depends on an idle pool worker.
        workers=Executors.newThreadPerTaskExecutor(workerThreads);
    }
    public RequestContext context(DelegatedActor actor, UUID serverId) {
        return context(actor,serverId,clock.instant());
    }
    public RequestContext context(DelegatedActor actor, UUID serverId, Instant admittedAt) {
        return new RequestContext(actor,serverId,admittedAt,admittedAt.plus(budget));
    }
    public List<McpSchema.Tool> list(RequestContext context) {
        long start=System.nanoTime();
        try(var permit=admission.attempt(context.actor(),"tools/list",null)) {
            var live=context.revalidate(authority,clock);
            var result=registry.all().stream().filter(t->t.permits(live))
                .map(ToolDefinition::wire).toList();
            sample(context,null,null,McpAudit.Outcome.SUCCESS,false,false,null,start);
            return result;
        } catch(AccessFailure failure) {
            McpFailure safe=accessFailure(failure);
            sample(context,null,safe.reason(),McpAudit.Outcome.DENIED,false,false,null,start);throw safe;
        }
        catch(McpFailure failure) { sample(context,null,failure.reason(),McpAudit.Outcome.DENIED,false,false,null,start); throw failure; }
    }
    public McpSchema.CallToolResult call(RequestContext context, String name, Object arguments) {
        long start=System.nanoTime(); ToolDefinition tool=null; LocalAdmission.Permit permit=null;
        try {
            // Unknown names use one stable bucket, never caller-created cardinality or metric labels.
            tool=registry.all().stream().filter(t->t.wire().name().equals(name)).findFirst().orElse(null);
            permit=admission.attempt(context.actor(),tool==null?"unknown":tool.wire().name(),tool==null?null:tool.resource());
            var live=context.revalidate(authority,clock);
            if(tool==null) throw new McpFailure(McpFailure.Reason.UNKNOWN_TOOL);
            if(!tool.permits(live)) throw new McpFailure(McpFailure.Reason.FORBIDDEN);
            if (!(arguments instanceof Map<?,?>)) throw new McpFailure(McpFailure.Reason.VALIDATION);
            @SuppressWarnings("unchecked") Map<String,Object> input=(Map<String,Object>)arguments;
            registry.validateInput(tool,input);
            final ToolDefinition selected=tool;
            final LocalAdmission.Permit runnerPermit=permit;
            Instant resourceDeadline=tool.resource()==ToolDefinition.Resource.RETRIEVAL
                ?clock.instant().plusSeconds(10):context.deadline();
            RequestContext handlerContext=resourceDeadline.isBefore(context.deadline())
                ?new RequestContext(context.actor(),context.requestId(),context.admittedAt(),resourceDeadline):context;
            handlerContext.remaining(clock,budget);
            Map<String,Object> frozen=JsonData.freeze(input);
            Future<ToolOutcome> running;
            if(!workerCapacity.tryAcquire()) throw new McpFailure(McpFailure.Reason.RATE_LIMITED);
            try {
                running=workers.submit(()-> {
                    try {
                        try(runnerPermit) {
                            handlerContext.revalidate(authority,clock);
                            ToolOutcome outcome=selected.handler().execute(handlerContext,frozen);
                            registry.validateOutput(selected,outcome.content());
                            if(json.writeValueAsBytes(outcome.content()).length>65536) throw new McpFailure(McpFailure.Reason.UNKNOWN);
                            return outcome;
                        }
                    } finally {
                        // Release after actual work/accounting ends and before its Future publishes completion.
                        workerCapacity.release();
                    }
                });
            } catch(RuntimeException | Error failedStart) {
                workerCapacity.release();
                if(failedStart instanceof RejectedExecutionException) throw new McpFailure(McpFailure.Reason.RATE_LIMITED);
                throw failedStart;
            }
            permit=null; // The runner alone owns release, even after timeout/disconnect/interruption.
            try {
                long remaining=Math.max(1,Math.min(budget.toNanos()-(System.nanoTime()-start),
                    Duration.between(clock.instant(),handlerContext.deadline()).toNanos()));
                ToolOutcome result=running.get(remaining,TimeUnit.NANOSECONDS);
                McpAudit.Outcome outcome=result.failure()==null?McpAudit.Outcome.SUCCESS
                    :result.failure()==McpFailure.Reason.UNKNOWN || result.failure()==McpFailure.Reason.TIMEOUT?McpAudit.Outcome.UNKNOWN
                    :result.failure()==McpFailure.Reason.UNAVAILABLE?McpAudit.Outcome.UNAVAILABLE:McpAudit.Outcome.DENIED;
                sample(context,tool,result.failure(),outcome,true,false,result,start);
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(null,
                    json.writeValueAsString(result.content()),null)),result.failure()!=null,result.content(),resultMetadata(context,result));
            } catch(TimeoutException timeout) {
                boolean retrieval=tool.failureOutcome()==McpAudit.Outcome.UNAVAILABLE;
                sample(context,tool,McpFailure.Reason.TIMEOUT,retrieval?McpAudit.Outcome.UNAVAILABLE:McpAudit.Outcome.UNKNOWN,true,true,null,start);
                return error(context,McpFailure.Reason.TIMEOUT,retrieval?"unavailable":"unknown");
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                boolean unavailable=tool.failureOutcome()==McpAudit.Outcome.UNAVAILABLE;
                sample(context,tool,unavailable?McpFailure.Reason.UNAVAILABLE:McpFailure.Reason.UNKNOWN,
                        unavailable?McpAudit.Outcome.UNAVAILABLE:McpAudit.Outcome.UNKNOWN,true,false,null,start);
                return error(context,unavailable?McpFailure.Reason.UNAVAILABLE:McpFailure.Reason.UNKNOWN,unavailable?"unavailable":"unknown");
            } catch(ExecutionException failure) {
                // No provider/SQL/exception text escapes. An executing handler may have committed effects.
                boolean retrieval=tool.failureOutcome()==McpAudit.Outcome.UNAVAILABLE;
                sample(context,tool,retrieval?McpFailure.Reason.UNAVAILABLE:McpFailure.Reason.UNKNOWN,
                        retrieval?McpAudit.Outcome.UNAVAILABLE:McpAudit.Outcome.UNKNOWN,true,false,null,start);
                return error(context,retrieval?McpFailure.Reason.UNAVAILABLE:McpFailure.Reason.UNKNOWN,retrieval?"unavailable":"unknown");
            }
        } catch(AccessFailure failure) {
            McpFailure safe=accessFailure(failure);
            sample(context,tool,safe.reason(),McpAudit.Outcome.DENIED,false,false,null,start);
            return error(context,safe.reason(),"not_dispatched");
        } catch(McpFailure failure) {
            sample(context,tool,failure.reason(),McpAudit.Outcome.DENIED,false,false,null,start);
            if(failure.reason()==McpFailure.Reason.UNKNOWN_TOOL) throw failure;
            return error(context,failure.reason(),"not_dispatched");
        } finally { if(permit!=null) permit.close(); }
    }
    private McpSchema.CallToolResult error(RequestContext context,McpFailure.Reason reason,String dispatch) {
        Map<String,Object> safe=Map.of("category",reason.name().toLowerCase(Locale.ROOT),"outcome",dispatch,
            "requestId",context.requestId().toString());
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(null,json.writeValueAsString(safe),null)),true,safe,null);
    }
    private static Map<String,Object> resultMetadata(RequestContext context,ToolOutcome result) {
        Map<String,Object> refs=new LinkedHashMap<>();refs.put("requestId",context.requestId().toString());
        if(result.knownTarget()!=null)refs.put("knownTarget",result.knownTarget().toString());
        if(result.productRequestId()!=null)refs.put("productRequestId",result.productRequestId().toString());
        if(result.confirmationId()!=null)refs.put("confirmationId",result.confirmationId().toString());
        if(result.intentId()!=null)refs.put("intentId",result.intentId().toString());
        if(result.documentId()!=null)refs.put("documentId",result.documentId().toString());
        if(result.versionId()!=null)refs.put("versionId",result.versionId().toString());
        if(!result.retrievalReferences().isEmpty())refs.put("retrievalReferences",result.retrievalReferences());
        return Map.copyOf(refs);
    }
    private static McpFailure accessFailure(AccessFailure failure) {
        return new McpFailure(failure.reason()==AccessFailure.Reason.UNAVAILABLE?McpFailure.Reason.UNAVAILABLE:McpFailure.Reason.FORBIDDEN);
    }
    private void sample(RequestContext c,ToolDefinition tool,McpFailure.Reason reason,McpAudit.Outcome outcome,
            boolean allowed,boolean timeout,ToolOutcome result,long start) {
        audit.offer(new McpAudit.Event(c.requestId(),c.actor().original().principalId().value(),c.actor().delegationId(),
            c.actor().tenantId().value(),c.actor().venueId().value(),tool==null?null:tool.wire().name(),
            tool==null?null:tool.action(c.actor()),
            allowed,reason,result==null?(allowed?McpAudit.Dispatch.UNAVAILABLE:McpAudit.Dispatch.NOT_DISPATCHED)
                :(result.dispatched()?McpAudit.Dispatch.REPORTED:McpAudit.Dispatch.NOT_DISPATCHED),outcome,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),timeout,
            timeout?McpAudit.TimeoutLayer.HANDLER:result==null?McpAudit.TimeoutLayer.NONE:result.timeoutLayer(),
            result==null?null:result.confirmationId(),
            result==null?null:result.intentId(),result==null?null:result.knownTarget(),result==null?null:result.productRequestId(),
            result==null?null:result.documentId(),result==null?null:result.versionId(),result==null?List.of():result.retrievalReferences()));
    }
    @Override public void close() { admission.unavailable(); workers.shutdown(); }
    public int activeWorkers() { return workerLimit-workerCapacity.availablePermits(); }
}
