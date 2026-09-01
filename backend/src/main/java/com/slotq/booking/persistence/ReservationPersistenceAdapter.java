package com.slotq.booking.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.booking.application.ReservationRepository;
import com.slotq.booking.domain.CapacityAllocation;
import com.slotq.booking.domain.CapacityAllocationId;
import com.slotq.booking.domain.PartySize;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import org.springframework.stereotype.Component;

@Component
class ReservationPersistenceAdapter implements ReservationRepository {

    private final ReservationSpringDataRepository reservationRepository;
    private final CapacityAllocationSpringDataRepository allocationRepository;

    ReservationPersistenceAdapter(ReservationSpringDataRepository reservationRepository,
                                  CapacityAllocationSpringDataRepository allocationRepository) {
        this.reservationRepository = reservationRepository;
        this.allocationRepository = allocationRepository;
    }

    @Override
    public void save(Reservation reservation) {
        reservationRepository.save(toEntity(reservation));
        allocationRepository.saveAndFlush(toEntity(reservation.allocation()));
    }

    @Override
    public Optional<Reservation> find(VenueId venueId, ReservationId reservationId) {
        return reservationRepository.findByVenueIdAndId(venueId.value(), reservationId.value())
            .map(entity -> toDomain(entity, allocationRepository
                .findByTenantIdAndVenueIdAndReservationId(
                    entity.tenantId(), entity.venueId(), entity.id()
                ).orElseThrow(() -> new IllegalStateException("Reservation allocation is missing"))));
    }

    @Override
    public Optional<Reservation> findCurrent(VenueId venueId, ReservationId reservationId) {
        return reservationRepository.findCurrentByVenueIdAndId(venueId.value(), reservationId.value())
            .map(entity -> toDomain(entity, allocationRepository
                .findCurrentByTenantIdAndVenueIdAndReservationId(
                    entity.tenantId(), entity.venueId(), entity.id()
                ).orElseThrow(() -> new IllegalStateException("Reservation allocation is missing"))));
    }

    @Override
    public List<Reservation> findAll(TenantId tenantId, VenueId venueId, Instant startsAt, Instant endsAt) {
        return reservationRepository
            .findAllByTenantIdAndVenueIdAndStartsAtGreaterThanEqualAndStartsAtLessThanOrderByStartsAtAscIdAsc(
                tenantId.value(), venueId.value(), startsAt, endsAt
            ).stream().map(entity -> toDomain(entity, allocationRepository
                .findByTenantIdAndVenueIdAndReservationId(
                    entity.tenantId(), entity.venueId(), entity.id()
                ).orElseThrow(() -> new IllegalStateException("Reservation allocation is missing"))))
            .toList();
    }

    @Override
    public boolean existsEffectiveCapacityConsumer(TenantId tenantId, VenueId venueId,
                                                    ResourceId resourceId,
                                                    SlotInventoryId slotInventoryId, Instant now) {
        return reservationRepository.countEffectiveCapacityConsumers(
            tenantId.value(), venueId.value(), resourceId.value(), slotInventoryId.value(), now
        ) > 0;
    }

    private ReservationJpaEntity toEntity(Reservation reservation) {
        return new ReservationJpaEntity(
            reservation.id().value(), reservation.tenantId().value(), reservation.venueId().value(),
            reservation.resourceId().value(), reservation.slotInventoryId().value(),
            reservation.customerPrincipalId().value(), reservation.partySize().value(), reservation.state(),
            reservation.appliedPolicyVersion(), reservation.startsAt(), reservation.expiresAt(),
            reservation.cancelAllowedUntil(), reservation.noShowEligibleAt()
        );
    }

    private CapacityAllocationJpaEntity toEntity(CapacityAllocation allocation) {
        return new CapacityAllocationJpaEntity(
            allocation.id().value(), allocation.reservationId().value(), allocation.tenantId().value(),
            allocation.venueId().value(), allocation.resourceId().value(),
            allocation.slotInventoryId().value(), allocation.units(), allocation.active()
        );
    }

    private Reservation toDomain(ReservationJpaEntity reservation, CapacityAllocationJpaEntity allocation) {
        CapacityAllocation restoredAllocation = CapacityAllocation.reconstitute(
            new CapacityAllocationId(allocation.id()), new ReservationId(allocation.reservationId()),
            new TenantId(allocation.tenantId()), new VenueId(allocation.venueId()),
            new ResourceId(allocation.resourceId()), new SlotInventoryId(allocation.slotInventoryId()),
            allocation.units(), allocation.active()
        );
        return Reservation.reconstitute(
            new ReservationId(reservation.id()), new TenantId(reservation.tenantId()),
            new VenueId(reservation.venueId()), new ResourceId(reservation.resourceId()),
            new SlotInventoryId(reservation.slotInventoryId()),
            new PrincipalId(reservation.customerPrincipalId()), new PartySize(reservation.partySize()),
            reservation.state(), reservation.appliedPolicyVersion(), reservation.startsAt(),
            reservation.expiresAt(), reservation.cancelAllowedUntil(), reservation.noShowEligibleAt(),
            restoredAllocation
        );
    }
}
