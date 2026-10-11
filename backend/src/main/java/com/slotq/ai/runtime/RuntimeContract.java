package com.slotq.ai.runtime;

import com.slotq.ai.router.*;
import com.slotq.auth.access.DelegatedActor;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Server-owned inputs and bounded result surface. None of these records is an HTTP DTO. */
public final class RuntimeContract {
    public enum Execution { ACTIVE, WAITING_CONFIRMATION, CANCEL_REQUESTED, DEADLINE_ENDED, BUDGET_ENDED, TERMINAL }
    public enum Invocation { NOT_DISPATCHED, RESPONSE_OBSERVED, UNKNOWN }
    public enum Effect { NOT_APPLICABLE, SUCCEEDED, REJECTED, UNKNOWN }
    public enum Stage { PROPOSAL, ANSWER }
    public record Limits(int steps, int providerAttempts, int toolAttempts, long tokens, long outputTokens,
                         BigDecimal dollars, Duration lifetime, Duration attemptTimeout) {
        public Limits {
            if(steps<1 || steps>64 || providerAttempts<1 || providerAttempts>32 || toolAttempts<1 || toolAttempts>32
                || tokens<1 || tokens>10_000_000 || outputTokens<1 || outputTokens>65536
                || dollars==null || dollars.signum()<0 || dollars.compareTo(BigDecimal.TEN)>0
                || lifetime==null || lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofMinutes(15))>0
                || attemptTimeout==null || attemptTimeout.isNegative() || attemptTimeout.isZero()
                || attemptTimeout.compareTo(Duration.ofSeconds(45))>0) throw new IllegalArgumentException("Invalid Runtime bounds");
        }
        public static Limits bounded() {
            return new Limits(12,3,6,150000,6144,BigDecimal.ZERO,Duration.ofMinutes(10),Duration.ofSeconds(30));
        }
    }
    public record HoldMaterial(UUID slot, int partySize) {
        public HoldMaterial { if(slot==null || partySize<1 || partySize>20) throw new IllegalArgumentException("Invalid HOLD material"); }
    }
    public record Plan(String revision, String workload, Limits limits, HoldMaterial hold,
                       Map<String,Map<String,Object>> reads) {
        public Plan {
            if(revision==null || !revision.matches("[a-zA-Z0-9._-]{1,80}") || !Set.of("customer","management","ops").contains(workload)
                || limits==null || reads==null || reads.size()>8 || (hold!=null && !workload.equals("customer")))
                throw new IllegalArgumentException("Invalid Runtime plan");
            Map<String,Map<String,Object>> frozen=new TreeMap<>();
            reads.forEach((name,args)-> {
                if(!Set.of("reservation.get","management.reservations.list","knowledge.search").contains(name))
                    throw new IllegalArgumentException("Unsupported Runtime read");
                frozen.put(name,RuntimeData.freeze(args));
            });
            reads=Collections.unmodifiableMap(frozen);
        }
        @Override public String toString() { return "RuntimePlan["+revision+","+workload+"]"; }
    }
    public record Context(ModelRouter.Classification classification, ProviderProtocol.Request request) {
        public Context {
            if(classification==null || request==null || request.prompt()==null || request.prompt().length()>8192
                || classification.inputTokens()<1 || classification.inputTokens()>100000
                || request.maximumOutputTokens()<1 || request.maximumOutputTokens()>2048
                || classification.outputTokens()!=request.maximumOutputTokens()) throw new IllegalArgumentException("Invalid Runtime context");
        }
        @Override public String toString() { return "RuntimeContext[withheld]"; }
    }
    /** Trusted, local, nonblocking context assembly; no I/O or tool dispatch. Retrieved text is untrusted data. */
    @FunctionalInterface public interface ContextSource {
        Context prepare(DelegatedActor current, Stage stage, Result facts);
    }
    @FunctionalInterface public interface Provider {
        GeminiAdapter.Result generate(String model, ModelRouter.Classification classification,
                                      ProviderProtocol.Request request, ProviderBudget budget);
    }
    public record ApprovalReview(UUID runId, UUID proposalId, UUID intentId, UUID tenant, UUID venue,
                                 UUID slot, int partySize, Instant expiresAt) { }
    public record ProviderObservation(UUID attemptId, int step, String model, boolean localCompleted,
                                      boolean remoteUnknown, boolean usageUnknown) { }
    public record ToolObservation(UUID invocationId, UUID proposalId, int step, String tool, Invocation invocation,
                                  Effect effect, UUID knownTarget, Map<String,Object> structured) {
        public ToolObservation { structured=RuntimeData.freeze(structured); }
        @Override public String toString() { return "ToolObservation["+invocationId+","+tool+","+invocation+","+effect+"]"; }
    }
    public record Budget(int steps, int providerAttempts, int toolAttempts, long remainingTokens,
                         long remainingOutputTokens, BigDecimal remainingDollars, boolean usageUnknown,
                         int localWork, Instant admittedAt, Instant deadline) { }
    public record Result(UUID runId, Execution execution, Effect productEffect, UUID knownTarget,
                         Map<String,Object> productResult, Map<String,Object> reconciliationResult, List<ProviderObservation> providers, List<ToolObservation> tools, Budget budget,
                         String answer, String failure) {
        public Result { productResult=RuntimeData.freeze(productResult);reconciliationResult=RuntimeData.freeze(reconciliationResult);providers=List.copyOf(providers);tools=List.copyOf(tools); }
        @Override public String toString() { return "RuntimeResult["+runId+","+execution+","+productEffect+"]"; }
    }
    private RuntimeContract() { }
}
