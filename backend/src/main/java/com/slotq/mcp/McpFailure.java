package com.slotq.mcp;

public final class McpFailure extends RuntimeException {
    public enum Reason { VALIDATION, FORBIDDEN, RATE_LIMITED, UNAVAILABLE, TIMEOUT, UNKNOWN, UNKNOWN_TOOL, PROTOCOL }
    private final Reason reason;
    public McpFailure(Reason reason) { super(reason.name(), null, false, false); this.reason = reason; }
    public Reason reason() { return reason; }
}
