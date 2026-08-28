package com.slotq.auth.domain;

import java.util.Objects;
import java.util.Set;

import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;

public record ActorContext(
    PrincipalId principalId,
    TenantId tenantId,
    TenantRole role,
    Set<VenueId> venueGrants
) {

    public ActorContext {
        Objects.requireNonNull(principalId, "principalId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(role, "role must not be null");
        venueGrants = Set.copyOf(Objects.requireNonNull(venueGrants, "venueGrants must not be null"));
    }

    public boolean canAccess(VenueId venueId) {
        return venueGrants.contains(venueId);
    }

    public boolean canManageConfiguration() {
        return role == TenantRole.OWNER || role == TenantRole.MANAGER;
    }
}
