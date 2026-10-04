package com.slotq.mcp;

import com.slotq.auth.access.ActorAccess;
import com.slotq.auth.access.DelegatedActor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Explicit across threads; no credential, incoming trace context or mutable authority. */
public record RequestContext(DelegatedActor actor, UUID requestId, Instant admittedAt, Instant deadline) {
    public DelegatedActor revalidate(ActorAccess authority, Clock clock) {
        if (clock.instant().isBefore(admittedAt)) throw new McpFailure(McpFailure.Reason.UNAVAILABLE);
        if (!clock.instant().isBefore(deadline)) throw new McpFailure(McpFailure.Reason.TIMEOUT);
        DelegatedActor live = authority.revalidate(actor.delegationId());
        if (!live.equals(actor)) throw new McpFailure(McpFailure.Reason.FORBIDDEN);
        return live;
    }
    public Duration remaining(Clock clock, Duration maximum) {
        if (clock.instant().isBefore(admittedAt)) throw new McpFailure(McpFailure.Reason.UNAVAILABLE);
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isNegative() || remaining.isZero()) throw new McpFailure(McpFailure.Reason.TIMEOUT);
        return remaining.compareTo(maximum) < 0 ? remaining : maximum;
    }
}
