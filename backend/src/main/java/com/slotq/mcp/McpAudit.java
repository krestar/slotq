package com.slotq.mcp;

import com.slotq.auth.access.AccessAction;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Bounded, non-blocking best effort. No client object or free-form failure text can enter the event. */
public final class McpAudit implements AutoCloseable {
    public enum Outcome { SUCCESS, DENIED, NOT_DISPATCHED, UNKNOWN, UNAVAILABLE }
    public enum Dispatch { NOT_DISPATCHED, REPORTED, UNAVAILABLE }
    public enum TimeoutLayer { NONE, MCP_REQUEST, HANDLER, PRODUCT_CONNECT, PRODUCT_RESPONSE, RETRIEVAL_PROVIDER }
    public record Event(UUID requestId, UUID principalId, UUID delegationId, UUID tenantId, UUID venueId,
        String registeredTool, AccessAction action, boolean allowed, McpFailure.Reason reason,
        Dispatch dispatch, Outcome outcome, long latencyMillis, boolean handlerTimeout, TimeoutLayer timeoutLayer,
        UUID confirmationId, UUID intentId, UUID knownTarget, UUID productRequestId, UUID documentId, UUID versionId) { }
    private final ArrayBlockingQueue<Event> queue;
    private final Consumer<Event> sink;
    private final LongAdder dropped=new LongAdder(), failed=new LongAdder(), delivered=new LongAdder();
    private final Thread worker;
    private volatile boolean running=true;
    public McpAudit(int capacity, Consumer<Event> sink) {
        if(capacity<1 || capacity>65536) throw new IllegalArgumentException("Audit queue bound");
        this.queue=new ArrayBlockingQueue<>(capacity); this.sink=sink;
        worker=Thread.ofPlatform().daemon().name("mcp-audit").start(this::drain);
    }
    public void offer(Event event) { if(!running || !queue.offer(event)) dropped.increment(); }
    public long dropped() { return dropped.sum(); }
    public long failed() { return failed.sum(); }
    public long delivered() { return delivered.sum(); }
    private void drain() {
        while(running) try {
            Event event=queue.take();
            try { sink.accept(event); delivered.increment(); }
            catch(RuntimeException ignored) { failed.increment(); }
        } catch(InterruptedException ignored) { if(!running) return; }
    }
    @Override public void close() { running=false; worker.interrupt(); }
}
