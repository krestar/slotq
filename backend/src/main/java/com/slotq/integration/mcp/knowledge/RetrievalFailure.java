package com.slotq.integration.mcp.knowledge;

/** Never retain provider/SQL/process text or causes. */
public final class RetrievalFailure extends RuntimeException {
    private final boolean timeout;
    public RetrievalFailure(boolean timeout) { super(timeout ? "timeout" : "unavailable", null, false, false); this.timeout = timeout; }
    public boolean timeout() { return timeout; }
}
