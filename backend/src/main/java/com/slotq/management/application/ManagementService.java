package com.slotq.management.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.application.ReservationAction;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.ActorContext;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.booking.application.ReservationCommand;
import com.slotq.booking.application.ReservationRepository;
import com.slotq.booking.application.ReservationTransitionPolicy;
import com.slotq.booking.application.SlotInventoryRepository;
import com.slotq.booking.application.SlotInventoryConflictException;
import com.slotq.booking.application.SlotInventoryNotAllowedException;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationState;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.venue.application.ResourceRepository;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicy;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueId;
import com.slotq.venue.domain.VenueStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ManagementService implements ManagementUseCase {

    private static final List<ReservationAction> MANAGEMENT_ACTIONS = List.of(
        ReservationAction.CANCEL,
        ReservationAction.CHECK_IN,
        ReservationAction.NO_SHOW,
        ReservationAction.COMPLETE
    );

    private final AuthorizationUseCase authorization;
    private final VenueConfigurationUseCase venueUseCase;
    private final ResourceUseCase resourceUseCase;
    private final SlotInventoryUseCase slotUseCase;
    private final ResourceRepository resourceRepository;
    private final SlotInventoryRepository slotRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationTransitionPolicy transitionPolicy;
    private final Clock clock;

    ManagementService(
        AuthorizationUseCase authorization,
        VenueConfigurationUseCase venueUseCase,
        ResourceUseCase resourceUseCase,
        SlotInventoryUseCase slotUseCase,
        ResourceRepository resourceRepository,
        SlotInventoryRepository slotRepository,
        ReservationRepository reservationRepository,
        ReservationTransitionPolicy transitionPolicy,
        Clock clock
    ) {
        this.authorization = authorization;
        this.venueUseCase = venueUseCase;
        this.resourceUseCase = resourceUseCase;
        this.slotUseCase = slotUseCase;
        this.resourceRepository = resourceRepository;
        this.slotRepository = slotRepository;
        this.reservationRepository = reservationRepository;
        this.transitionPolicy = transitionPolicy;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Venue> getVenues(AuthenticatedPrincipal principal) {
        return authorization.discoverVenueAccess(principal).stream()
            .flatMap(actor -> actor.venueGrants().stream()
                .map(venueId -> venueUseCase.getVenue(actor.tenantId(), venueId)))
            .sorted(Comparator.comparing((Venue venue) -> venue.tenantId().value())
                .thenComparing(venue -> venue.id().value()))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Venue getVenue(AuthenticatedPrincipal principal, VenueId venueId) {
        ActorContext actor = authorization.requireVenueAccess(principal, venueId);
        return venueUseCase.getVenue(actor.tenantId(), venueId);
    }

    @Override
    @Transactional
    public Venue patchVenue(AuthenticatedPrincipal principal, VenueId venueId, PatchVenue patch) {
        ActorContext actor = authorization.requireVenueConfigurationAccess(principal, venueId);
        Venue current = venueUseCase.getVenue(actor.tenantId(), venueId);
        validateVenuePatch(patch, current);
        String name = patch.name().supplied() ? patch.name().value().strip() : current.name();
        VenueStatus status = patch.status().supplied() ? patch.status().value() : current.status();
        return venueUseCase.updateVenue(new VenueConfigurationUseCase.UpdateVenue(
            actor.tenantId(), venueId, name, status, current.timezone().getId(), current.operatingHours()
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public BookingPolicy getPolicy(AuthenticatedPrincipal principal, VenueId venueId) {
        return getVenue(principal, venueId).currentPolicy();
    }

    @Override
    @Transactional
    public BookingPolicy updatePolicy(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        BookingPolicyTerms terms
    ) {
        ActorContext actor = authorization.requireVenueConfigurationAccess(principal, venueId);
        validatePolicyTerms(terms);
        return venueUseCase.updateBookingPolicy(new VenueConfigurationUseCase.UpdateBookingPolicy(
            actor.tenantId(), venueId, terms
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Resource> getResources(AuthenticatedPrincipal principal, VenueId venueId) {
        ActorContext actor = authorization.requireVenueAccess(principal, venueId);
        return resourceRepository.findAll(actor.tenantId(), venueId);
    }

    @Override
    @Transactional
    public Resource createResource(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        CreateResource command
    ) {
        ActorContext actor = authorization.requireVenueConfigurationAccess(principal, venueId);
        validateResource(command.name(), command.seatingCapacity());
        return resourceUseCase.createResource(new ResourceUseCase.CreateResource(
            actor.tenantId(), venueId, command.name(), command.seatingCapacity()
        ));
    }

    @Override
    @Transactional
    public Resource patchResource(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        ResourceId resourceId,
        PatchResource patch
    ) {
        ActorContext actor = authorization.requireVenueConfigurationAccess(principal, venueId);
        Resource current = resourceUseCase.getResource(actor.tenantId(), venueId, resourceId);
        validateResourcePatch(patch, current);
        String name = patch.name().supplied() ? patch.name().value().strip() : current.name();
        int capacity = patch.seatingCapacity().supplied()
            ? patch.seatingCapacity().value() : current.seatingCapacity();
        ResourceStatus status = patch.status().supplied() ? patch.status().value() : current.status();
        return resourceUseCase.updateResource(new ResourceUseCase.UpdateResource(
            actor.tenantId(), venueId, resourceId, name, capacity, status
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SlotInventory> getSlotInventories(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        LocalDate date
    ) {
        ActorContext actor = authorization.requireVenueAccess(principal, venueId);
        Venue venue = venueUseCase.getVenue(actor.tenantId(), venueId);
        DateWindow window = dateWindow(date, venue);
        return slotRepository.findAll(actor.tenantId(), venueId, window.startsAt(), window.endsAt());
    }

    @Override
    @Transactional
    public SlotInventory createSlotInventory(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        CreateSlotInventory command
    ) {
        ActorContext actor = authorization.requireVenueConfigurationAccess(principal, venueId);
        if (command == null || command.resourceId() == null || command.startsAt() == null
            || command.startsAt().isBlank()) {
            throw validation("startsAt", "startsAt and resourceId are required");
        }
        try {
            return slotUseCase.createSlot(new SlotInventoryUseCase.CreateSlot(
                actor.tenantId(), venueId, command.resourceId(), command.startsAt()
            ));
        } catch (SlotInventoryConflictException | SlotInventoryNotAllowedException exception) {
            throw exception;
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw validation("startsAt", "startsAt is invalid for the Venue schedule");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReservationItem> getReservations(
        AuthenticatedPrincipal principal,
        VenueId venueId,
        LocalDate date,
        ReservationState status
    ) {
        ActorContext actor = authorization.requireVenueAccess(principal, venueId);
        Venue venue = venueUseCase.getVenue(actor.tenantId(), venueId);
        DateWindow window = dateWindow(date, venue);
        Instant verificationNow = clock.instant();
        Clock verificationClock = Clock.fixed(verificationNow, ZoneOffset.UTC);
        return reservationRepository.findAll(
            actor.tenantId(), venueId, window.startsAt(), window.endsAt()
        ).stream().map(reservation -> item(actor, reservation, verificationNow, verificationClock))
            .filter(item -> status == null || item.effectiveState() == status)
            .toList();
    }

    private ReservationItem item(
        ActorContext actor,
        Reservation reservation,
        Instant verificationNow,
        Clock verificationClock
    ) {
        SlotInventory slot = slotRepository.find(reservation.venueId(), reservation.slotInventoryId())
            .filter(found -> found.tenantId().equals(reservation.tenantId())
                && found.resourceId().equals(reservation.resourceId()))
            .orElseThrow(ResourceNotFoundException::new);
        List<ReservationAction> actions = MANAGEMENT_ACTIONS.stream()
            .filter(action -> authorization.isOperatorReservationActionAllowed(actor, action))
            .filter(action -> transitionPolicy.isAllowed(
                reservation, ReservationCommand.valueOf(action.name()), verificationNow
            )).toList();
        return new ReservationItem(
            reservation,
            slot.endsAt(),
            reservation.effectiveState(verificationClock),
            actions
        );
    }

    private DateWindow dateWindow(LocalDate date, Venue venue) {
        if (date == null) {
            throw validation("date", "date is required");
        }
        return new DateWindow(
            date.atStartOfDay(venue.timezone()).toInstant(),
            date.plusDays(1).atStartOfDay(venue.timezone()).toInstant()
        );
    }

    private void validateVenuePatch(PatchVenue patch, Venue current) {
        if (patch == null || patch.name() == null || patch.status() == null
            || (!patch.name().supplied() && !patch.status().supplied())) {
            throw validation("body", "at least one supported field is required");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        validateName(patch.name(), errors);
        if (patch.status().supplied() && patch.status().value() == null) {
            errors.put("status", "must not be null");
        }
        failIfInvalid(errors);
        String name = patch.name().supplied() ? patch.name().value().strip() : current.name();
        VenueStatus status = patch.status().supplied() ? patch.status().value() : current.status();
        if (name.equals(current.name()) && status == current.status()) {
            throw validation("body", "patch must change at least one field");
        }
    }

    private void validateResourcePatch(PatchResource patch, Resource current) {
        if (patch == null || patch.name() == null || patch.seatingCapacity() == null
            || patch.status() == null || (!patch.name().supplied()
                && !patch.seatingCapacity().supplied() && !patch.status().supplied())) {
            throw validation("body", "at least one supported field is required");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        validateName(patch.name(), errors);
        if (patch.seatingCapacity().supplied()
            && (patch.seatingCapacity().value() == null || patch.seatingCapacity().value() < 1)) {
            errors.put("seatingCapacity", "must be positive");
        }
        if (patch.status().supplied() && patch.status().value() == null) {
            errors.put("status", "must not be null");
        }
        failIfInvalid(errors);
        String name = patch.name().supplied() ? patch.name().value().strip() : current.name();
        int capacity = patch.seatingCapacity().supplied()
            ? patch.seatingCapacity().value() : current.seatingCapacity();
        ResourceStatus status = patch.status().supplied() ? patch.status().value() : current.status();
        if (name.equals(current.name()) && capacity == current.seatingCapacity()
            && status == current.status()) {
            throw validation("body", "patch must change at least one field");
        }
    }

    private void validateResource(String name, int seatingCapacity) {
        Map<String, String> errors = new LinkedHashMap<>();
        validateName(new PatchField<>(true, name), errors);
        if (seatingCapacity < 1) {
            errors.put("seatingCapacity", "must be positive");
        }
        failIfInvalid(errors);
    }

    private void validateName(PatchField<String> field, Map<String, String> errors) {
        if (!field.supplied()) {
            return;
        }
        if (field.value() == null || field.value().isBlank()) {
            errors.put("name", "must not be blank");
        } else if (field.value().strip().length() > 100) {
            errors.put("name", "must not exceed 100 characters");
        }
    }

    private void validatePolicyTerms(BookingPolicyTerms terms) {
        if (terms == null) {
            throw validation("body", "policy terms are required");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (terms.slotDurationMinutes() < 1) errors.put("slotDurationMinutes", "must be positive");
        if (terms.holdDurationMinutes() < 1) errors.put("holdDurationMinutes", "must be positive");
        if (terms.cancellationCutoffMinutes() < 0) {
            errors.put("cancellationCutoffMinutes", "must not be negative");
        }
        if (terms.noShowGraceMinutes() < 0) {
            errors.put("noShowGraceMinutes", "must not be negative");
        }
        failIfInvalid(errors);
    }

    private void failIfInvalid(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new ManagementValidationException(errors);
        }
    }

    private ManagementValidationException validation(String field, String detail) {
        return new ManagementValidationException(Map.of(field, detail));
    }

    private record DateWindow(Instant startsAt, Instant endsAt) { }
}
