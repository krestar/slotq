package com.slotq.auth.access;

/** No external exception text may cross this boundary. */
public final class AccessFailure extends RuntimeException {
    public enum Reason { UNAUTHENTICATED, FORBIDDEN, UNAVAILABLE }
    private final Reason reason;
    public AccessFailure(Reason reason) { super(reason.name(), null, false, false); this.reason = reason; }
    public Reason reason() { return reason; }
}
