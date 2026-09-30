package com.slotq.integration.operations.recovery;

/** Fixed, PII-free response codes; never return JDBC, token or payload diagnostics. */
public final class RecoveryProblem extends RuntimeException {
    private final int status;
    private final String code;

    public RecoveryProblem(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
    static RecoveryProblem hidden() { return new RecoveryProblem(404, "TARGET_NOT_FOUND"); }
    static RecoveryProblem stale() { return new RecoveryProblem(409, "RECOVERY_STATE_CONFLICT"); }
    static RecoveryProblem invalid() { return new RecoveryProblem(400, "INVALID_RECOVERY_REQUEST"); }
}
