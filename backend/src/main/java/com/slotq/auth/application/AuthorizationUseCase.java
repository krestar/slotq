package com.slotq.auth.application;

import com.slotq.auth.domain.ActorContext;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.booking.application.ReservationCommand;
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
        ReservationCommand command
    ) {
        if (principal.principalId().equals(target.customerPrincipalId())) {
            if (command != ReservationCommand.CONFIRM && command != ReservationCommand.CANCEL) {
                throw new AccessDeniedException();
            }
            return;
        }
        ActorContext actor = requireVenueAccess(principal, target.venueId());
        if (!actor.tenantId().equals(target.tenantId())) {
            throw new ResourceNotFoundException();
        }
        boolean allowed = switch (actor.role()) {
            case OWNER, MANAGER -> command != ReservationCommand.CONFIRM;
            case STAFF -> command == ReservationCommand.CHECK_IN
                || command == ReservationCommand.NO_SHOW
                || command == ReservationCommand.COMPLETE;
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
