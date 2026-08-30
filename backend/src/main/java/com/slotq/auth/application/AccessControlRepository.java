package com.slotq.auth.application;

import java.util.List;
import java.util.Optional;

import com.slotq.auth.domain.ActorContext;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;

public interface AccessControlRepository {

    void registerPrincipal(PrincipalId principalId);

    void saveMembership(PrincipalId principalId, TenantId tenantId, TenantRole role);

    void saveVenueGrant(PrincipalId principalId, TenantId tenantId, TenantRole role, VenueId venueId);

    List<ActorContext> findActorsForPrincipalScopeDiscovery(PrincipalId principalId);

    Optional<ActorContext> findActorForVenue(PrincipalId principalId, VenueId verifiedTargetVenueId);
}
