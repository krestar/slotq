package com.slotq.mcp;

import com.slotq.auth.access.*;
import com.slotq.auth.domain.*;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class McpFoundationTests {
    static final Clock CLOCK=Clock.systemUTC();
    static final Map<String,Object> INPUT=Map.of("type","object","additionalProperties",false,
        "required",List.of("query","count"),"properties",Map.of("query",Map.of("type","string","maxLength",128),
            "count",Map.of("type","integer","minimum",1,"maximum",10)));
    static final Map<String,Object> OUTPUT=Map.of("type","object","additionalProperties",false,
        "required",List.of("scope"),"properties",Map.of("scope",Map.of("type","string","maxLength",128)));
    static DelegatedActor actor(AccessProfile profile) {
        return new DelegatedActor(new AuthenticatedPrincipal(new PrincipalId(UUID.randomUUID())),UUID.randomUUID(),
            UUID.randomUUID(),new TenantId(UUID.randomUUID()),new VenueId(UUID.randomUUID()),profile,
            profile==AccessProfile.CUSTOMER?Set.of(AccessAction.KNOWLEDGE_PUBLIC):Set.of(AccessAction.MANAGEMENT_READ),
            Set.of("test.customer","test.management"),CLOCK.instant().plusSeconds(600));
    }
    static ToolDefinition tool(String name,AccessProfile profile,ToolDefinition.Handler handler) {
        return new ToolDefinition(new McpSchema.Tool(name,null,"Synthetic test only",INPUT,OUTPUT,null,null,null),profile,
            profile==AccessProfile.CUSTOMER?AccessAction.KNOWLEDGE_PUBLIC:AccessAction.MANAGEMENT_READ,
            ToolDefinition.Resource.RETRIEVAL,handler);
    }
    static ActorAccess authority(DelegatedActor... actors) {
        Map<UUID,DelegatedActor> map=new HashMap<>();for(var a:actors)map.put(a.delegationId(),a);
        return new ActorAccess() {
            public AuthenticatedPrincipal validateOriginal(String c){throw new UnsupportedOperationException();}
            public AuthenticatedPrincipal requireOriginalConfigurationAccess(String c,VenueId v){throw new UnsupportedOperationException();}
            public DelegatedActor authenticateMcp(String c){throw new UnsupportedOperationException();}
            public DelegatedActor revalidate(UUID id){return map.get(id);}
        };
    }
    static LocalAdmission limiter(int concurrency) {return new LocalAdmission(new LocalAdmission.Limit(100,100,concurrency),128,System::nanoTime);}

    @Test void listAndDirectCallIndependentlyAuthorizeAndStrictlyValidateWithoutLeakingInputs() throws Exception {
        var customer=actor(AccessProfile.CUSTOMER);var management=actor(AccessProfile.MANAGEMENT);
        List<McpAudit.Event> events=new CopyOnWriteArrayList<>();
        var handler=(ToolDefinition.Handler)(c,i)->ToolOutcome.success(Map.of("scope",c.actor().tenantId().value().toString()));
        try(var audit=new McpAudit(32,events::add);var engine=new McpEngine(authority(customer,management),
                new ToolRegistry(List.of(tool("test.customer",AccessProfile.CUSTOMER,handler),
                    tool("test.management",AccessProfile.MANAGEMENT,handler))),limiter(4),audit,CLOCK,Duration.ofSeconds(1),2)) {
            var c=engine.context(customer,UUID.randomUUID());var m=engine.context(management,UUID.randomUUID());
            assertThat(engine.list(c)).extracting(McpSchema.Tool::name).containsExactly("test.customer");
            assertThat(engine.list(m)).extracting(McpSchema.Tool::name).containsExactly("test.management");
            assertThat(engine.call(c,"test.management",Map.of()).isError()).isTrue();
            List<Object> invalid=List.of(Map.of(),Map.of("query","PII_SENTINEL","count",0),Map.of("query",4,"count",1),
                Map.of("query","PROMPT_SENTINEL","count",1,"tenant","TENANT_SENTINEL"),
                Map.of("query","x","count",11),Map.of("query","x".repeat(129),"count",1),"arbitrary-client-object",
                Map.of("query","x","count",1,"principal","PRINCIPAL_SENTINEL","role","ROLE_SENTINEL","allowlist",List.of("evil")));
            for(Object input:invalid)assertThat(engine.call(c,"test.customer",input).isError()).isTrue();
            assertThatThrownBy(()->engine.call(c,"unknown-PROVIDER_SENTINEL",Map.of()))
                .isInstanceOf(McpFailure.class);
            assertThat(engine.call(c,"test.customer",Map.of("query","PROMPT_SENTINEL","count",1)).isError()).isFalse();
            await().untilAsserted(()->assertThat(events).hasSize(13));
            String serialized=JsonMapper.builder().build().writeValueAsString(events);
            assertThat(serialized).doesNotContain("PII_SENTINEL","PROMPT_SENTINEL","TENANT_SENTINEL","ROLE_SENTINEL",
                "PRINCIPAL_SENTINEL","PROVIDER_SENTINEL","arbitrary-client-object");
            assertThat(events).extracting(McpAudit.Event::outcome).contains(McpAudit.Outcome.SUCCESS,McpAudit.Outcome.DENIED);
            samples("audit-allow-deny",events);
        }
    }

    @Test void oneExplicitToolCanAuthorizeBothProfilesWithoutPermissionOrAllowlistSubstitution() throws Exception {
        var customer=actor(AccessProfile.CUSTOMER);var management=actor(AccessProfile.MANAGEMENT);
        var noGrant=new DelegatedActor(management.original(),management.originalCredentialId(),UUID.randomUUID(),
            management.tenantId(),management.venueId(),management.profile(),Set.of(),management.tools(),management.expiresAt());
        var noTool=new DelegatedActor(customer.original(),customer.originalCredentialId(),UUID.randomUUID(),
            customer.tenantId(),customer.venueId(),customer.profile(),customer.actions(),Set.of(),customer.expiresAt());
        Map<AccessProfile,AccessAction> permissions=new EnumMap<>(AccessProfile.class);
        permissions.put(AccessProfile.CUSTOMER,AccessAction.KNOWLEDGE_PUBLIC);
        permissions.put(AccessProfile.MANAGEMENT,AccessAction.MANAGEMENT_READ);
        var shared=new ToolDefinition(new McpSchema.Tool("test.customer",null,"Synthetic shared test only",INPUT,OUTPUT,null,null,null),
            permissions,ToolDefinition.Resource.RETRIEVAL,(c,i)->ToolOutcome.success(Map.of("scope",c.actor().profile().name())));
        permissions.clear(); // Composition input cannot mutate frozen permissions after registration.
        List<McpAudit.Event> events=new CopyOnWriteArrayList<>();
        try(var audit=new McpAudit(16,events::add);var engine=new McpEngine(authority(customer,management,noGrant,noTool),
                new ToolRegistry(List.of(shared)),limiter(4),audit,CLOCK,Duration.ofSeconds(1),2)) {
            for(var actor:List.of(customer,management)) {
                var context=engine.context(actor,UUID.randomUUID());
                assertThat(engine.list(context)).extracting(McpSchema.Tool::name).containsExactly("test.customer");
                assertThat(engine.call(context,"test.customer",Map.of("query","x","count",1)).isError()).isFalse();
            }
            for(var actor:List.of(noGrant,noTool)) {
                var context=engine.context(actor,UUID.randomUUID());
                assertThat(engine.list(context)).isEmpty();
                assertThat(engine.call(context,"test.customer",Map.of("query","x","count",1)).isError()).isTrue();
            }
            await().untilAsserted(()->assertThat(events).hasSize(8));
            assertThat(events.stream().filter(e->e.allowed() && e.registeredTool()!=null).map(McpAudit.Event::action))
                .containsExactly(AccessAction.KNOWLEDGE_PUBLIC,AccessAction.MANAGEMENT_READ);
        }
    }

    @Test void concurrentProfilesCarryImmutableContextAcrossExecutorChanges() throws Exception {
        var customer=actor(AccessProfile.CUSTOMER);var management=actor(AccessProfile.MANAGEMENT);
        var barrier=new CyclicBarrier(2);List<RequestContext> seen=new CopyOnWriteArrayList<>();
        ToolDefinition.Handler handler=(context,input)->{
            barrier.await(2,TimeUnit.SECONDS);
            try(var executor=Executors.newSingleThreadExecutor()) {
                return executor.submit(()-> {
                    seen.add(context);
                    assertThatThrownBy(()->input.put("principal","injected")).isInstanceOf(UnsupportedOperationException.class);
                    return ToolOutcome.success(Map.of("scope",context.actor().tenantId().value().toString()));
                }).get();
            }
        };
        try(var audit=new McpAudit(16,e->{});var engine=new McpEngine(authority(customer,management),
            new ToolRegistry(List.of(tool("test.customer",AccessProfile.CUSTOMER,handler),tool("test.management",AccessProfile.MANAGEMENT,handler))),
            limiter(4),audit,CLOCK,Duration.ofSeconds(3),2);var clients=Executors.newFixedThreadPool(2)) {
            RequestContext c=engine.context(customer,UUID.randomUUID()),m=engine.context(management,UUID.randomUUID());
            var a=clients.submit(()->engine.call(c,"test.customer",Map.of("query","x","count",1)));
            var b=clients.submit(()->engine.call(m,"test.management",Map.of("query","x","count",1)));
            assertThat(((Map<?,?>)a.get().structuredContent()).get("scope")).isEqualTo(customer.tenantId().value().toString());
            assertThat(((Map<?,?>)b.get().structuredContent()).get("scope")).isEqualTo(management.tenantId().value().toString());
            assertThat(seen).containsExactlyInAnyOrder(c,m);
            assertThat(c.requestId()).isNotEqualTo(m.requestId());
        }
    }

    @Test void timedOutContinuingWorkKeepsPermitUntilActualExitAndNeverQueuesOrReplays() throws Exception {
        var actor=actor(AccessProfile.CUSTOMER);var entered=new CountDownLatch(1);var exit=new CountDownLatch(1);
        var invocations=new AtomicLong();List<McpAudit.Event> events=new CopyOnWriteArrayList<>();
        var tool=tool("test.customer",AccessProfile.CUSTOMER,(c,i)-> {
            invocations.incrementAndGet();entered.countDown();exit.await();
            return ToolOutcome.success(Map.of("scope","effect-may-have-committed"));
        });
        try(var audit=new McpAudit(16,events::add);var engine=new McpEngine(authority(actor),new ToolRegistry(List.of(tool)),
                limiter(1),audit,CLOCK,Duration.ofMillis(80),1)) {
            var timeout=engine.call(engine.context(actor,UUID.randomUUID()),"test.customer",Map.of("query","x","count",1));
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            assertThat(timeout.isError()).isTrue();assertThat(timeout.structuredContent().toString()).contains("unknown");
            assertThat(engine.activeWorkers()).isEqualTo(1);
            assertThat(engine.call(engine.context(actor,UUID.randomUUID()),"test.customer",Map.of("query","x","count",1))
                .structuredContent().toString()).contains("rate_limited","not_dispatched");
            assertThat(invocations).hasValue(1);exit.countDown();
            await().untilAsserted(()->assertThat(engine.activeWorkers()).isZero());
            await().untilAsserted(()->assertThat(events).hasSize(2));
            assertThat(events.getFirst().outcome()).isEqualTo(McpAudit.Outcome.UNKNOWN);
            assertThat(events.getFirst().handlerTimeout()).isTrue();
            assertThat(events.getFirst().timeoutLayer()).isEqualTo(McpAudit.TimeoutLayer.HANDLER);
            samples("audit-timeout",events);
        } finally {exit.countDown();}
    }

    @Test void localQuotaCountsDenialsRetriesAndCannotResetIndebtedBucketsOrBypassPrincipalViaDelegation() {
        var actor=actor(AccessProfile.CUSTOMER);AtomicLong time=new AtomicLong(1);
        var limit=new LocalAdmission(new LocalAdmission.Limit(1,2,1),8,time::get);
        try(var p=limit.attempt(actor,"unknown",null)) {
            assertThatThrownBy(()->limit.attempt(actor,"unknown",null)).isInstanceOf(McpFailure.class);
        }
        assertThatThrownBy(()->limit.attempt(actor,"unknown",null)).isInstanceOf(McpFailure.class);
        var renewed=new DelegatedActor(actor.original(),actor.originalCredentialId(),UUID.randomUUID(),actor.tenantId(),actor.venueId(),
            actor.profile(),actor.actions(),actor.tools(),actor.expiresAt());
        assertThatThrownBy(()->limit.attempt(renewed,"unknown",null)).isInstanceOf(McpFailure.class);
        time.addAndGet(Duration.ofSeconds(2).toNanos());
        try(var p=limit.attempt(renewed,"unknown",null)) {assertThat(limit.bucketCount()).isLessThanOrEqualTo(8);}
        limit.unavailable();assertThatThrownBy(()->limit.attempt(actor,"unknown",null)).isInstanceOf(McpFailure.class);
        var restarted=new LocalAdmission(new LocalAdmission.Limit(1,2,1),8,time::get);
        try(var p=restarted.attempt(actor,"unknown",null)) {assertThat(restarted.bucketCount()).isEqualTo(4);}
        assertThatThrownBy(()->new LocalAdmission.Limit(Double.NaN,1,1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void auditQueueFullAndUnavailableSinkNeverChangeSuccessAndExceptionsRemainRedacted() throws Exception {
        var actor=actor(AccessProfile.CUSTOMER);var sinkEntered=new CountDownLatch(1);var sinkExit=new CountDownLatch(1);
        try(var audit=new McpAudit(1,event->{sinkEntered.countDown();try{sinkExit.await();}catch(InterruptedException ignored){}
                throw new IllegalStateException("SECRET_PROVIDER_SQL_SENTINEL");});
            var engine=new McpEngine(authority(actor),new ToolRegistry(List.of(tool("test.customer",AccessProfile.CUSTOMER,
                (c,i)->ToolOutcome.success(Map.of("scope","ok"))))),limiter(10),audit,CLOCK,Duration.ofSeconds(2),1)) {
            for(int i=0;i<5;i++) {
                assertThat(engine.call(engine.context(actor,UUID.randomUUID()),"test.customer",Map.of("query","secret-token-PII-prompt", "count",1)).isError()).isFalse();
                if(i==0)assertThat(sinkEntered.await(1,TimeUnit.SECONDS)).isTrue();
            }
            assertThat(audit.dropped()).isGreaterThan(0);sinkExit.countDown();
            await().untilAsserted(()->assertThat(audit.failed()).isGreaterThan(0));
        } finally {sinkExit.countDown();}
        List<McpAudit.Event> events=new CopyOnWriteArrayList<>();
        try(var audit=new McpAudit(4,events::add);var engine=new McpEngine(authority(actor),new ToolRegistry(List.of(
            tool("test.customer",AccessProfile.CUSTOMER,(c,i)->{throw new IllegalStateException("PROVIDER_ERROR_SENTINEL");}))),
                limiter(2),audit,CLOCK,Duration.ofSeconds(1),1)) {
            var result=engine.call(engine.context(actor,UUID.randomUUID()),"test.customer",Map.of("query","PII_PROMPT_SENTINEL","count",1));
            assertThat(result.structuredContent().toString()).contains("unknown").doesNotContain("SENTINEL");
            await().untilAsserted(()->assertThat(events).hasSize(1));
            assertThat(events.toString()).doesNotContain("SENTINEL");
            samples("audit-unknown",events);
        }
        events.clear();UUID known=UUID.randomUUID(),productRequest=UUID.randomUUID();
        try(var audit=new McpAudit(4,events::add);var engine=new McpEngine(authority(actor),new ToolRegistry(List.of(
            tool("test.customer",AccessProfile.CUSTOMER,(c,i)->new ToolOutcome(Map.of("scope","unknown"),true,known,
                productRequest,null,null,null,null,McpFailure.Reason.TIMEOUT,McpAudit.TimeoutLayer.PRODUCT_RESPONSE)))),
                limiter(2),audit,CLOCK,Duration.ofSeconds(1),1)) {
            var result=engine.call(engine.context(actor,UUID.randomUUID()),"test.customer",Map.of("query","x","count",1));
            assertThat(result.isError()).isTrue();assertThat(result.meta().get("knownTarget")).isEqualTo(known.toString());
            assertThat(result.meta().get("productRequestId")).isEqualTo(productRequest.toString());
            await().untilAsserted(()->assertThat(events).hasSize(1));
            assertThat(events.getFirst().outcome()).isEqualTo(McpAudit.Outcome.UNKNOWN);
            assertThat(events.getFirst().dispatch()).isEqualTo(McpAudit.Dispatch.REPORTED);
            assertThat(events.getFirst().timeoutLayer()).isEqualTo(McpAudit.TimeoutLayer.PRODUCT_RESPONSE);
            samples("audit-downstream-unknown",events);
        }
    }
    static void samples(String name,List<McpAudit.Event> events) throws Exception {
        var path=java.nio.file.Path.of("build/mcp/"+name+".json");java.nio.file.Files.createDirectories(path.getParent());
        java.nio.file.Files.writeString(path,JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(events));
    }
}
