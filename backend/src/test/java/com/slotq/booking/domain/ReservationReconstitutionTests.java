package com.slotq.booking.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationReconstitutionTests {

    private static final Instant EXPIRES_AT = Instant.parse("2026-08-30T10:30:00Z");
    private static final Instant CANCEL_ALLOWED_UNTIL = Instant.parse("2026-08-30T10:45:00Z");
    private static final Instant STARTS_AT = Instant.parse("2026-08-30T11:00:00Z");
    private static final Instant NO_SHOW_ELIGIBLE_AT = Instant.parse("2026-08-30T11:15:00Z");

    @ParameterizedTest(name = "restores {0} with allocation active={1}")
    @MethodSource("storedStates")
    void restoresEveryStoredStateWithoutLifecycleReplay(ReservationState state, boolean active) {
        Snapshot snapshot = snapshot();

        Reservation reservation = snapshot.reconstitute(state, active);

        assertThat(reservation.id()).isEqualTo(snapshot.reservationId());
        assertThat(reservation.tenantId()).isEqualTo(snapshot.tenantId());
        assertThat(reservation.venueId()).isEqualTo(snapshot.venueId());
        assertThat(reservation.resourceId()).isEqualTo(snapshot.resourceId());
        assertThat(reservation.slotInventoryId()).isEqualTo(snapshot.slotInventoryId());
        assertThat(reservation.customerPrincipalId()).isEqualTo(snapshot.customerPrincipalId());
        assertThat(reservation.partySize()).isEqualTo(new PartySize(4));
        assertThat(reservation.state()).isEqualTo(state);
        assertThat(reservation.appliedPolicyVersion()).isEqualTo(3);
        assertThat(reservation.startsAt()).isEqualTo(STARTS_AT);
        assertThat(reservation.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(reservation.cancelAllowedUntil()).isEqualTo(CANCEL_ALLOWED_UNTIL);
        assertThat(reservation.noShowEligibleAt()).isEqualTo(NO_SHOW_ELIGIBLE_AT);
        assertThat(reservation.allocation().id()).isEqualTo(snapshot.allocationId());
        assertThat(reservation.allocation().reservationId()).isEqualTo(snapshot.reservationId());
        assertThat(reservation.allocation().tenantId()).isEqualTo(snapshot.tenantId());
        assertThat(reservation.allocation().venueId()).isEqualTo(snapshot.venueId());
        assertThat(reservation.allocation().resourceId()).isEqualTo(snapshot.resourceId());
        assertThat(reservation.allocation().slotInventoryId()).isEqualTo(snapshot.slotInventoryId());
        assertThat(reservation.allocation().units()).isEqualTo(CapacityAllocation.UNIT);
        assertThat(reservation.allocation().active()).isEqualTo(active);
    }

    @Test
    void restoresDueHeldSnapshotWithoutMaterializingExpiryOrReleasingAllocation() {
        Snapshot snapshot = snapshot();
        Clock afterExpiry = Clock.fixed(EXPIRES_AT.plusSeconds(1), ZoneOffset.UTC);

        Reservation reservation = snapshot.reconstitute(ReservationState.HELD, true);

        assertThat(reservation.state()).isEqualTo(ReservationState.HELD);
        assertThat(reservation.allocation().active()).isTrue();
        assertThat(reservation.effectiveState(afterExpiry)).isEqualTo(ReservationState.EXPIRED);
        assertThat(reservation.state()).isEqualTo(ReservationState.HELD);
        assertThat(reservation.allocation().active()).isTrue();
    }

    @Test
    void rejectsAllocationWithInvalidUnit() {
        Snapshot snapshot = snapshot();

        assertThatThrownBy(() -> snapshot.allocation(true, 2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("units");
    }

    @ParameterizedTest(name = "rejects mismatched {0}")
    @ValueSource(strings = {"reservationId", "tenantId", "venueId", "resourceId", "slotInventoryId"})
    void rejectsMismatchedAllocationIdentity(String field) {
        Snapshot snapshot = snapshot();
        CapacityAllocation allocation = switch (field) {
            case "reservationId" -> CapacityAllocation.reconstitute(
                snapshot.allocationId(), ReservationId.newId(), snapshot.tenantId(), snapshot.venueId(),
                snapshot.resourceId(), snapshot.slotInventoryId(), 1, true
            );
            case "tenantId" -> CapacityAllocation.reconstitute(
                snapshot.allocationId(), snapshot.reservationId(), TenantId.newId(), snapshot.venueId(),
                snapshot.resourceId(), snapshot.slotInventoryId(), 1, true
            );
            case "venueId" -> CapacityAllocation.reconstitute(
                snapshot.allocationId(), snapshot.reservationId(), snapshot.tenantId(), VenueId.newId(),
                snapshot.resourceId(), snapshot.slotInventoryId(), 1, true
            );
            case "resourceId" -> CapacityAllocation.reconstitute(
                snapshot.allocationId(), snapshot.reservationId(), snapshot.tenantId(), snapshot.venueId(),
                ResourceId.newId(), snapshot.slotInventoryId(), 1, true
            );
            case "slotInventoryId" -> CapacityAllocation.reconstitute(
                snapshot.allocationId(), snapshot.reservationId(), snapshot.tenantId(), snapshot.venueId(),
                snapshot.resourceId(), SlotInventoryId.newId(), 1, true
            );
            default -> throw new IllegalArgumentException("Unknown field: " + field);
        };

        assertThatThrownBy(() -> snapshot.reconstitute(ReservationState.HELD, allocation))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("identities");
    }

    @ParameterizedTest(name = "rejects {0} with allocation active={1}")
    @MethodSource("invalidStateAllocationPairs")
    void rejectsInvalidStateAllocationCombination(ReservationState state, boolean active) {
        Snapshot snapshot = snapshot();

        assertThatThrownBy(() -> snapshot.reconstitute(state, active))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("status");
    }

    @Test
    void rejectsInvalidPolicyVersionAndDeadlineOrder() {
        Snapshot snapshot = snapshot();
        CapacityAllocation allocation = snapshot.allocation(true, CapacityAllocation.UNIT);

        assertThatThrownBy(() -> snapshot.reconstitute(
            ReservationState.HELD, 0, STARTS_AT, EXPIRES_AT, CANCEL_ALLOWED_UNTIL,
            NO_SHOW_ELIGIBLE_AT, allocation
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> snapshot.reconstitute(
            ReservationState.HELD, 3, STARTS_AT, STARTS_AT.plusNanos(1), CANCEL_ALLOWED_UNTIL,
            NO_SHOW_ELIGIBLE_AT, allocation
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expiresAt");
        assertThatThrownBy(() -> snapshot.reconstitute(
            ReservationState.HELD, 3, STARTS_AT, EXPIRES_AT, STARTS_AT.plusNanos(1),
            NO_SHOW_ELIGIBLE_AT, allocation
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cancelAllowedUntil");
        assertThatThrownBy(() -> snapshot.reconstitute(
            ReservationState.HELD, 3, STARTS_AT, EXPIRES_AT, CANCEL_ALLOWED_UNTIL,
            STARTS_AT.minusNanos(1), allocation
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("noShowEligibleAt");
    }

    private static Stream<Arguments> storedStates() {
        return Stream.of(
            Arguments.of(ReservationState.HELD, true),
            Arguments.of(ReservationState.CONFIRMED, true),
            Arguments.of(ReservationState.CHECKED_IN, true),
            Arguments.of(ReservationState.EXPIRED, false),
            Arguments.of(ReservationState.CANCELLED, false),
            Arguments.of(ReservationState.NO_SHOW, false),
            Arguments.of(ReservationState.COMPLETED, false)
        );
    }

    private static Stream<Arguments> invalidStateAllocationPairs() {
        return storedStates().map(arguments -> Arguments.of(
            arguments.get()[0],
            !(boolean) arguments.get()[1]
        ));
    }

    private static Snapshot snapshot() {
        return new Snapshot(
            ReservationId.newId(), CapacityAllocationId.newId(), TenantId.newId(), VenueId.newId(),
            ResourceId.newId(), SlotInventoryId.newId(), PrincipalId.newId()
        );
    }

    private record Snapshot(
        ReservationId reservationId,
        CapacityAllocationId allocationId,
        TenantId tenantId,
        VenueId venueId,
        ResourceId resourceId,
        SlotInventoryId slotInventoryId,
        PrincipalId customerPrincipalId
    ) {
        Reservation reconstitute(ReservationState state, boolean active) {
            return reconstitute(state, allocation(active, CapacityAllocation.UNIT));
        }

        Reservation reconstitute(ReservationState state, CapacityAllocation allocation) {
            return reconstitute(
                state, 3, STARTS_AT, EXPIRES_AT, CANCEL_ALLOWED_UNTIL, NO_SHOW_ELIGIBLE_AT, allocation
            );
        }

        Reservation reconstitute(
            ReservationState state,
            long appliedPolicyVersion,
            Instant startsAt,
            Instant expiresAt,
            Instant cancelAllowedUntil,
            Instant noShowEligibleAt,
            CapacityAllocation allocation
        ) {
            return Reservation.reconstitute(
                reservationId, tenantId, venueId, resourceId, slotInventoryId, customerPrincipalId,
                new PartySize(4), state, appliedPolicyVersion, startsAt, expiresAt,
                cancelAllowedUntil, noShowEligibleAt, allocation
            );
        }

        CapacityAllocation allocation(boolean active, int units) {
            return CapacityAllocation.reconstitute(
                allocationId, reservationId, tenantId, venueId, resourceId, slotInventoryId, units, active
            );
        }
    }
}
