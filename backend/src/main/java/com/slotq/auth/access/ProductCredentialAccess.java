package com.slotq.auth.access;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProductCredentialAccess {
    ProductAccessCredential issueProduct(UUID delegation, ProductOperation operation, UUID target, Instant deadline);
    Optional<AuthenticatedPrincipal> authenticateProduct(String credential, String method, String path);
}
