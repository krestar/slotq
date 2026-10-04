package com.slotq.auth.access;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.venue.domain.VenueId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProductCredentialAccess {
    ProductAccessCredential issueProduct(UUID delegation, ProductOperation operation, UUID target, Instant deadline);
    ProductAccessCredential issueHold(UUID delegation, UUID slot, int partySize, String key, Instant deadline);
    Optional<AuthenticatedPrincipal> authenticateProduct(String credential, String method, String path);
    /** Current Auth-owned PRODUCT record at application admission; ordinary principals are unchanged. */
    void requireAdmission(AuthenticatedPrincipal principal, ProductOperation operation, VenueId venue, UUID target);
    void requireHoldAdmission(AuthenticatedPrincipal principal, VenueId venue, UUID slot, int partySize, String key);
    void requireBoundedExecution();
    /** Local accounting only, never authentication or business outcome. Includes pre-admission current-Auth reads. */
    RequestLease trackRequest(String credential);
    void awaitRequestEnd(ProductAccessCredential credential);
    @FunctionalInterface interface RequestLease extends AutoCloseable { @Override void close(); }
}
