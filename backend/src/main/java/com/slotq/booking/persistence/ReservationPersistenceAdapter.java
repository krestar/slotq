package com.slotq.booking.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

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
import org.springframework.jdbc.core.JdbcTemplate;

@Component
class ReservationPersistenceAdapter implements ReservationRepository {

    private static final String CURRENT_SELECT = """
        SELECT reservation.id, reservation.tenant_id, reservation.venue_id,
               reservation.resource_id, reservation.slot_inventory_id,
               reservation.customer_principal_id, reservation.party_size,
               reservation.state, reservation.applied_policy_version,
               reservation.starts_at, reservation.expires_at,
               reservation.cancel_allowed_until, reservation.no_show_eligible_at,
               reservation.promotional_request_id, reservation.promotional_confirmed,
               allocation.id AS allocation_id, allocation.units AS allocation_units,
               allocation.active AS allocation_active
          FROM reservations reservation
          JOIN capacity_allocations allocation
            ON allocation.tenant_id = reservation.tenant_id
           AND allocation.venue_id = reservation.venue_id
           AND allocation.resource_id = reservation.resource_id
           AND allocation.slot_inventory_id = reservation.slot_inventory_id
           AND allocation.reservation_id = reservation.id
        """;

    private final ReservationSpringDataRepository reservationRepository;
    private final CapacityAllocationSpringDataRepository allocationRepository;
    private final JdbcTemplate jdbc;

    ReservationPersistenceAdapter(ReservationSpringDataRepository reservationRepository,
                                  CapacityAllocationSpringDataRepository allocationRepository,
                                  JdbcTemplate jdbc) {
        this.reservationRepository = reservationRepository;
        this.allocationRepository = allocationRepository;
        this.jdbc = jdbc;
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
    public Optional<Reservation> findForUpdate(VenueId venueId, ReservationId reservationId) {
        return queryCurrent(
            " WHERE reservation.venue_id = ? AND reservation.id = ? FOR UPDATE",
            JdbcWaitlistDemandQuery.bytes(venueId.value()),
            JdbcWaitlistDemandQuery.bytes(reservationId.value())
        );
    }

    @Override
    public Optional<Reservation> findCurrent(VenueId venueId, ReservationId reservationId) {
        return queryCurrent(
            " WHERE reservation.venue_id = ? AND reservation.id = ? FOR SHARE",
            JdbcWaitlistDemandQuery.bytes(venueId.value()),
            JdbcWaitlistDemandQuery.bytes(reservationId.value())
        );
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
            tenantId.value(), venueId.value(), resourceId.value(), slotInventoryId.value(), null, now
        ) > 0;
    }

    @Override
    public boolean existsOtherEffectiveCapacityConsumer(TenantId tenantId, VenueId venueId,
                                                         ResourceId resourceId,
                                                         SlotInventoryId slotInventoryId,
                                                         ReservationId excludedReservationId, Instant now) {
        return reservationRepository.countEffectiveCapacityConsumers(
            tenantId.value(), venueId.value(), resourceId.value(), slotInventoryId.value(),
            excludedReservationId.value(), now
        ) > 0;
    }

    private ReservationJpaEntity toEntity(Reservation reservation) {
        return new ReservationJpaEntity(
            reservation.id().value(), reservation.tenantId().value(), reservation.venueId().value(),
            reservation.resourceId().value(), reservation.slotInventoryId().value(),
            reservation.customerPrincipalId().value(), reservation.partySize().value(), reservation.state(),
            reservation.appliedPolicyVersion(), reservation.startsAt(), reservation.expiresAt(),
            reservation.cancelAllowedUntil(), reservation.noShowEligibleAt(),
            reservation.promotionalRequestId(), reservation.promotionalConfirmed()
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
            restoredAllocation, reservation.promotionalRequestId(), reservation.promotionalConfirmed()
        );
    }

    @Override
    public Optional<Reservation> findPromotionalCurrent(TenantId tenantId, UUID promotionalRequestId) {
        return queryCurrent(
            " WHERE reservation.tenant_id = ? AND reservation.promotional_request_id = ? FOR SHARE",
            JdbcWaitlistDemandQuery.bytes(tenantId.value()),
            JdbcWaitlistDemandQuery.bytes(promotionalRequestId)
        );
    }

    @Override
    public boolean existsEffectiveCapacityConsumerCurrent(
        TenantId tenantId, VenueId venueId, ResourceId resourceId,
        SlotInventoryId slotInventoryId, ReservationId excludedReservationId, Instant now
    ) {
        return !jdbc.queryForList("""
            SELECT reservation.id
              FROM reservations reservation
              JOIN capacity_allocations allocation
                ON allocation.tenant_id = reservation.tenant_id
               AND allocation.venue_id = reservation.venue_id
               AND allocation.resource_id = reservation.resource_id
               AND allocation.slot_inventory_id = reservation.slot_inventory_id
               AND allocation.reservation_id = reservation.id
             WHERE reservation.tenant_id = ? AND reservation.venue_id = ?
               AND reservation.resource_id = ? AND reservation.slot_inventory_id = ?
               AND allocation.active = TRUE
               AND (? IS NULL OR reservation.id <> ?)
               AND (reservation.state IN ('CONFIRMED', 'CHECKED_IN')
                    OR (reservation.state = 'HELD' AND reservation.expires_at > ?))
             LIMIT 1 FOR SHARE
            """, JdbcWaitlistDemandQuery.bytes(tenantId.value()),
            JdbcWaitlistDemandQuery.bytes(venueId.value()),
            JdbcWaitlistDemandQuery.bytes(resourceId.value()),
            JdbcWaitlistDemandQuery.bytes(slotInventoryId.value()),
            excludedReservationId == null ? null : JdbcWaitlistDemandQuery.bytes(excludedReservationId.value()),
            excludedReservationId == null ? null : JdbcWaitlistDemandQuery.bytes(excludedReservationId.value()),
            java.sql.Timestamp.from(now)).isEmpty();
    }

    private Optional<Reservation> queryCurrent(String where, Object... args) {
        return jdbc.query(CURRENT_SELECT + where, ReservationPersistenceAdapter::currentReservation, args)
            .stream().findFirst();
    }

    private static Reservation currentReservation(ResultSet row, int number) throws SQLException {
        UUID reservationId = JdbcWaitlistDemandQuery.uuid(row.getBytes("id"));
        TenantId tenantId = new TenantId(JdbcWaitlistDemandQuery.uuid(row.getBytes("tenant_id")));
        VenueId venueId = new VenueId(JdbcWaitlistDemandQuery.uuid(row.getBytes("venue_id")));
        ResourceId resourceId = new ResourceId(JdbcWaitlistDemandQuery.uuid(row.getBytes("resource_id")));
        SlotInventoryId slotId = new SlotInventoryId(
            JdbcWaitlistDemandQuery.uuid(row.getBytes("slot_inventory_id"))
        );
        CapacityAllocation allocation = CapacityAllocation.reconstitute(
            new CapacityAllocationId(JdbcWaitlistDemandQuery.uuid(row.getBytes("allocation_id"))),
            new ReservationId(reservationId), tenantId, venueId, resourceId, slotId,
            row.getInt("allocation_units"), row.getBoolean("allocation_active")
        );
        byte[] requestId = row.getBytes("promotional_request_id");
        return Reservation.reconstitute(
            new ReservationId(reservationId), tenantId, venueId, resourceId, slotId,
            new PrincipalId(JdbcWaitlistDemandQuery.uuid(row.getBytes("customer_principal_id"))),
            new PartySize(row.getInt("party_size")),
            com.slotq.booking.domain.ReservationState.valueOf(row.getString("state")),
            row.getLong("applied_policy_version"), instant(row, "starts_at"),
            instant(row, "expires_at"), instant(row, "cancel_allowed_until"),
            instant(row, "no_show_eligible_at"), allocation,
            requestId == null ? null : JdbcWaitlistDemandQuery.uuid(requestId),
            row.getBoolean("promotional_confirmed")
        );
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
