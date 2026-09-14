package com.slotq.waitlist.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.slotq.auth.application.AccessDeniedException;
import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.ActorContext;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.auth.domain.TenantRole;
import com.slotq.booking.application.PromotionalReservationUseCase;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.venue.application.WaitlistVenueQuery;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.domain.WaitlistEntry;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistEntryState;
import com.slotq.waitlist.domain.WaitlistOffer;
import com.slotq.waitlist.domain.WaitlistOfferId;
import com.slotq.waitlist.domain.WaitlistOfferState;
import com.slotq.waitlist.domain.WaitlistOfferTerminalReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WaitlistOfferService implements WaitlistOfferUseCase {
    private final WaitlistOfferCommandExecutor executor;
    private final WaitlistEntryRepository entries;
    private final WaitlistOfferRepository offers;
    private final PromotionalReservationUseCase booking;
    private final WaitlistOfferProjection projection;
    private final WaitlistVenueQuery venues;
    private final AuthorizationUseCase authorization;
    private final Clock clock;

    WaitlistOfferService(
        WaitlistOfferCommandExecutor executor,
        WaitlistEntryRepository entries,
        WaitlistOfferRepository offers,
        PromotionalReservationUseCase booking,
        WaitlistOfferProjection projection,
        WaitlistVenueQuery venues,
        AuthorizationUseCase authorization,
        Clock clock
    ) {
        this.executor = executor;
        this.entries = entries;
        this.offers = offers;
        this.booking = booking;
        this.projection = projection;
        this.venues = venues;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public OfferView getOffer(
        VenueId venueId, WaitlistOfferId offerId, AuthenticatedPrincipal principal
    ) {
        Instant observedAt = clock.instant();
        WaitlistOffer offer = offers.findOwned(venueId, principal.principalId(), offerId)
            .orElseThrow(ResourceNotFoundException::new);
        return view(offer, booking.get(venueId, offer.reservationId(), observedAt), observedAt, true);
    }

    @Override
    @Transactional(readOnly = true)
    public OfferView getManagementOffer(
        VenueId venueId, WaitlistOfferId offerId, AuthenticatedPrincipal principal
    ) {
        ActorContext actor = authorization.requireVenueAccess(principal, venueId);
        if (actor.role() != TenantRole.OWNER && actor.role() != TenantRole.MANAGER) {
            throw new AccessDeniedException();
        }
        Instant observedAt = clock.instant();
        WaitlistOffer offer = offers.find(venueId, offerId)
            .filter(found -> found.tenantId().equals(actor.tenantId()))
            .orElseThrow(ResourceNotFoundException::new);
        return view(offer, booking.get(venueId, offer.reservationId(), observedAt), observedAt, false);
    }

    @Override
    public CommandResult accept(
        VenueId venueId, WaitlistOfferId offerId, AuthenticatedPrincipal principal
    ) {
        WaitlistOffer routed = requireActionOwner(venueId, offerId, principal);
        Instant commandNow = clock.instant();
        PromotionalReservationUseCase.ReservationView routedReservation =
            booking.get(venueId, routed.reservationId(), commandNow);
        WaitlistOfferProjection.Effective effective = projection.project(
            routed, routedReservation, commandNow
        );
        if (routed.state() != WaitlistOfferState.PENDING) {
            CommandOutcome terminal = effective.state() == WaitlistOfferState.ACCEPTED
                ? CommandOutcome.SUCCESS
                : effective.state() == WaitlistOfferState.EXPIRED
                    ? CommandOutcome.OFFER_EXPIRED : CommandOutcome.TRANSITION_NOT_ALLOWED;
            return new CommandResult(terminal,
                view(routed, routedReservation, clock.instant(), true));
        }
        if (effective.state() != WaitlistOfferState.PENDING) {
            WaitlistOfferCommandExecutor.TargetExecution reconciled = executor.reconcile(
                routed, commandNow
            );
            CommandOutcome outcome = effective.state() == WaitlistOfferState.EXPIRED
                ? CommandOutcome.OFFER_EXPIRED
                : effective.state() == WaitlistOfferState.ACCEPTED
                    ? CommandOutcome.SUCCESS : CommandOutcome.TRANSITION_NOT_ALLOWED;
            return new CommandResult(outcome,
                view(reconciled.offer(), reconciled.reservation(), clock.instant(), true));
        }
        WaitlistOfferCommandExecutor.ActionExecution result = executor.accept(routed, commandNow);
        return new CommandResult(result.outcome(),
            view(result.offer(), result.reservation(), clock.instant(), true));
    }

    @Override
    public CommandResult reject(
        VenueId venueId, WaitlistOfferId offerId, AuthenticatedPrincipal principal
    ) {
        WaitlistOffer routed = requireActionOwner(venueId, offerId, principal);
        Instant commandNow = clock.instant();
        WaitlistOfferCommandExecutor.ActionExecution result = executor.reject(
            routed, commandNow, WaitlistOfferTerminalReason.CUSTOMER_DECLINED
        );
        return new CommandResult(result.outcome(),
            view(result.offer(), result.reservation(), clock.instant(), true));
    }

    @Override
    public TargetResult createTarget(
        SystemPrincipal principal, VenueId venueId, WaitlistEntryId entryId,
        SlotInventoryId slotInventoryId
    ) {
        WaitlistEntry routed = entries.find(venueId, entryId)
            .orElseThrow(ResourceNotFoundException::new);
        Instant commandNow = clock.instant();
        WaitlistOfferCommandExecutor.TargetExecution result = executor.create(
            principal, routed, slotInventoryId, commandNow
        );
        return new TargetResult(result.outcome(), result.offer() == null ? null
            : view(result.offer(), result.reservation(), clock.instant(), false));
    }

    @Override
    public TargetResult reconcileTarget(
        SystemPrincipal principal, VenueId venueId, WaitlistOfferId offerId
    ) {
        if (principal == null) throw new NullPointerException("system principal must not be null");
        WaitlistOffer routed = offers.find(venueId, offerId).orElseThrow(ResourceNotFoundException::new);
        WaitlistOfferCommandExecutor.TargetExecution result = executor.reconcile(routed, clock.instant());
        return new TargetResult(result.outcome(),
            view(result.offer(), result.reservation(), clock.instant(), false));
    }

    EntryOffer entryOffer(WaitlistEntry entry, Instant observedAt) {
        WaitlistOffer offer = offers.findByEntry(entry.venueId(), entry.id()).orElse(null);
        if (offer == null) {
            if (entry.state() == WaitlistEntryState.OFFERED) {
                throw new IllegalStateException("OFFERED Entry has no Offer");
            }
            return null;
        }
        PromotionalReservationUseCase.ReservationView reservation = booking.get(
            offer.venueId(), offer.reservationId(), observedAt
        );
        WaitlistOfferProjection.Effective effective = projection.project(offer, reservation, observedAt);
        WaitlistEntryState entryState = switch (effective.state()) {
            case PENDING -> WaitlistEntryState.OFFERED;
            case ACCEPTED -> WaitlistEntryState.FULFILLED;
            case DECLINED -> WaitlistEntryState.DECLINED;
            case EXPIRED -> WaitlistEntryState.EXPIRED;
        };
        return new EntryOffer(
            offer.id(), entryState, effective.state(), offer.expiresAt(), reservation.id()
        );
    }

    WaitlistOfferCommandExecutor.ActionExecution cancelEntry(WaitlistOffer offer, Instant commandNow) {
        return executor.reject(offer, commandNow, WaitlistOfferTerminalReason.ENTRY_CANCELLED);
    }

    WaitlistOffer findOfferForEntry(WaitlistEntry entry) {
        return offers.findByEntry(entry.venueId(), entry.id())
            .orElseThrow(() -> new IllegalStateException("OFFERED Entry has no Offer"));
    }

    private OfferView view(
        WaitlistOffer offer, PromotionalReservationUseCase.ReservationView reservation,
        Instant observedAt, boolean customer
    ) {
        WaitlistOfferProjection.Effective effective = projection.project(offer, reservation, observedAt);
        List<String> actions = customer && effective.state() == WaitlistOfferState.PENDING
            ? List.of("ACCEPT", "REJECT") : List.of();
        ZoneId timezone = venues.find(offer.venueId())
            .orElseThrow(ResourceNotFoundException::new).timezone();
        return new OfferView(
            offer.id().value(), offer.entryId().value(), offer.venueId().value(),
            offer.resourceId().value(), offer.slotInventoryId().value(), effective.state(),
            effective.reason(), offer.expiresAt(), observedAt, timezone, actions, reservation
        );
    }

    private WaitlistOffer requireActionOwner(
        VenueId venueId, WaitlistOfferId offerId, AuthenticatedPrincipal principal
    ) {
        WaitlistOffer owned = offers.findOwned(venueId, principal.principalId(), offerId).orElse(null);
        if (owned != null) return owned;
        WaitlistOffer existing = offers.find(venueId, offerId).orElseThrow(ResourceNotFoundException::new);
        ActorContext actor = authorization.requireVenueAccess(principal, venueId);
        if (actor.tenantId().equals(existing.tenantId())
            && (actor.role() == TenantRole.OWNER || actor.role() == TenantRole.MANAGER)) {
            throw new AccessDeniedException();
        }
        throw new ResourceNotFoundException();
    }

    record EntryOffer(
        WaitlistOfferId offerId,
        WaitlistEntryState entryState,
        WaitlistOfferState offerState,
        Instant offerExpiresAt,
        com.slotq.booking.domain.ReservationId reservationId
    ) { }
}
