package com.slotq.auth.application;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccessControlService implements AccessControlProvisioning {

    private final AccessControlRepository repository;

    AccessControlService(AccessControlRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void registerPrincipal(PrincipalId principalId) {
        repository.registerPrincipal(principalId);
    }

    @Override
    @Transactional
    public void assignMembership(PrincipalId principalId, TenantId tenantId, TenantRole role) {
        repository.saveMembership(principalId, tenantId, role);
    }

    @Override
    @Transactional
    public void grantVenue(PrincipalId principalId, TenantId tenantId, TenantRole role, VenueId venueId) {
        repository.saveVenueGrant(principalId, tenantId, role, venueId);
    }
}
