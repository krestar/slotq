package com.slotq.ai.runtime;

import com.slotq.ai.router.*;
import com.slotq.auth.access.*;
import com.slotq.integration.mcp.product.HoldApprovals;
import com.slotq.mcp.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;
import static com.slotq.ai.runtime.RuntimeContract.*;
import static com.slotq.ai.runtime.RuntimeFailure.Reason.*;

/** Bounded server-side process-local execution. No loop, public API, persistence or automatic retry. */
public final class AgentRuntime implements AutoCloseable {
    @FunctionalInterface interface ToolClient {
        McpSchema.CallToolResult call(RequestContext context,String tool,Map<String,Object> args,McpEngine.Completion completion);
    }
    private final ActorAccess authority;
    private final ToolRegistry registry;
    private final ToolClient tools;
    private final HoldApprovals approvals;
    private final ModelRouter router=new ModelRouter();
    private final JsonMapper json=JsonMapper.builder().build();
    private final Provider provider;
    private final List<ModelRouter.Candidate> candidates;
    private final ContextSource contexts;
    private final LocalAdmission admission;
    private final Clock clock;
    private final int capacity,workerLimit;
    private final Semaphore workersAvailable;
    private final ExecutorService workers=Executors.newThreadPerTaskExecutor(Thread.ofPlatform().daemon().name("agent-work-",0).factory());
    private final Map<UUID,Run> runs=new HashMap<>();
    private volatile boolean closed;

    public AgentRuntime(ActorAccess authority,ToolRegistry registry,McpEngine engine,HoldApprovals approvals,
            Provider provider,List<ModelRouter.Candidate> candidates,ContextSource contexts,
            LocalAdmission admission,Clock clock,int capacity,int concurrency) {
        this(authority,registry,engine::call,approvals,provider,candidates,contexts,admission,clock,capacity,concurrency);
    }
    AgentRuntime(ActorAccess authority,ToolRegistry registry,ToolClient tools,HoldApprovals approvals,
            Provider provider,List<ModelRouter.Candidate> candidates,ContextSource contexts,
            LocalAdmission admission,Clock clock,int capacity,int concurrency) {
        if(capacity<1 || capacity>4096 || concurrency<1 || concurrency>32 || candidates.size()>16)
            throw new IllegalArgumentException("Invalid Runtime capacity");
        this.authority=Objects.requireNonNull(authority);this.registry=Objects.requireNonNull(registry);this.tools=Objects.requireNonNull(tools);
        this.approvals=Objects.requireNonNull(approvals);this.provider=Objects.requireNonNull(provider);
        this.candidates=List.copyOf(candidates);this.contexts=Objects.requireNonNull(contexts);
        this.admission=Objects.requireNonNull(admission);this.clock=Objects.requireNonNull(clock);
        this.capacity=capacity;this.workerLimit=concurrency;workersAvailable=new Semaphore(concurrency);
    }

    public UUID start(String credential,Plan plan) {
        var actor = authenticate(credential, plan.workload());
        synchronized(runs) {
            if(closed) throw fail(UNAVAILABLE);
            // Never evict live budget, unknown effects or actual continuing workers before absolute expiry.
            runs.values().removeIf(r->{synchronized(r){return r.localWork==0 && !clock.instant().isBefore(r.deadline);}});
            if(runs.size()>=capacity) throw fail(ADMISSION);
            UUID id=UUID.randomUUID();runs.put(id,new Run(id,actor,plan));return id;
        }
    }
    public Result result(UUID id,String credential) {
        Run r=run(id);synchronized(r){current(r,credential);expire(r);return snapshot(r);}
    }
    public Result cancel(UUID id,String credential) {
        Run r=run(id);synchronized(r){current(r,credential);r.execution=Execution.CANCEL_REQUESTED;return snapshot(r);}
    }
    public Result answerDeliveryFailed(UUID id,String credential) {
        Run r=run(id);synchronized(r){current(r,credential);r.answer=null;r.failure="answer_delivery_unavailable";return snapshot(r);}
    }

    /** Each call is one explicit step. Failed providers are terminal under ADR-0010. */
    public Result generate(UUID id,String credential) {
        Run r=run(id);Future<?> future;long wait;
        synchronized(r) {
            var actor=current(r,credential);active(r);step(r);
            if(r.providerAttempts>=r.plan.limits().providerAttempts() || r.providerBudget.usageUnknown() || r.usageUnknown) endBudget(r);
            Context context;
            try { context=contexts.prepare(actor,r.holdAttempted?Stage.ANSWER:Stage.PROPOSAL,snapshot(r)); }
            catch(RuntimeException failure) {r.execution=Execution.TERMINAL;r.failure="context_unavailable";return snapshot(r);}
            // Revalidation happens after context preparation and again at the dispatch boundary.
            current(r,credential);active(r);
            long reserve=(long)context.classification().inputTokens()+context.classification().outputTokens();
            if(reserve>r.providerBudget.remainingTokens() || context.request().maximumOutputTokens()>r.remainingOutput) endBudget(r);
            var decision=router.route(new ModelRouter.Snapshot(r.plan.revision(),r.plan.workload(),context.classification(),candidates,
                ModelRouter.Policy.bounded(),new ModelRouter.Remaining(r.plan.limits().providerAttempts()-r.providerAttempts,
                    r.providerBudget.remainingTokens(),r.remainingDollars,clock.instant(),r.deadline)));
            if(decision.selected()==null){r.execution=Execution.TERMINAL;r.failure="no_candidate";return snapshot(r);}
            var selected=candidates.stream().filter(c->c.id().equals(decision.selected())).findFirst().orElseThrow();
            Work work=admit(r,actor,"provider."+selected.model(),false);
            r.providerAttempts++;r.remainingOutput-=context.request().maximumOutputTokens();
            UUID attempt=UUID.randomUUID();int providerIndex=r.providers.size();int step=r.steps;
            r.providers.add(new ProviderObservation(attempt,step,selected.model(),false,true,true));
            wait=waitNanos(r);
            try {
                future=workers.submit(()-> {
                    try {
                        synchronized(r) {
                            if(!canDispatch(r,actor)) {
                                r.failure="provider_not_dispatched";
                                r.providers.set(providerIndex,new ProviderObservation(attempt,step,selected.model(),true,false,false));return;
                            }
                        }
                        var response=provider.generate(selected.model(),context.classification(),context.request(),r.providerBudget);
                        synchronized(r){
                            observeProvider(r,response,context,selected);
                            boolean remoteUnknown=response==null || response.failure()==GeminiAdapter.Failure.TIMEOUT_REMOTE_UNKNOWN
                                || response.failure()==GeminiAdapter.Failure.TRANSPORT_REMOTE_UNKNOWN;
                            r.providers.set(providerIndex,new ProviderObservation(attempt,step,selected.model(),true,remoteUnknown,r.usageUnknown || r.providerBudget.usageUnknown()));
                        }
                    } catch(RuntimeException failure) {
                        synchronized(r){r.usageUnknown=true;r.failure="provider_unavailable";terminal(r);
                            r.providers.set(providerIndex,new ProviderObservation(attempt,step,selected.model(),true,true,true));}
                    } finally {work.outerFinished();}
                });
            } catch(RuntimeException failure) {work.outerFinished();throw fail(UNAVAILABLE);}
        }
        awaitProvider(r,future,wait);
        synchronized(r){current(r,credential);expire(r);return snapshot(r);}
    }

    public ApprovalReview reviewHold(UUID id,String credential,String originalCredential) {
        Run r=run(id);synchronized(r) {
            current(r,credential);
            if(r.holdAttempted)active(r);else waiting(r);
            original(r,originalCredential);
            if(r.review==null) r.review=approvals.prepare(originalCredential,r.origin.delegationId(),r.plan.hold().slot(),r.plan.hold().partySize());
            var material=approvals.review(originalCredential,r.review.intentId());verifyReview(r,material);
            return publicReview(r,material);
        }
    }
    /** The controlled caller must display reviewHold's immutable material before submitting it here. */
    public UUID approveHold(UUID id,String credential,String originalCredential,ApprovalReview displayed) {
        Run r=run(id);synchronized(r) {
            current(r,credential);expire(r);original(r,originalCredential);
            if(r.execution!=Execution.WAITING_CONFIRMATION && !(r.execution==Execution.ACTIVE && r.holdAttempted)) throw fail(STATE);
            if(r.review==null) throw fail(APPROVAL);
            var material=approvals.review(originalCredential,r.review.intentId());verifyReview(r,material);
            if(!publicReview(r,material).equals(displayed))throw fail(APPROVAL);
            r.confirmation=approvals.approve(originalCredential,material.intentId());return r.confirmation.id();
        }
    }
    public Result dispatchHold(UUID id,String credential) { return hold(id,credential,null,false); }
    /** Explicit original-Actor retry only; provider output can never call this boundary. */
    public Result retryHold(UUID id,String credential,String originalCredential) {return hold(id,credential,originalCredential,true);}
    private Result hold(UUID id,String credential,String originalCredential,boolean retry) {
        Run r=run(id);Map<String,Object> args;
        synchronized(r) {
            current(r,credential);expire(r);
            if(retry) {
                active(r);original(r,originalCredential);
                if(!r.holdAttempted || r.effect==Effect.SUCCEEDED || r.effect==Effect.REJECTED)throw fail(STATE);
            } else {waiting(r);if(r.holdAttempted)throw fail(STATE);}
            if(r.review==null || r.confirmation==null)throw fail(APPROVAL);
            args=Map.of("intentId",r.review.intentId().toString(),"confirmationId",r.confirmation.id().toString(),
                "slotInventoryId",r.review.slotInventoryId().toString(),"partySize",r.review.partySize(),"idempotencyKey",r.review.idempotencyKey());
        }
        return invoke(r,credential,"reservation.hold",args,true,false,retry);
    }
    public Result read(UUID id,String credential,String name,Map<String,Object> proposal) {
        Run r=run(id);
        return invoke(r,credential,name,proposal,false,false,false);
    }
    public Result reconcile(UUID id,String credential) {
        Run r=run(id);Map<String,Object> args;
        synchronized(r) {
            current(r,credential);active(r);
            if(r.knownTarget==null)return snapshot(r);
            args=Map.of("reservationId",r.knownTarget.toString());
        }
        return invoke(r,credential,"reservation.get",args,false,true,false);
    }
    private Result invoke(Run r,String credential,String name,Map<String,Object> args,boolean mutation,boolean reconciliation,boolean explicitRetry) {
        Future<?> future;long wait;Attempt attempt;
        synchronized(r) {
            var actor=current(r,credential);
            if(mutation) {
                if(explicitRetry) {active(r);if(!r.holdAttempted || r.effect==Effect.SUCCEEDED || r.effect==Effect.REJECTED)throw fail(STATE);}
                else {waiting(r);if(r.holdAttempted)throw fail(STATE);}
            }else active(r);
            step(r);
            if(r.toolAttempts>=r.plan.limits().toolAttempts())endBudget(r);
            r.toolAttempts++;
            String safeName=registry.all().stream().anyMatch(tool->tool.wire().name().equals(name))?name:"unknown";
            attempt=new Attempt(UUID.randomUUID(),mutation?r.proposalId:UUID.randomUUID(),r.steps,safeName,mutation,reconciliation);
            r.attempts.add(attempt);
            try {
                var tool=registry.require(name);
                if(!tool.permits(actor) || (!mutation && !reconciliation && !Objects.equals(r.plan.reads().get(name),args))) throw fail(PROPOSAL);
                registry.validateInput(tool,args);
            }catch(RuntimeException rejected){attempt.observation=observation(attempt,Invocation.NOT_DISPATCHED,Effect.NOT_APPLICABLE,null,Map.of());return snapshot(r);}
            Map<String,Object> frozen=RuntimeData.freeze(args);
            Work work=admit(r,actor,"runtime.tool",true);
            if(mutation) {r.holdAttempted=true;r.execution=Execution.ACTIVE;}
            attempt.observation=observation(attempt,Invocation.UNKNOWN,mutation?Effect.UNKNOWN:Effect.NOT_APPLICABLE,null,Map.of());
            wait=waitNanos(r);
            var context=new RequestContext(actor,attempt.id,clock.instant(),earlier(r.deadline,clock.instant().plusSeconds(30)));
            try {
                future=workers.submit(()-> {
                    boolean entered=false;
                    try {
                        synchronized(r) {
                            if(!canDispatch(r,actor)) {
                                observe(r,attempt,Invocation.NOT_DISPATCHED,Effect.NOT_APPLICABLE,null,Map.of());return;
                            }
                            entered=true;
                            if(mutation)merge(r,attempt.observation);
                        }
                        var response=tools.call(context,name,frozen,new McpEngine.Completion() {
                            @Override public void observed(ToolOutcome outcome) {
                                synchronized(r){mapOutcome(r,attempt,outcome.content(),outcome.knownTarget());}
                            }
                            @Override public void finished(){work.innerFinished();}
                        });
                        synchronized(r){mapWire(r,attempt,response);}
                    }catch(RuntimeException failure){synchronized(r){unknown(r,attempt);}}
                    finally {if(!entered)work.innerFinished();work.outerFinished();}
                });
            }catch(RuntimeException failure){work.innerFinished();work.outerFinished();throw fail(UNAVAILABLE);}
        }
        try {future.get(wait,TimeUnit.NANOSECONDS);}
        catch(TimeoutException failure){synchronized(r){unknown(r,attempt);r.failure="tool_response_unknown";expire(r);}}
        catch(InterruptedException failure){Thread.currentThread().interrupt();synchronized(r){unknown(r,attempt);r.failure="tool_response_unknown";}}
        catch(ExecutionException failure){synchronized(r){unknown(r,attempt);r.failure="tool_response_unknown";}}
        synchronized(r){current(r,credential);expire(r);return snapshot(r);}
    }

    private void observeProvider(Run r,GeminiAdapter.Result response,Context context,ModelRouter.Candidate selected) {
        if(response==null || response.attempts()<0 || response.attempts()>1){r.usageUnknown=true;r.failure="provider_unavailable";terminal(r);return;}
        var usage=response.usage();
        if(response.attempts()>0 && (usage==null || !"measured".equals(usage.status()) || usage.total()==null || usage.total()<0
                || usage.total()>(long)context.classification().inputTokens()+context.classification().outputTokens()
                || usage.input()==null || usage.output()==null || usage.input()<0 || usage.output()<0
                || usage.output()>context.request().maximumOutputTokens() || usage.total()<usage.input()+usage.output()
                || !Set.of("measured","estimated").contains(response.costStatus()))) r.usageUnknown=true;
        else if(response.attempts()>0) {
            var price=selected.price();
            var cost=price.inputPerMillion().multiply(BigDecimal.valueOf(usage.input()))
                .add(price.outputPerMillion().multiply(BigDecimal.valueOf(usage.total()-usage.input()))).divide(BigDecimal.valueOf(1_000_000));
            if(cost.compareTo(r.remainingDollars)>0)r.usageUnknown=true;else r.remainingDollars=r.remainingDollars.subtract(cost);
            r.remainingOutput+=context.request().maximumOutputTokens()-usage.output();
        }
        expire(r);
        if(r.execution!=Execution.ACTIVE)return; // late provider text never starts a step or exposes an answer
        if(response.failure()!=null || response.output()==null || r.usageUnknown || r.providerBudget.usageUnknown()) {
            r.failure=response.failure()==null?"provider_usage_unknown":GeminiAdapter.disposition(response.failure());terminal(r);return;
        }
        ProviderProtocol.Output output;
        try {output=ProviderProtocol.parse(json.writeValueAsString(response.output()),context.request());}
        catch(RuntimeException invalid){r.failure=r.holdAttempted?"answer_rejected":"proposal_rejected";terminal(r);return;}
        boolean valid=context.request().knownSources().containsAll(output.sourceRefs()) && context.request().knownClaims().containsAll(output.claimRefs());
        if("PROPOSE_HOLD".equals(output.disposition())) {
            if(!valid || r.holdAttempted || r.proposalId!=null || r.plan.hold()==null || !"reservation.hold".equals(output.tool())
                || !r.plan.hold().slot().toString().equals(output.target()) || r.plan.hold().partySize()!=output.partySize()
                || !"NOT_APPLICABLE".equals(output.outcome())) {r.failure="proposal_rejected";terminal(r);return;}
            r.proposalId=UUID.randomUUID();r.execution=Execution.WAITING_CONFIRMATION;return;
        }
        String expected=r.effect==Effect.SUCCEEDED?"HELD":r.effect==Effect.REJECTED?"REJECTED":r.effect==Effect.UNKNOWN?"UNKNOWN":"NOT_APPLICABLE";
        if(!valid || !Set.of("ANSWER","CLARIFY","INSUFFICIENT","REJECT").contains(output.disposition()) || !"none".equals(output.tool())
            || !output.target().isEmpty() || output.partySize()!=0 || !expected.equals(output.outcome())
            || output.answer()==null || output.answer().isBlank() || output.answer().length()>700)r.failure="answer_rejected";
        else r.answer=output.answer();
        terminal(r);
    }
    private void awaitProvider(Run r,Future<?> future,long wait) {
        try {future.get(wait,TimeUnit.NANOSECONDS);}
        catch(TimeoutException failure){synchronized(r){r.usageUnknown=true;r.failure="provider_remote_unknown";terminal(r);expire(r);}}
        catch(InterruptedException failure){Thread.currentThread().interrupt();synchronized(r){r.usageUnknown=true;r.failure="provider_remote_unknown";terminal(r);}}
        catch(ExecutionException failure){synchronized(r){r.usageUnknown=true;r.failure="provider_unavailable";terminal(r);}}
    }
    private void mapWire(Run r,Attempt attempt,McpSchema.CallToolResult response) {
        if(response==null || !(response.structuredContent() instanceof Map<?,?> content)){unknown(r,attempt);return;}
        @SuppressWarnings("unchecked") Map<String,Object> data=(Map<String,Object>)content;
        UUID target=null;
        try {if(response.meta()!=null && response.meta().get("knownTarget") instanceof String value)target=UUID.fromString(value);}
        catch(RuntimeException ignored){ }
        mapOutcome(r,attempt,data,target);
    }
    private void mapOutcome(Run r,Attempt attempt,Map<String,Object> data,UUID target) {
        String outcome=String.valueOf(data.get("outcome"));
        if("not_dispatched".equals(outcome)) {
            if(attempt.reconciliation)r.reconciliation=RuntimeData.freeze(data);
            observe(r,attempt,Invocation.NOT_DISPATCHED,Effect.NOT_APPLICABLE,null,data);return;
        }
        if("unknown".equals(outcome) || "unavailable".equals(outcome)){unknown(r,attempt);return;}
        try {registry.validateOutput(registry.require(attempt.tool),data);}
        catch(RuntimeException invalid){unknown(r,attempt);return;}
        if(!Set.of("succeeded","rejected","outcome_unknown").contains(outcome)) {
            // Knowledge has its own structured read outcome; it can never carry mutation authority.
            if(!attempt.mutation && attempt.tool.equals("knowledge.search"))observe(r,attempt,Invocation.RESPONSE_OBSERVED,Effect.NOT_APPLICABLE,null,data);
            else unknown(r,attempt);
            return;
        }
        Effect effect=!attempt.mutation?Effect.NOT_APPLICABLE:outcome.equals("succeeded")?Effect.SUCCEEDED:outcome.equals("rejected")?Effect.REJECTED:Effect.UNKNOWN;
        if(attempt.reconciliation) {
            if(outcome.equals("succeeded") && (!(data.get("data") instanceof Map<?,?> found)
                || r.knownTarget==null || !r.knownTarget.toString().equals(String.valueOf(found.get("id"))))) {unknown(r,attempt);return;}
            r.reconciliation=RuntimeData.freeze(data);
        }
        observe(r,attempt,Invocation.RESPONSE_OBSERVED,effect,target,data);
    }
    private void unknown(Run r,Attempt attempt){
        if(attempt.reconciliation && (attempt.observation==null || attempt.observation.invocation()!=Invocation.RESPONSE_OBSERVED))r.reconciliation=Map.of();
        observe(r,attempt,Invocation.UNKNOWN,attempt.mutation?Effect.UNKNOWN:Effect.NOT_APPLICABLE,null,Map.of());
    }
    private void observe(Run r,Attempt attempt,Invocation invocation,Effect effect,UUID target,Map<String,Object> data) {
        var old=attempt.observation;
        if(old!=null && old.invocation()==Invocation.RESPONSE_OBSERVED && (invocation!=Invocation.RESPONSE_OBSERVED || old.effect()==Effect.SUCCEEDED))return;
        attempt.observation=observation(attempt,invocation,effect,target,data);
        if(attempt.mutation)merge(r,attempt.observation);
    }
    private static ToolObservation observation(Attempt a,Invocation invocation,Effect effect,UUID target,Map<String,Object> data){
        return new ToolObservation(a.id,a.proposal,a.step,a.tool,invocation,effect,target,data);
    }
    private static void merge(Run r,ToolObservation observation) {
        if(observation.knownTarget()!=null && (r.knownTarget==null || r.knownTarget.equals(observation.knownTarget())))r.knownTarget=observation.knownTarget();
        Effect effect=Effect.NOT_APPLICABLE;
        for(var attempt:r.attempts) if(attempt.mutation && attempt.observation!=null) {
            var seen=attempt.observation;
            if(seen.effect()==Effect.SUCCEEDED){effect=Effect.SUCCEEDED;r.product=seen.structured();break;}
            if(seen.effect()==Effect.UNKNOWN){effect=Effect.UNKNOWN;if(!seen.structured().isEmpty())r.product=seen.structured();}
            else if(seen.effect()==Effect.REJECTED && effect!=Effect.UNKNOWN){effect=Effect.REJECTED;r.product=seen.structured();}
        }
        r.effect=effect;
    }

    private Work admit(Run r,DelegatedActor actor,String resource,boolean inner) {
        LocalAdmission.Permit permit;
        try {permit=admission.attempt(actor,resource,null);}catch(RuntimeException denied){throw fail(ADMISSION);}
        if(!workersAvailable.tryAcquire()){permit.close();throw fail(ADMISSION);}
        r.localWork++;return new Work(r,permit,inner);
    }
    private final class Work {
        final Run r;final LocalAdmission.Permit permit;boolean outerDone,innerDone,released;
        Work(Run r,LocalAdmission.Permit permit,boolean inner){this.r=r;this.permit=permit;innerDone=!inner;}
        void outerFinished(){synchronized(r){outerDone=true;release();}}
        void innerFinished(){synchronized(r){innerDone=true;release();}}
        void release(){if(!released && outerDone && innerDone){released=true;r.localWork--;permit.close();workersAvailable.release();}}
    }
    public int activeWorkers(){return workerLimit-workersAvailable.availablePermits();}
    private Run run(UUID id){synchronized(runs){Run r=runs.get(id);if(r==null)throw fail(AUTHORITY);return r;}}
    private DelegatedActor authenticate(String credential, String workload) {
        try {
            var actor = authority.authenticateMcp(credential);
            boolean customer = workload.equals("customer");
            if (customer != (actor.profile() == AccessProfile.CUSTOMER)) {
                throw fail(AUTHORITY);
            }
            var live = revalidate(workload, actor.delegationId());
            if (!actor.equals(live) || !clock.instant().isBefore(live.expiresAt())) {
                throw fail(AUTHORITY);
            }
            return live;
        } catch (RuntimeException failure) {
            throw fail(AUTHORITY);
        }
    }
    private DelegatedActor revalidate(String workload, UUID delegationId) {
        return workload.equals("customer")
            ? authority.revalidate(delegationId)
            : authority.revalidateOwnerManager(delegationId);
    }
    private DelegatedActor current(Run run, String credential) {
        var actor = authenticate(credential, run.plan.workload());
        if (!run.origin.equals(actor)) {
            throw fail(AUTHORITY);
        }
        return actor;
    }
    private boolean canDispatch(Run run, DelegatedActor admitted) {
        expire(run);
        if (closed || run.execution != Execution.ACTIVE) {
            return false;
        }
        try {
            return admitted.equals(revalidate(run.plan.workload(), admitted.delegationId()))
                && clock.instant().isBefore(admitted.expiresAt());
        } catch (RuntimeException failure) {
            run.failure = "authority_unavailable";
            terminal(run);
            return false;
        }
    }
    private void original(Run r,String credential){try{if(!authority.validateOriginal(credential).principalId().equals(r.origin.original().principalId()))throw fail(APPROVAL);}catch(RuntimeException failure){throw fail(APPROVAL);}}
    private void verifyReview(Run r,HoldApprovals.Review review) {
        if(!r.review.intentId().equals(review.intentId()) || !r.origin.tenantId().value().equals(review.tenantId())
            || !r.origin.venueId().value().equals(review.venueId()) || !r.plan.hold().slot().equals(review.slotInventoryId())
            || r.plan.hold().partySize()!=review.partySize() || !r.review.idempotencyKey().equals(review.idempotencyKey()))throw fail(APPROVAL);
    }
    private static ApprovalReview publicReview(Run r,HoldApprovals.Review review){return new ApprovalReview(r.id,r.proposalId,review.intentId(),review.tenantId(),review.venueId(),review.slotInventoryId(),review.partySize(),review.retryExpiresAt());}
    private void step(Run r){if(r.steps>=r.plan.limits().steps())endBudget(r);r.steps++;}
    private void active(Run r){expire(r);if(closed)throw fail(UNAVAILABLE);if(r.execution!=Execution.ACTIVE || r.localWork!=0)throw fail(STATE);}
    private void waiting(Run r){expire(r);if(closed)throw fail(UNAVAILABLE);if(r.execution!=Execution.WAITING_CONFIRMATION || r.localWork!=0)throw fail(STATE);}
    private void expire(Run r){if(clock.instant().isBefore(r.admittedAt) || !clock.instant().isBefore(r.deadline)) {
        if(r.execution==Execution.ACTIVE || r.execution==Execution.WAITING_CONFIRMATION || r.execution==Execution.TERMINAL)r.execution=Execution.DEADLINE_ENDED;
    }}
    private void endBudget(Run r){r.execution=Execution.BUDGET_ENDED;throw fail(BUDGET);}
    private static void terminal(Run r){if(r.execution==Execution.ACTIVE)r.execution=Execution.TERMINAL;}
    private long waitNanos(Run r){return Math.max(1,Math.min(r.plan.limits().attemptTimeout().toNanos(),Duration.between(clock.instant(),r.deadline).toNanos()));}
    private Result snapshot(Run r){return new Result(r.id,r.execution,r.effect,r.knownTarget,r.product,r.reconciliation,
        r.providers,
        r.attempts.stream().map(a->a.observation==null?observation(a,Invocation.NOT_DISPATCHED,Effect.NOT_APPLICABLE,null,Map.of()):a.observation).toList(),
        new Budget(r.steps,r.providerAttempts,r.toolAttempts,r.providerBudget.remainingTokens(),r.remainingOutput,r.remainingDollars,
            r.usageUnknown || r.providerBudget.usageUnknown(),r.localWork,r.admittedAt,r.deadline),r.answer,r.failure);}
    private static Instant earlier(Instant a,Instant b){return a.isBefore(b)?a:b;}
    private static RuntimeFailure fail(RuntimeFailure.Reason reason){return new RuntimeFailure(reason);}
    @Override public void close(){closed=true;synchronized(runs){for(Run r:runs.values())synchronized(r){r.execution=Execution.CANCEL_REQUESTED;}}workers.shutdown();}
    private final class Run {
        final UUID id;final DelegatedActor origin;final Plan plan;final Instant admittedAt,deadline;final ProviderBudget providerBudget;
        final List<ProviderObservation> providers=new ArrayList<>();final List<Attempt> attempts=new ArrayList<>();
        Execution execution=Execution.ACTIVE;Effect effect=Effect.NOT_APPLICABLE;UUID proposalId,knownTarget;
        HoldApprovals.Review review;HoldApprovals.Confirmation confirmation;Map<String,Object> product=Map.of(),reconciliation=Map.of();
        int steps,providerAttempts,toolAttempts,localWork;long remainingOutput;BigDecimal remainingDollars;
        boolean holdAttempted,usageUnknown;String answer,failure;
        Run(UUID id,DelegatedActor actor,Plan plan){this.id=id;origin=actor;this.plan=plan;admittedAt=clock.instant();
            deadline=earlier(admittedAt.plus(plan.limits().lifetime()),actor.expiresAt());
            providerBudget=new ProviderBudget(clock,deadline,plan.limits().providerAttempts(),plan.limits().tokens());
            remainingOutput=plan.limits().outputTokens();remainingDollars=plan.limits().dollars();}
    }
    private static final class Attempt {
        final UUID id,proposal;final int step;final String tool;final boolean mutation,reconciliation;ToolObservation observation;
        Attempt(UUID id,UUID proposal,int step,String tool,boolean mutation,boolean reconciliation){this.id=id;this.proposal=proposal;this.step=step;this.tool=tool;this.mutation=mutation;this.reconciliation=reconciliation;}
    }
}
