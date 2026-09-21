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
import com.slotq.tenancy.domain.TenantStatus;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.VenueStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PromotionalReservationService implements PromotionalReservationUseCase {

    private final ReservationRepository reservations;
    private final SlotInventoryRepository slots;
    private final PromotionalReservationContextQuery contexts;
    private final ReservationTransitionRecorder recorder;

    PromotionalReservationService(
        ReservationRepository reservations,
        SlotInventoryRepository slots,
        PromotionalReservationContextQuery contexts,
        ReservationTransitionRecorder recorder
    ) {
        this.reservations = reservations;
        this.slots = slots;
        this.contexts = contexts;
        this.recorder = recorder;
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

        PromotionalReservationContextQuery.Context context = contexts.findCurrent(
            slot.tenantId(), slot.venueId(), slot.resourceId(), slot.id()
        ).orElseThrow(() -> new IllegalStateException("Promotional Reservation context is missing"));
        Resource resource = context.resource();
        if (context.tenantStatus() != TenantStatus.ACTIVE || context.venueStatus() != VenueStatus.ACTIVE
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
            context.currentPolicy().applyTo(slot.startsAt(), commandClock), commandClock,
            command.promotionalRequestId()
        );
        reservations.save(reservation);
        return new CreateResult(CreateOutcome.CREATED, view(reservation, slot.endsAt()));
    }

    @Override
    @Transactional
    public PromotionResult createHold(PromotionCommand command, CandidateSelector selector) {
        Objects.requireNonNull(command.principal(), "system principal must not be null");
        SlotInventory slot = slots.findForUpdate(command.venueId(), command.slotInventoryId())
            .orElseThrow(() -> new PromotionReferenceException(PromotionReferenceException.Reason.MISSING));
        if (!slot.tenantId().equals(command.tenantId())) {
            throw new PromotionReferenceException(PromotionReferenceException.Reason.TENANT_MISMATCH);
        }
        if (!slot.resourceId().equals(command.resourceId())) {
            throw new PromotionReferenceException(PromotionReferenceException.Reason.SCOPE_MISMATCH);
        }
        if (command.releasedReservationId() != null) {
            // Immutable ownership only. Do not lock the historical release Reservation or require
            // its mutable state to equal the old event. No effect will write this Reservation.
            Reservation source = reservations.find(command.venueId(), command.releasedReservationId())
                .orElseThrow(() -> new PromotionReferenceException(PromotionReferenceException.Reason.MISSING));
            if (!source.tenantId().equals(slot.tenantId())) {
                throw new PromotionReferenceException(PromotionReferenceException.Reason.TENANT_MISMATCH);
            }
            if (!source.resourceId().equals(slot.resourceId()) || !source.slotInventoryId().equals(slot.id())) {
                throw new PromotionReferenceException(PromotionReferenceException.Reason.SCOPE_MISMATCH);
            }
        }
        if (!command.commandNow().isBefore(slot.startsAt())) {
            return new PromotionResult(PromotionOutcome.SLOT_PAST, null);
        }
        var context = contexts.findCurrent(slot.tenantId(), slot.venueId(), slot.resourceId(), slot.id())
            .orElseThrow(() -> new PromotionReferenceException(PromotionReferenceException.Reason.MISSING));
        if (context.tenantStatus() != TenantStatus.ACTIVE || context.venueStatus() != VenueStatus.ACTIVE
            || context.resource().status() != ResourceStatus.ACTIVE) {
            return new PromotionResult(PromotionOutcome.NOT_ELIGIBLE, null);
        }
        Selection selection = selector.afterSlotLocked(new PromotionContext(slotView(slot), context.resource().seatingCapacity()));
        if (selection.outcome() != SelectionOutcome.SELECTED) {
            return new PromotionResult(selection.outcome() == SelectionOutcome.DEFERRED
                ? PromotionOutcome.DEFERRED : PromotionOutcome.NO_CANDIDATE, null);
        }
        Candidate candidate = Objects.requireNonNull(selection.candidate());
        if (!slot.startsAt().equals(candidate.startsAt()) || !slot.endsAt().equals(candidate.endsAt())
            || candidate.partySize() < 1 || candidate.partySize() > context.resource().seatingCapacity()) {
            return new PromotionResult(PromotionOutcome.NOT_ELIGIBLE, null);
        }
        // The selector holds the WAITING Entry. Existing promotional identity would be corruption;
        // the unique insert enforces it without an absent-identity gap lock shared by other Slots.
        if (reservations.existsEffectiveCapacityConsumerCurrent(
            slot.tenantId(), slot.venueId(), slot.resourceId(), slot.id(), null, command.commandNow()
        )) {
            return new PromotionResult(PromotionOutcome.NO_CAPACITY, null);
        }
        Clock commandClock = Clock.fixed(command.commandNow(), ZoneOffset.UTC);
        Reservation reservation = Reservation.promotionalHold(
            ReservationId.newId(), CapacityAllocationId.newId(), slot.tenantId(), slot.venueId(),
            context.resource(), slot, candidate.customerPrincipalId(), new PartySize(candidate.partySize()),
            context.currentPolicy().applyTo(slot.startsAt(), commandClock), commandClock, candidate.promotionalRequestId()
        );
        reservations.save(reservation);
        return new PromotionResult(PromotionOutcome.CREATED, view(reservation, slot.endsAt()));
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
        var before = ReservationTransitionRecorder.Before.capture(reservation);
        if (reservation.promotionalConfirmed()) {
            return new AcceptResult(AcceptOutcome.ALREADY_ACCEPTED, view(reservation, slot.endsAt()));
        }
        Clock commandClock = Clock.fixed(command.commandNow(), ZoneOffset.UTC);
        if (reservation.state() == ReservationState.EXPIRED
            || (reservation.state() == ReservationState.HELD
                && !command.commandNow().isBefore(reservation.expiresAt()))) {
            if (reservation.state() == ReservationState.HELD) {
                reservation.expire(commandClock);
                recorder.save(reservation, before, command.commandNow());
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
        recorder.save(reservation, before, command.commandNow());
        return new AcceptResult(AcceptOutcome.ACCEPTED, view(reservation, slot.endsAt()));
    }

    @Override
    @Transactional
    public ReleaseResult release(ReleaseCommand command) {
        Reservation reservation = reservations.findForUpdate(command.venueId(), command.reservationId())
            .orElseThrow(() -> new IllegalStateException("Promotional Reservation is missing"));
        var before = ReservationTransitionRecorder.Before.capture(reservation);
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
                recorder.save(reservation, before, command.commandNow());
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
        recorder.save(reservation, before, command.commandNow());
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
