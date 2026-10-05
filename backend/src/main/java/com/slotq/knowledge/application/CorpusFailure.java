package com.slotq.knowledge.application;

/** Safe categories only; never include credentials, payloads, or provider error text. */
public class CorpusFailure extends RuntimeException {
    public enum Reason { NOT_FOUND, SCOPE_MISMATCH, STALE_REVISION, IMMUTABLE_VERSION, INVALID_STATE, VALIDATION_FAILED }
    private final Reason reason;
    public CorpusFailure(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}
