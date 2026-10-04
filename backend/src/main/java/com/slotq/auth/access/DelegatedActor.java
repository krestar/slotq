package com.slotq.auth.access;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** An immutable observation; consumers must revalidate at their authority boundary. */
public record DelegatedActor(AuthenticatedPrincipal original, UUID originalCredentialId, UUID delegationId,
        TenantId tenantId, VenueId venueId, AccessProfile profile, Set<AccessAction> actions,
        Set<String> tools, Instant expiresAt) {
    public DelegatedActor {
        actions = Set.copyOf(actions);
        tools = Set.copyOf(tools);
    }
    public boolean permits(String tool, AccessProfile requiredProfile, AccessAction action) {
        return profile == requiredProfile && tools.contains(tool) && actions.contains(action);
    }
}
