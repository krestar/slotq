package com.slotq.ai.runtime;

/** Stable safe reasons only; credentials, input and downstream exception text never escape. */
public final class RuntimeFailure extends RuntimeException {
    public enum Reason { AUTHORITY, UNAVAILABLE, ADMISSION, BUDGET, DEADLINE, STATE, PROPOSAL, APPROVAL }
    private final Reason reason;
    public RuntimeFailure(Reason reason) { super(reason.name(),null,false,false);this.reason=reason; }
    public Reason reason() { return reason; }
}
