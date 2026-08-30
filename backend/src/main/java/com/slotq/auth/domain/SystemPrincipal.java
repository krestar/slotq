package com.slotq.auth.domain;

/** Marker for trusted, in-process system commands. It is never created by HTTP authentication. */
public enum SystemPrincipal {
    INSTANCE
}
