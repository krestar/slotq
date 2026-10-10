package com.slotq.ai.router;

import java.time.*;

/** Provider-only accounting supplied by #153's admission boundary; no Run or Product authority. */
public final class ProviderBudget {
    private final Clock clock;
    private final Instant deadline;
    private int remainingAttempts;
    private long remainingTokens;
    private boolean usageUnknown;
    private boolean inFlight;
    private long activeReservation;
    public ProviderBudget(Clock clock, Instant deadline, int attempts, long tokens) {
        if (clock == null || deadline == null || attempts < 1 || attempts > 100 || tokens < 1 || tokens > 10_000_000)
            throw new IllegalArgumentException("Invalid provider budget");
        this.clock=clock; this.deadline=deadline; this.remainingAttempts=attempts; this.remainingTokens=tokens;
    }
    public synchronized long begin(long reservedTokens) {
        long millis = Duration.between(clock.instant(), deadline).toMillis();
        if (inFlight || usageUnknown || remainingAttempts < 1 || reservedTokens < 1 || remainingTokens < reservedTokens || millis < 1)
            throw new IllegalStateException("Provider budget unavailable");
        remainingAttempts--; remainingTokens-=reservedTokens; inFlight=true; activeReservation=reservedTokens;
        return Math.min(millis, 45_000);
    }
    public synchronized void end(long reservedTokens, Long observedTokens) {
        if (!inFlight || reservedTokens != activeReservation) throw new IllegalStateException("Provider reservation mismatch");
        inFlight=false;
        if (observedTokens == null || observedTokens < 0 || observedTokens > reservedTokens) usageUnknown=true;
        else remainingTokens += reservedTokens-observedTokens;
    }
    public synchronized int remainingAttempts() { return remainingAttempts; }
    public synchronized long remainingTokens() { return remainingTokens; }
    public synchronized boolean usageUnknown() { return usageUnknown; }
    public synchronized boolean inFlight() { return inFlight; }
}
