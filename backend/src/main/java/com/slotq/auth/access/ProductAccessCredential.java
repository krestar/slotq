package com.slotq.auth.access;

import java.time.Instant;

/** Server-side secret: deliberately has no value-bearing toString. */
public final class ProductAccessCredential {
    private final String value;
    private final Instant expiresAt;
    private final java.util.UUID id;
    public ProductAccessCredential(String value, Instant expiresAt) { this(value, expiresAt, null); }
    public ProductAccessCredential(String value, Instant expiresAt, java.util.UUID id) { this.value=value;this.expiresAt=expiresAt;this.id=id; }
    public String value() { return value; }
    public Instant expiresAt() { return expiresAt; }
    public java.util.UUID id() { return id; }
    @Override public String toString() { return "ProductAccessCredential[redacted]"; }
}
