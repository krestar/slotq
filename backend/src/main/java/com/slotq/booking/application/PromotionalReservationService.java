package com.slotq.booking.application;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;

import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.booking.domain.CapacityAllocationId;
import com.slotq.booking.domain.PartySize;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.ReservationState;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.tenancy.application.TenantRepository;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.tenancy.domain.TenantStatus;
import com.slotq.venue.application.ResourceRepository;
import com.slotq.venue.application.VenueRepository;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PromotionalReservationService implements PromotionalReservationUseCase {

    private final ReservationRepository reservations;
    private final SlotInventoryRepository slots;
    private final TenantRepository tenants;
    private final VenueRepository venues;
    private final ResourceRepository resources;

    PromotionalReservationService(
        ReservationRepository reservations,
        SlotInventoryRepository slots,
        TenantRepository tenants,
        VenueRepository venues,
        ResourceRepository resources
    ) {
        this.reservations = reservations;
        this.slots = slots;
        this.tenants = tenants;
        this.venues = venues;
        this.resources = resources;
    }

    @Override
    @Transactional
    public CreateResult createHold(CreateCommand command, CreateGuard guard) {
        Objects.requireNonNull(command.principal(), "system principal must not be null");
        SlotInventory slot = slots.findForUpdate(command.venueId(), command.slotInventoryId())
            .orElseThrow(ResourceNotFoundException::new);
        if (!slot.tenantId().equals(command.tenantId())
            || !slot.venueId().equals(command.venueId())
            || !slot.startsAt().equals(command.startsAt())
            || !slot.endsAt().equals(command.endsAt())) {
            return new CreateResult(CreateOutcome.NOT_ELIGIBLE, null);
        }
        GateDecision gate = guard.afterSlotLocked(slotView(slot));
        if (gate != GateDecision.PROCEED) {
            return new CreateResult(switch (gate) {
                case EXISTING -> CreateOutcome.SKIPPED_EXISTING;
                case NOT_WAITING -> CreateOutcome.NOT_WAITING;
                case NOT_ELIGIBLE -> CreateOutcome.NOT_ELIGIBLE;
                case PROCEED -> throw new IllegalStateException("unreachable");
            }, null);
        }

        Reservation existing = reservations.findPromotionalCurrent(
            slot.tenantId(), command.promotionalRequestId()
        ).orElse(null);
        if (existing != null) {
            if (!sameFingerprint(existing, command)) {
                return new CreateResult(CreateOutcome.IDENTITY_CONFLICT, view(existing, slot.endsAt()));
            }
            return new CreateResult(CreateOutcome.EXISTING, view(existing, slot.endsAt()));
        }

        Tenant tenant = tenants.findById(slot.tenantId()).orElseThrow(ResourceNotFoundException::new);
        Venue venue = venues.find(slot.tenantId(), slot.venueId())
            .orElseThrow(ResourceNotFoundException::new);
        Resource resource = resources.find(slot.tenantId(), slot.venueId(), slot.resourceId())
            .orElseThrow(ResourceNotFoundException::new);
        if (tenant.status() != TenantStatus.ACTIVE || venue.status() != VenueStatus.ACTIVE
            || resource.status() != ResourceStatus.ACTIVE
            || command.partySize() > resource.seatingCapacity()
            || !command.commandNow().isBefore(slot.startsAt())) {
            return new CreateResult(CreateOutcome.NOT_ELIGIBLE, null);
        }
        if (reservations.existsEffectiveCapacityConsumerCurrent(
            slot.tenantId(), slot.venueId(), slot.resourceId(), slot.id(), null, command.commandNow()
        )) {
            return new CreateResult(CreateOutcome.CAPACITY_UNAVAILABLE, null);
        }

        Clock commandClock = Clock.fixed(command.commandNow(), ZoneOffset.UTC);
        Reservation reservation = Reservation.promotionalHold(
            ReservationId.newId(), CapacityAllocationId.newId(), slot.tenantId(), slot.venueId(),
            resource, slot, command.customerPrincipalId(), new PartySize(command.partySize()),
            venue.currentPolicy().applyTo(slot.startsAt(), commandClock), commandClock,
            command.promotionalRequestId()
        );
        reservations.save(reservation);
        return new CreateResult(CreateOutcome.CREATED, view(reservation, slot.endsAt()));
    }

    @Override
    @Transactional
    public AcceptResult accept(AcceptCommand command, AcceptGuard guard) {
        SlotInventory slot = slots.findForUpdate(command.venueId(), command.slotInventoryId())
            .orElseThrow(ResourceNotFoundException::new);
        if (!guard.afterSlotLocked()) {
            return new AcceptResult(AcceptOutcome.TERMINAL, null);
        }
        Reservation reservation = reservations.findForUpdate(command.venueId(), command.reservationId())
            .orElseThrow(() -> new IllegalStateException("Promotional Reservation is missing"));
        requireIdentity(reservation, command.promotionalRequestId(), slot);
        if (reservation.promotionalConfirmed()) {
            return new AcceptResult(AcceptOutcome.ALREADY_ACCEPTED, view(reservation, slot.endsAt()));
        }
        Clock commandClock = Clock.fixed(command.commandNow(), ZoneOffset.UTC);
        if (reservation.state() == ReservationState.EXPIRED
            || (reservation.state() == ReservationState.HELD
                && !command.commandNow().isBefore(reservation.expiresAt()))) {
            if (reservation.state() == ReservationState.HELD) {
                reservation.expire(commandClock);
                reservations.save(reservation);
            }
            return new AcceptResult(AcceptOutcome.EXPIRED, view(reservation, slot.endsAt()));
        }
        if (reservation.state() == ReservationState.CANCELLED) {
            return new AcceptResult(AcceptOutcome.BACKING_CANCELLED, view(reservation, slot.endsAt()));
        }
        if (reservation.state() != ReservationState.HELD) {
            throw new IllegalStateException("Promotional Reservation has invalid confirm evidence");
        }
        if (reservations.existsEffectiveCapacityConsumerCurrent(
            reservation.tenantId(), reservation.venueId(), reservation.resourceId(),
            reservation.slotInventoryId(), reservation.id(), command.commandNow()
        )) {
            return new AcceptResult(AcceptOutcome.CAPACITY_UNAVAILABLE, view(reservation, slot.endsAt()));
        }
        reservation.confirm(commandClock);
        reservations.save(reservation);
        return new AcceptResult(AcceptOutcome.ACCEPTED, view(reservation, slot.endsAt()));
    }

    @Override
    @Transactional
    public ReleaseResult release(ReleaseCommand command) {
        Reservation reservation = reservations.findForUpdate(command.venueId(), command.reservationId())
            .orElseThrow(() -> new IllegalStateException("Promotional Reservation is missing"));
        if (!command.promotionalRequestId().equals(reservation.promotionalRequestId())) {
            throw new IllegalStateException("Promotional Reservation identity does not match");
        }
        SlotInventory slot = slots.find(command.venueId(), reservation.slotInventoryId())
            .orElseThrow(() -> new IllegalStateException("Promotional Reservation Slot is missing"));
        if (reservation.promotionalConfirmed()) {
            return new ReleaseResult(ReleaseOutcome.ALREADY_ACCEPTED, view(reservation, slot.endsAt()));
        }
        Clock commandClock = Clock.fixed(command.commandNow(), ZoneOffset.UTC);
        if (reservation.state() == ReservationState.EXPIRED
            || (reservation.state() == ReservationState.HELD
                && !command.commandNow().isBefore(reservation.expiresAt()))) {
            if (reservation.state() == ReservationState.HELD) {
                reservation.expire(commandClock);
                reservations.save(reservation);
            }
            return new ReleaseResult(ReleaseOutcome.EXPIRED, view(reservation, slot.endsAt()));
        }
        if (reservation.state() == ReservationState.CANCELLED) {
            return new ReleaseResult(ReleaseOutcome.ALREADY_CANCELLED, view(reservation, slot.endsAt()));
        }
        if (reservation.state() != ReservationState.HELD) {
            throw new IllegalStateException("Promotional Reservation has invalid confirm evidence");
        }
        if (command.expiryOnly()) {
            return new ReleaseResult(ReleaseOutcome.NOT_DUE, view(reservation, slot.endsAt()));
        }
        reservation.cancel(commandClock);
        reservations.save(reservation);
        return new ReleaseResult(ReleaseOutcome.RELEASED, view(reservation, slot.endsAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationView get(
        com.slotq.venue.domain.VenueId venueId, ReservationId reservationId,
        java.time.Instant observedAt
    ) {
        Reservation reservation = reservations.find(venueId, reservationId)
            .orElseThrow(() -> new IllegalStateException("Promotional Reservation is missing"));
        SlotInventory slot = slots.find(venueId, reservation.slotInventoryId())
            .orElseThrow(() -> new IllegalStateException("Promotional Reservation Slot is missing"));
        ReservationView stored = view(reservation, slot.endsAt());
        return new ReservationView(
            stored.id(), stored.tenantId(), stored.venueId(), stored.resourceId(),
            stored.slotInventoryId(), stored.customerPrincipalId(),
            reservation.effectiveState(Clock.fixed(observedAt, ZoneOffset.UTC)),
            stored.startsAt(), stored.endsAt(), stored.expiresAt(), stored.partySize(),
            stored.allocationQuantity(), stored.appliedPolicyVersion(), stored.allocationActive(),
            stored.promotionalConfirmed()
        );
    }

    private boolean sameFingerprint(Reservation reservation, CreateCommand command) {
        return reservation.venueId().equals(command.venueId())
            && reservation.slotInventoryId().equals(command.slotInventoryId())
            && reservation.customerPrincipalId().equals(command.customerPrincipalId())
            && reservation.partySize().value() == command.partySize();
    }

    private void requireIdentity(Reservation reservation, java.util.UUID requestId, SlotInventory slot) {
        if (!requestId.equals(reservation.promotionalRequestId())
            || !slot.id().equals(reservation.slotInventoryId())
            || !slot.tenantId().equals(reservation.tenantId())
            || !slot.venueId().equals(reservation.venueId())
            || !slot.resourceId().equals(reservation.resourceId())) {
            throw new IllegalStateException("Promotional Reservation scope does not match");
        }
    }

    private SlotView slotView(SlotInventory slot) {
        return new SlotView(
            slot.tenantId(), slot.venueId(), slot.resourceId(), slot.id(),
            slot.startsAt(), slot.endsAt()
        );
    }

    private ReservationView view(Reservation reservation, java.time.Instant endsAt) {
        return new ReservationView(
            reservation.id(), reservation.tenantId(), reservation.venueId(), reservation.resourceId(),
            reservation.slotInventoryId(), reservation.customerPrincipalId(), reservation.state(),
            reservation.startsAt(), endsAt, reservation.expiresAt(), reservation.partySize().value(),
            reservation.allocation().units(), reservation.appliedPolicyVersion(),
            reservation.allocation().active(), reservation.promotionalConfirmed()
        );
    }
}
