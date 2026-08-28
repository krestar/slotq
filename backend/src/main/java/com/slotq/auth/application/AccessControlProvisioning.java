package com.slotq.auth.application;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;

public interface AccessControlProvisioning {

    void registerPrincipal(PrincipalId principalId);

    void assignMembership(PrincipalId principalId, TenantId tenantId, TenantRole role);

    void grantVenue(PrincipalId principalId, TenantId tenantId, TenantRole role, VenueId venueId);
}
