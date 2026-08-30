package com.slotq.booking.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

import com.slotq.auth.application.AuthorizationUseCase;
import com.slotq.auth.application.ReservationAccessTarget;
import com.slotq.auth.application.ResourceNotFoundException;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.booking.domain.CapacityAllocationId;
import com.slotq.booking.domain.PartySize;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.tenancy.application.TenantRepository;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.tenancy.domain.TenantStatus;
import com.slotq.venue.application.ResourceRepository;
import com.slotq.venue.application.VenueRepository;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueId;
import com.slotq.venue.domain.VenueStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ReservationService implements ReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final SlotInventoryRepository slotRepository;
    private final TenantRepository tenantRepository;
    private final VenueRepository venueRepository;
    private final ResourceRepository resourceRepository;
    private final AuthorizationUseCase authorizationUseCase;
    private final Clock clock;

    ReservationService(ReservationRepository reservationRepository,
                       SlotInventoryRepository slotRepository,
                       TenantRepository tenantRepository,
                       VenueRepository venueRepository,
                       ResourceRepository resourceRepository,
                       AuthorizationUseCase authorizationUseCase,
                       Clock clock) {
        this.reservationRepository = reservationRepository;
        this.slotRepository = slotRepository;
        this.tenantRepository = tenantRepository;
        this.venueRepository = venueRepository;
        this.resourceRepository = resourceRepository;
        this.authorizationUseCase = authorizationUseCase;
        this.clock = clock;
    }

    @Override
    public ReservationDetails createHold(CreateHold command) {
        Objects.requireNonNull(command, "command must not be null");
        Instant now = clock.instant();
        Clock commandClock = Clock.fixed(now, ZoneOffset.UTC);
        SlotInventory slot = slotRepository.find(command.venueId(), command.slotInventoryId())
            .orElseThrow(ResourceNotFoundException::new);
        Tenant tenant = tenantRepository.findById(slot.tenantId())
            .orElseThrow(ResourceNotFoundException::new);
        Venue venue = venueRepository.find(slot.tenantId(), slot.venueId())
            .orElseThrow(ResourceNotFoundException::new);
        Resource resource = resourceRepository.find(slot.tenantId(), slot.venueId(), slot.resourceId())
            .orElseThrow(ResourceNotFoundException::new);

        validateBookingAllowed(tenant, venue, resource, slot, now);
        PartySize partySize = new PartySize(command.partySize());
        if (partySize.value() > resource.seatingCapacity()) {
            throw new PartySizeNotSupportedException();
        }
        if (reservationRepository.existsEffectiveCapacityConsumer(
            slot.tenantId(), slot.venueId(), slot.resourceId(), slot.id(), now
        )) {
            throw new CapacityUnavailableException();
        }

        Reservation reservation = Reservation.hold(
            ReservationId.newId(), CapacityAllocationId.newId(), slot.tenantId(), slot.venueId(),
            resource, slot, command.customerPrincipalId(), partySize,
            venue.currentPolicy().applyTo(slot.startsAt(), commandClock), commandClock
        );
        reservationRepository.save(reservation);
        return new ReservationDetails(reservation, slot.endsAt(), reservation.effectiveState(commandClock));
    }

    @Override
    @Transactional(readOnly = true)
    public ReservationDetails getReservation(VenueId venueId, ReservationId reservationId,
                                             AuthenticatedPrincipal principal) {
        Reservation reservation = reservationRepository.find(venueId, reservationId)
            .orElseThrow(ResourceNotFoundException::new);
        authorizationUseCase.authorizeReservationRead(
            principal,
            new ReservationAccessTarget(
                reservation.tenantId(), reservation.venueId(), reservation.customerPrincipalId()
            )
        );
        SlotInventory slot = slotRepository.find(venueId, reservation.slotInventoryId())
            .filter(found -> found.tenantId().equals(reservation.tenantId())
                && found.resourceId().equals(reservation.resourceId()))
            .orElseThrow(ResourceNotFoundException::new);
        Clock readClock = Clock.fixed(clock.instant(), ZoneOffset.UTC);
        return new ReservationDetails(reservation, slot.endsAt(), reservation.effectiveState(readClock));
    }

    private void validateBookingAllowed(Tenant tenant, Venue venue, Resource resource,
                                        SlotInventory slot, Instant now) {
        if (tenant.status() != TenantStatus.ACTIVE
            || venue.status() != VenueStatus.ACTIVE
            || resource.status() != ResourceStatus.ACTIVE
            || !now.isBefore(slot.startsAt())) {
            throw new BookingNotAllowedException();
        }
    }
}
