package com.slotq.booking.application;

/** Invalid stored routing evidence, never a normal capacity/eligibility refusal. */
public final class PromotionReferenceException extends RuntimeException {
    public enum Reason { MISSING, TENANT_MISMATCH, SCOPE_MISMATCH }
    private final Reason reason;
    public PromotionReferenceException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
    public Reason reason() { return reason; }
}
