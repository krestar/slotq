package com.slotq.auth.application;

import com.slotq.auth.domain.ActorContext;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.venue.domain.VenueId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthorizationUseCase {

    private final AccessControlRepository repository;

    public AuthorizationUseCase(AccessControlRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ActorContext requireVenueAccess(AuthenticatedPrincipal principal, VenueId targetVenueId) {
        return repository.findActorForVenue(principal.principalId(), targetVenueId)
            .filter(actor -> actor.canAccess(targetVenueId))
            .orElseThrow(ResourceNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public ActorContext requireVenueConfigurationAccess(
        AuthenticatedPrincipal principal,
        VenueId targetVenueId
    ) {
        ActorContext actor = requireVenueAccess(principal, targetVenueId);
        if (!actor.canManageConfiguration()) {
            throw new AccessDeniedException();
        }
        return actor;
    }

    @Transactional(readOnly = true)
    public ReservationAccess authorizeReservationRead(
        AuthenticatedPrincipal principal,
        ReservationAccessTarget target
    ) {
        if (principal.principalId().equals(target.customerPrincipalId())) {
            return ReservationAccess.customer(principal.principalId());
        }
        ActorContext actor = requireVenueAccess(principal, target.venueId());
        if (!actor.tenantId().equals(target.tenantId())) {
            throw new ResourceNotFoundException();
        }
        return ReservationAccess.operator(actor);
    }

    @Transactional(readOnly = true)
    public void authorizeReservationCommand(
        AuthenticatedPrincipal principal,
        ReservationAccessTarget target,
        ReservationAction action
    ) {
        if (principal.principalId().equals(target.customerPrincipalId())) {
            if (action != ReservationAction.CONFIRM && action != ReservationAction.CANCEL) {
                throw new AccessDeniedException();
            }
            return;
        }
        ActorContext actor = requireVenueAccess(principal, target.venueId());
        if (!actor.tenantId().equals(target.tenantId())) {
            throw new ResourceNotFoundException();
        }
        boolean allowed = switch (actor.role()) {
            case OWNER, MANAGER -> action != ReservationAction.CONFIRM;
            case STAFF -> action == ReservationAction.CHECK_IN
                || action == ReservationAction.NO_SHOW
                || action == ReservationAction.COMPLETE;
        };
        if (!allowed) {
            throw new AccessDeniedException();
        }
    }

    public record ReservationAccess(PrincipalId customerPrincipalId, ActorContext operator) {
        public ReservationAccess {
            if ((customerPrincipalId == null) == (operator == null)) {
                throw new IllegalArgumentException("Exactly one reservation actor must be present");
            }
        }

        static ReservationAccess customer(PrincipalId principalId) {
            return new ReservationAccess(principalId, null);
        }

        static ReservationAccess operator(ActorContext actor) {
            return new ReservationAccess(null, actor);
        }

        public boolean isCustomer() {
            return customerPrincipalId != null;
        }
    }
}
