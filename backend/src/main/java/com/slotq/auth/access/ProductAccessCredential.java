package com.slotq.auth.access;

import java.time.Instant;

/** Server-side secret: deliberately has no value-bearing toString. */
public final class ProductAccessCredential {
    private final String value;
    private final Instant expiresAt;
    public ProductAccessCredential(String value, Instant expiresAt) { this.value = value; this.expiresAt = expiresAt; }
    public String value() { return value; }
    public Instant expiresAt() { return expiresAt; }
    @Override public String toString() { return "ProductAccessCredential[redacted]"; }
}
