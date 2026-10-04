package com.slotq.mcp;

import com.slotq.auth.access.DelegatedActor;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** One live instance. No refunds, queues or eviction of indebted/in-flight buckets. */
public final class LocalAdmission {
    public record Limit(double rate, int burst, int concurrency) {
        public Limit { if (!Double.isFinite(rate) || rate <= 0 || burst <= 0 || concurrency <= 0)
            throw new IllegalArgumentException("Positive finite quota required"); }
    }
    private final Limit limit;
    private final int cardinality;
    private final LongSupplier ticker;
    private final Map<Key, Bucket> buckets = new HashMap<>();
    private boolean available = true;
    public LocalAdmission(Limit limit, int cardinality, LongSupplier ticker) {
        if (cardinality < 8 || cardinality > 65536) throw new IllegalArgumentException("Quota cardinality bound");
        this.limit=limit; this.cardinality=cardinality; this.ticker=ticker;
    }
    public synchronized Permit attempt(DelegatedActor actor, String registeredTool, ToolDefinition.Resource resource) {
        if (!available) throw new McpFailure(McpFailure.Reason.UNAVAILABLE);
        long now=ticker.getAsLong();
        List<Key> keys = new ArrayList<>(List.of(new Key("principal", actor.original().principalId().value().toString()),
            new Key("delegation", actor.delegationId().toString()), new Key("tenant", actor.tenantId().value().toString()),
            new Key("tool", registeredTool)));
        if (resource != null) keys.add(new Key("resource", resource.name()));
        List<Bucket> selected=new ArrayList<>();
        for (Key key:keys) {
            Bucket bucket=buckets.get(key);
            if (bucket==null) {
                evict(now);
                if (buckets.size()>=cardinality) throw new McpFailure(McpFailure.Reason.RATE_LIMITED);
                bucket=new Bucket(now); buckets.put(key,bucket);
            }
            bucket.refill(now);
            // Counts validation, forbidden and explicit retry attempts; no later-layer refund.
            if (bucket.tokens<1) throw new McpFailure(McpFailure.Reason.RATE_LIMITED);
            bucket.tokens-=1;
            selected.add(bucket);
        }
        if (selected.stream().anyMatch(b->b.inFlight>=limit.concurrency()))
            throw new McpFailure(McpFailure.Reason.RATE_LIMITED);
        selected.forEach(b->b.inFlight++);
        return new Permit(selected);
    }
    private void evict(long now) {
        buckets.values().removeIf(b->{ b.refill(now); return b.inFlight==0 && b.tokens>=limit.burst(); });
    }
    public synchronized void unavailable() { available=false; }
    public synchronized int bucketCount() { return buckets.size(); }
    public final class Permit implements AutoCloseable {
        private List<Bucket> held;
        private Permit(List<Bucket> held) { this.held=held; }
        @Override public void close() { synchronized(LocalAdmission.this) {
            if(held!=null) { held.forEach(b->b.inFlight--); held=null; }
        }}
    }
    private record Key(String layer, String identity) { }
    private final class Bucket {
        double tokens=limit.burst(); int inFlight; long updated;
        Bucket(long now) { updated=now; }
        void refill(long now) {
            if(now<updated) { available=false; throw new McpFailure(McpFailure.Reason.UNAVAILABLE); }
            tokens=Math.min(limit.burst(),tokens+(now-updated)/1_000_000_000.0*limit.rate()); updated=now;
        }
    }
}
