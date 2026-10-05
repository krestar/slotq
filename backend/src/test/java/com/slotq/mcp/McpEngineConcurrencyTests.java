package com.slotq.mcp;

import com.slotq.auth.access.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.slotq.mcp.McpFoundationTests.*;

class McpEngineConcurrencyTests {
    @Test void completedFutureCanStillBeRejectedByTheOldSynchronousHandoff() throws Exception {
        var completed=new CountDownLatch(1);var leave=new CountDownLatch(1);var executing=new AtomicInteger();
        var pool=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<Runnable>(),
                Thread.ofPlatform().daemon().factory(),new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void afterExecute(Runnable task,Throwable failure) {
                completed.countDown();waitFor(leave);
            }
        };
        try {
            var first=pool.submit(()->{executing.incrementAndGet();try{return "done";}finally{executing.decrementAndGet();}});
            assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo("done");
            assertThat(completed.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(executing).hasValue(0);
            assertThatThrownBy(()->pool.submit(()->"next")).isInstanceOf(RejectedExecutionException.class);
        } finally {leave.countDown();pool.shutdown();}
    }

    @Test void sequentialCallsImmediatelyReuseOneActualWorkSlot() {
        var actor=actor(AccessProfile.CUSTOMER);var invocations=new AtomicInteger();
        try(var audit=new McpAudit(1,event->{});var engine=engine(actor,audit,Duration.ofSeconds(10),1,quota(8),
                (c,i)->{invocations.incrementAndGet();return success();},threads())) {
            for(int i=0;i<1000;i++) {
                assertThat(call(engine,actor,2).isError()).as("sequential call %s",i).isFalse();
                assertThat(engine.activeWorkers()).isZero();
            }
            assertThat(invocations).hasValue(1000);
        }
    }

    @Test void completedRunnerThreadEpiloguesCannotDelaySequentialAdmission() throws Exception {
        var actor=actor(AccessProfile.CUSTOMER);var leave=new CountDownLatch(1);
        var tails=new ArrayList<CountDownLatch>();for(int i=0;i<20;i++)tails.add(new CountDownLatch(1));
        var started=new AtomicInteger();
        ThreadFactory factory=task->{int index=started.getAndIncrement();return threads().newThread(()->{
            task.run();tails.get(index).countDown();waitFor(leave);
        });};
        try(var audit=new McpAudit(1,event->{});var engine=engine(actor,audit,Duration.ofSeconds(10),1,quota(1),(c,i)->success(),factory)) {
            for(int i=0;i<tails.size();i++) {
                assertThat(call(engine,actor,2).isError()).isFalse();
                assertThat(tails.get(i).await(5,TimeUnit.SECONDS)).isTrue();
                // The previous thread remains deliberately parked after its actual work and Future completed.
                assertThat(engine.activeWorkers()).isZero();
            }
        } finally {leave.countDown();}
    }

    @ParameterizedTest @ValueSource(ints={1,3})
    void actualInFlightWorkRejectsNextWithoutQueueAndReusesCompletedSlot(int capacity) throws Exception {
        var actor=actor(AccessProfile.CUSTOMER);var entered=new CountDownLatch(capacity);
        var exits=new ArrayList<CountDownLatch>();for(int i=0;i<capacity;i++)exits.add(new CountDownLatch(1));
        var invocations=new AtomicInteger();var executing=new AtomicInteger();var maximum=new AtomicInteger();
        ToolDefinition.Handler handler=(c,input)->{
            invocations.incrementAndGet();int active=executing.incrementAndGet();maximum.accumulateAndGet(active,Math::max);
            int slot=((Number)input.get("count")).intValue();
            try {if(slot<=capacity){entered.countDown();exits.get(slot-1).await();}return success();}
            finally {executing.decrementAndGet();}
        };
        try(var audit=new McpAudit(32,event->{});var engine=engine(actor,audit,Duration.ofSeconds(10),capacity,quota(8),handler,threads());
                var clients=Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var admitted=new ArrayList<Future<McpSchema.CallToolResult>>();
                for(int i=1;i<=capacity;i++){int slot=i;admitted.add(clients.submit(()->call(engine,actor,slot)));}
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(engine.activeWorkers()).isEqualTo(capacity);
                assertRateLimited(clients.submit(()->call(engine,actor,capacity+1)).get(2,TimeUnit.SECONDS));
                assertThat(invocations).hasValue(capacity);assertThat(executing).hasValue(capacity);
                exits.getFirst().countDown();
                assertThat(admitted.getFirst().get(5,TimeUnit.SECONDS).isError()).isFalse();
                assertThat(engine.activeWorkers()).isEqualTo(capacity-1);
                assertThat(call(engine,actor,capacity+1).isError()).isFalse();
                assertThat(invocations).hasValue(capacity+1);assertThat(maximum.get()).isEqualTo(capacity);
                exits.forEach(CountDownLatch::countDown);
                for(var result:admitted)assertThat(result.get(5,TimeUnit.SECONDS).isError()).isFalse();
                assertThat(engine.activeWorkers()).isZero();
                // The rejected request never runs later when the active requests leave.
                assertThat(invocations).hasValue(capacity+1);
            } finally {exits.forEach(CountDownLatch::countDown);}
        }
    }

    @Test void callerTimeoutRetainsWorkerCapacityUntilActualWorkReturns() throws Exception {
        var actor=actor(AccessProfile.CUSTOMER);var entered=new CountDownLatch(1);var exit=new CountDownLatch(1);
        var finished=new CountDownLatch(1);var invocations=new AtomicInteger();
        ToolDefinition.Handler handler=(c,input)->{
            invocations.incrementAndGet();
            if(((Number)input.get("count")).intValue()==1){entered.countDown();exit.await();}
            return success();
        };
        try(var audit=new McpAudit(8,event->{});var engine=engine(actor,audit,Duration.ofSeconds(1),1,quota(8),handler,finishedThreads(finished));
                var clients=Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var running=clients.submit(()->call(engine,actor,1));
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                var timeout=running.get(3,TimeUnit.SECONDS);
                assertThat(timeout.structuredContent().toString()).contains("timeout","unknown");
                assertThat(engine.activeWorkers()).isEqualTo(1);assertThat(finished.getCount()).isEqualTo(1);
                assertRateLimited(call(engine,actor,2));assertThat(invocations).hasValue(1);
                exit.countDown();assertThat(finished.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(engine.activeWorkers()).isZero();
                assertThat(call(engine,actor,2).isError()).isFalse();assertThat(invocations).hasValue(2);
            } finally {exit.countDown();}
        }
    }

    @Test void callerInterruptDoesNotCancelWorkOrReleaseCapacity() throws Exception {
        var actor=actor(AccessProfile.CUSTOMER);var entered=new CountDownLatch(1);var exit=new CountDownLatch(1);
        var finished=new CountDownLatch(1);var interrupted=new AtomicBoolean();
        ToolDefinition.Handler handler=(c,input)->{
            if(((Number)input.get("count")).intValue()==1){entered.countDown();exit.await();}return success();
        };
        try(var audit=new McpAudit(8,event->{});var engine=engine(actor,audit,Duration.ofSeconds(10),1,quota(8),handler,finishedThreads(finished))) {
            var result=new FutureTask<McpSchema.CallToolResult>(()->{
                var outcome=call(engine,actor,1);interrupted.set(Thread.currentThread().isInterrupted());return outcome;
            });
            Thread caller=Thread.ofPlatform().daemon().start(result);
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();caller.interrupt();
                assertThat(result.get(3,TimeUnit.SECONDS).structuredContent().toString()).contains("unknown");
                assertThat(interrupted).isTrue();assertThat(engine.activeWorkers()).isEqualTo(1);
                assertRateLimited(call(engine,actor,2));
                exit.countDown();assertThat(finished.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(engine.activeWorkers()).isZero();assertThat(call(engine,actor,2).isError()).isFalse();
            } finally {exit.countDown();}
        }
    }

    @Test void rejectedThreadStartReleasesOnlyUndispatchedCapacityAndQuotaWithoutRetry() {
        var actor=actor(AccessProfile.CUSTOMER);var attempts=new AtomicInteger();var invocations=new AtomicInteger();
        ThreadFactory factory=task->{if(attempts.getAndIncrement()==0)throw new RejectedExecutionException("synthetic start failure");return threads().newThread(task);};
        try(var audit=new McpAudit(8,event->{});var engine=engine(actor,audit,Duration.ofSeconds(10),1,quota(1),
                (c,i)->{invocations.incrementAndGet();return success();},factory)) {
            assertRateLimited(call(engine,actor,2));assertThat(engine.activeWorkers()).isZero();
            assertThat(attempts).hasValue(1);assertThat(invocations).hasValue(0);
            assertThat(call(engine,actor,2).isError()).isFalse();
            assertThat(attempts).hasValue(2);assertThat(invocations).hasValue(1);assertThat(engine.activeWorkers()).isZero();
        }
    }

    private static McpEngine engine(DelegatedActor actor,McpAudit audit,Duration budget,int capacity,LocalAdmission quota,
            ToolDefinition.Handler handler,ThreadFactory threads) {
        return new McpEngine(authority(actor),new ToolRegistry(List.of(tool("test.customer",AccessProfile.CUSTOMER,handler))),quota,audit,CLOCK,budget,capacity,threads);
    }
    private static LocalAdmission quota(int concurrency){return new LocalAdmission(new LocalAdmission.Limit(100000,5000,concurrency),128,System::nanoTime);}
    private static McpSchema.CallToolResult call(McpEngine engine,DelegatedActor actor,int count){return engine.call(engine.context(actor,UUID.randomUUID()),"test.customer",Map.of("query","x","count",count));}
    private static ToolOutcome success(){return ToolOutcome.success(Map.of("scope","ok"));}
    private static ThreadFactory threads(){return Thread.ofPlatform().daemon().factory();}
    private static ThreadFactory finishedThreads(CountDownLatch finished){return task->threads().newThread(()->{try{task.run();}finally{finished.countDown();}});}
    private static void waitFor(CountDownLatch latch){try{latch.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    private static void assertRateLimited(McpSchema.CallToolResult result){assertThat(result.isError()).isTrue();assertThat(result.structuredContent().toString()).contains("rate_limited","not_dispatched");}
}
