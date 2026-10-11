package com.slotq.auth.access;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.venue.domain.VenueId;
import java.util.UUID;

/** Shared Auth boundary for MCP and future controlled corpus authoring. */
public interface ActorAccess {
    AuthenticatedPrincipal validateOriginal(String credential);
    AuthenticatedPrincipal requireOriginalConfigurationAccess(String credential, VenueId venue);
    DelegatedActor authenticateMcp(String credential);
    DelegatedActor revalidate(UUID delegationId);
    /** Current Venue role gate; does not expand delegation actions or tools. */
    DelegatedActor revalidateOwnerManager(UUID delegationId);
}
