package com.slotq.booking.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.PolicyDeadlines;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.ResourceStatus;
import com.slotq.venue.domain.ResourceType;
import com.slotq.venue.domain.VenueId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTests {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-08-30T10:30:00Z");
    private static final Instant CANCEL_ALLOWED_UNTIL = Instant.parse("2026-08-30T10:45:00Z");
    private static final Instant STARTS_AT = Instant.parse("2026-08-30T11:00:00Z");
    private static final Instant NO_SHOW_ELIGIBLE_AT = Instant.parse("2026-08-30T11:15:00Z");

    @Test
    void holdFactoryCreatesReservationAndOneActiveAllocationWithoutRequestedState() {
        Fixture fixture = fixture();
        Reservation reservation = fixture.reservation();

        assertThat(ReservationState.values())
            .extracting(Enum::name)
            .doesNotContain("REQUESTED");
        assertThat(reservation.state()).isEqualTo(ReservationState.HELD);
        assertThat(reservation.id()).isEqualTo(fixture.reservationId());
        assertThat(reservation.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(reservation.venueId()).isEqualTo(fixture.venueId());
        assertThat(reservation.resourceId()).isEqualTo(fixture.resource().id());
        assertThat(reservation.slotInventoryId()).isEqualTo(fixture.slot().id());
        assertThat(reservation.customerPrincipalId()).isEqualTo(fixture.customerId());
        assertThat(reservation.partySize()).isEqualTo(new PartySize(4));
        assertThat(reservation.appliedPolicyVersion()).isEqualTo(3);
        assertThat(reservation.startsAt()).isEqualTo(STARTS_AT);
        assertThat(reservation.expiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(reservation.cancelAllowedUntil()).isEqualTo(CANCEL_ALLOWED_UNTIL);
        assertThat(reservation.noShowEligibleAt()).isEqualTo(NO_SHOW_ELIGIBLE_AT);

        CapacityAllocation allocation = reservation.allocation();
        assertThat(allocation.id()).isEqualTo(fixture.allocationId());
        assertThat(allocation.reservationId()).isEqualTo(reservation.id());
        assertThat(allocation.tenantId()).isEqualTo(reservation.tenantId());
        assertThat(allocation.venueId()).isEqualTo(reservation.venueId());
        assertThat(allocation.resourceId()).isEqualTo(reservation.resourceId());
        assertThat(allocation.slotInventoryId()).isEqualTo(reservation.slotInventoryId());
        assertThat(allocation.units()).isEqualTo(1);
        assertThat(allocation.active()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedTransitions")
    void appliesEveryAllowedTransitionAndItsAllocationRule(TransitionCase transition) {
        Reservation reservation = fixture().reservation();
        transition.prepare().accept(reservation);

        transition.command().accept(reservation);

        assertThat(reservation.state()).isEqualTo(transition.target());
        assertThat(reservation.allocation().active()).isEqualTo(transition.allocationActive());
    }

    @ParameterizedTest(name = "repeat {0}")
    @MethodSource("allowedTransitions")
    void repeatingTheSameTargetIsANoOp(TransitionCase transition) {
        Reservation reservation = fixture().reservation();
        transition.prepare().accept(reservation);
        transition.command().accept(reservation);
        boolean allocationActive = reservation.allocation().active();

        transition.command().accept(reservation);

        assertThat(reservation.state()).isEqualTo(transition.target());
        assertThat(reservation.allocation().active()).isEqualTo(allocationActive);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("forbiddenTransitions")
    void rejectsEveryForbiddenTransitionWithoutChangingReservationOrAllocation(
        ReservationState source,
        ReservationState target
    ) {
        Reservation reservation = fixture().reservation();
        moveTo(reservation, source);
        boolean allocationActive = reservation.allocation().active();

        assertThatThrownBy(() -> command(target).accept(reservation))
            .isInstanceOf(IllegalStateException.class);
        assertThat(reservation.state()).isEqualTo(source);
        assertThat(reservation.allocation().active()).isEqualTo(allocationActive);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deadlineBoundaries")
    void appliesDeadlineEqualityBoundariesWithoutSleeping(BoundaryCase boundary) {
        Reservation reservation = fixture().reservation();
        boundary.prepare().accept(reservation);

        if (boundary.allowed()) {
            boundary.command().accept(reservation);
            assertThat(reservation.state()).isEqualTo(boundary.target());
        } else {
            ReservationState before = reservation.state();
            boolean allocationActive = reservation.allocation().active();
            assertThatThrownBy(() -> boundary.command().accept(reservation))
                .isInstanceOf(IllegalStateException.class);
            assertThat(reservation.state()).isEqualTo(before);
            assertThat(reservation.allocation().active()).isEqualTo(allocationActive);
        }
    }

    @ParameterizedTest(name = "{0} at {1}")
    @MethodSource("effectiveCapacityCases")
    void computesEffectiveStateAndCapacityWithoutMutation(
        ReservationState storedState,
        Instant now,
        ReservationState effectiveState,
        boolean consumesCapacity
    ) {
        Reservation reservation = fixture().reservation();
        moveTo(reservation, storedState);
        boolean allocationActive = reservation.allocation().active();

        assertThat(reservation.effectiveState(at(now))).isEqualTo(effectiveState);
        assertThat(reservation.effectiveConsumesCapacity(at(now))).isEqualTo(consumesCapacity);
        assertThat(reservation.state()).isEqualTo(storedState);
        assertThat(reservation.allocation().active()).isEqualTo(allocationActive);
    }

    @Test
    void expireMaterializesDueHeldStateAndReleaseExactlyOnce() {
        Reservation reservation = fixture().reservation();
        assertThat(reservation.effectiveState(at(EXPIRES_AT))).isEqualTo(ReservationState.EXPIRED);
        assertThat(reservation.state()).isEqualTo(ReservationState.HELD);
        assertThat(reservation.allocation().active()).isTrue();

        reservation.expire(at(EXPIRES_AT));
        reservation.expire(at(EXPIRES_AT.plusSeconds(1)));

        assertThat(reservation.state()).isEqualTo(ReservationState.EXPIRED);
        assertThat(reservation.allocation().active()).isFalse();
    }

    @Test
    void validatesOwnershipEligibilityAndPolicySnapshotAtCreation() {
        Fixture fixture = fixture();
        TenantId otherTenant = TenantId.newId();
        VenueId otherVenue = VenueId.newId();
        Resource crossTenant = resource(
            fixture.resource().id(), otherTenant, fixture.venueId(), ResourceStatus.ACTIVE, 4
        );
        Resource crossVenue = resource(
            fixture.resource().id(), fixture.tenantId(), otherVenue, ResourceStatus.ACTIVE, 4
        );
        Resource inactive = resource(
            fixture.resource().id(), fixture.tenantId(), fixture.venueId(), ResourceStatus.INACTIVE, 4
        );

        assertThatThrownBy(() -> fixture.hold(crossTenant, fixture.slot(), fixture.deadlines(), new PartySize(4)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.hold(crossVenue, fixture.slot(), fixture.deadlines(), new PartySize(4)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.hold(inactive, fixture.slot(), fixture.deadlines(), new PartySize(4)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.hold(fixture.resource(), fixture.slot(), fixture.deadlines(), new PartySize(5)))
            .isInstanceOf(IllegalArgumentException.class);

        SlotInventory wrongResourceSlot = new SlotInventory(
            SlotInventoryId.newId(), fixture.tenantId(), fixture.venueId(), ResourceId.newId(),
            STARTS_AT, STARTS_AT.plusSeconds(1800), 1, 3
        );
        assertThatThrownBy(() -> fixture.hold(
            fixture.resource(), wrongResourceSlot, fixture.deadlines(), new PartySize(4)
        )).isInstanceOf(IllegalArgumentException.class);

        PolicyDeadlines expired = deadlines(3, CREATED_AT, CANCEL_ALLOWED_UNTIL, NO_SHOW_ELIGIBLE_AT);
        PolicyDeadlines expiryAfterStart = deadlines(
            3, STARTS_AT.plusSeconds(1), CANCEL_ALLOWED_UNTIL, NO_SHOW_ELIGIBLE_AT
        );
        PolicyDeadlines cancellationAfterStart = deadlines(
            3, EXPIRES_AT, STARTS_AT.plusSeconds(1), NO_SHOW_ELIGIBLE_AT
        );
        PolicyDeadlines noShowBeforeStart = deadlines(
            3, EXPIRES_AT, CANCEL_ALLOWED_UNTIL, STARTS_AT.minusSeconds(1)
        );

        Stream.of(expired, expiryAfterStart, cancellationAfterStart, noShowBeforeStart)
            .forEach(invalid -> assertThatThrownBy(() -> fixture.hold(
                fixture.resource(), fixture.slot(), invalid, new PartySize(4)
            )).isInstanceOf(IllegalArgumentException.class));
        assertThatThrownBy(() -> fixture.hold(fixture.resource(), fixture.slot(), fixture.deadlines(), null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Reservation.hold(
            fixture.reservationId(), fixture.allocationId(), fixture.tenantId(), fixture.venueId(),
            fixture.resource(), fixture.slot(), null, new PartySize(4), fixture.deadlines(), at(CREATED_AT)
        )).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PartySize(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesReservationPolicyVersionIndependentOfSlotCreationPolicyVersion() {
        Fixture fixture = fixture();
        SlotInventory policyVersionOneSlot = new SlotInventory(
            fixture.slot().id(), fixture.tenantId(), fixture.venueId(), fixture.resource().id(),
            STARTS_AT, STARTS_AT.plusSeconds(1800), 1, 1
        );
        PolicyDeadlines policyVersionTwoDeadlines = deadlines(
            2, EXPIRES_AT, CANCEL_ALLOWED_UNTIL, NO_SHOW_ELIGIBLE_AT
        );

        Reservation reservation = fixture.hold(
            fixture.resource(), policyVersionOneSlot, policyVersionTwoDeadlines, new PartySize(4)
        );

        assertThat(policyVersionOneSlot.appliedPolicyVersion()).isEqualTo(1);
        assertThat(reservation.appliedPolicyVersion()).isEqualTo(2);
        assertThat(reservation.state()).isEqualTo(ReservationState.HELD);
        assertThat(reservation.allocation().active()).isTrue();
    }

    private static Stream<TransitionCase> allowedTransitions() {
        Consumer<Reservation> none = reservation -> { };
        return Stream.of(
            new TransitionCase("HELD to CONFIRMED", none, command(ReservationState.CONFIRMED), ReservationState.CONFIRMED, true),
            new TransitionCase("HELD to EXPIRED", none, command(ReservationState.EXPIRED), ReservationState.EXPIRED, false),
            new TransitionCase("HELD to CANCELLED", none, command(ReservationState.CANCELLED), ReservationState.CANCELLED, false),
            new TransitionCase("CONFIRMED to CANCELLED", ReservationTests::confirm, command(ReservationState.CANCELLED), ReservationState.CANCELLED, false),
            new TransitionCase("CONFIRMED to CHECKED_IN", ReservationTests::confirm, command(ReservationState.CHECKED_IN), ReservationState.CHECKED_IN, true),
            new TransitionCase("CONFIRMED to NO_SHOW", ReservationTests::confirm, command(ReservationState.NO_SHOW), ReservationState.NO_SHOW, false),
            new TransitionCase("CHECKED_IN to COMPLETED", ReservationTests::checkIn, command(ReservationState.COMPLETED), ReservationState.COMPLETED, false)
        );
    }

    private static Stream<Arguments> forbiddenTransitions() {
        Set<String> allowed = Set.of(
            "HELD:CONFIRMED", "HELD:EXPIRED", "HELD:CANCELLED",
            "CONFIRMED:CANCELLED", "CONFIRMED:CHECKED_IN", "CONFIRMED:NO_SHOW",
            "CHECKED_IN:COMPLETED"
        );
        EnumSet<ReservationState> commandTargets = EnumSet.complementOf(EnumSet.of(ReservationState.HELD));
        return Stream.of(ReservationState.values())
            .flatMap(source -> commandTargets.stream().map(target -> Arguments.of(source, target)))
            .filter(arguments -> {
                ReservationState source = (ReservationState) arguments.get()[0];
                ReservationState target = (ReservationState) arguments.get()[1];
                return source != target && !allowed.contains(source + ":" + target);
            });
    }

    private static Stream<BoundaryCase> deadlineBoundaries() {
        return Stream.of(
            new BoundaryCase("confirm before expiresAt", reservation -> { },
                reservation -> reservation.confirm(at(EXPIRES_AT.minusNanos(1))), ReservationState.CONFIRMED, true),
            new BoundaryCase("confirm at expiresAt", reservation -> { },
                reservation -> reservation.confirm(at(EXPIRES_AT)), ReservationState.CONFIRMED, false),
            new BoundaryCase("expire before expiresAt", reservation -> { },
                reservation -> reservation.expire(at(EXPIRES_AT.minusNanos(1))), ReservationState.EXPIRED, false),
            new BoundaryCase("expire at expiresAt", reservation -> { },
                reservation -> reservation.expire(at(EXPIRES_AT)), ReservationState.EXPIRED, true),
            new BoundaryCase("cancel before cancelAllowedUntil", ReservationTests::confirm,
                reservation -> reservation.cancel(at(CANCEL_ALLOWED_UNTIL.minusNanos(1))), ReservationState.CANCELLED, true),
            new BoundaryCase("cancel at cancelAllowedUntil", ReservationTests::confirm,
                reservation -> reservation.cancel(at(CANCEL_ALLOWED_UNTIL)), ReservationState.CANCELLED, false),
            new BoundaryCase("check-in before startsAt", ReservationTests::confirm,
                reservation -> reservation.checkIn(at(STARTS_AT.minusNanos(1))), ReservationState.CHECKED_IN, false),
            new BoundaryCase("check-in at startsAt", ReservationTests::confirm,
                reservation -> reservation.checkIn(at(STARTS_AT)), ReservationState.CHECKED_IN, true),
            new BoundaryCase("check-in before noShowEligibleAt", ReservationTests::confirm,
                reservation -> reservation.checkIn(at(NO_SHOW_ELIGIBLE_AT.minusNanos(1))), ReservationState.CHECKED_IN, true),
            new BoundaryCase("check-in at noShowEligibleAt", ReservationTests::confirm,
                reservation -> reservation.checkIn(at(NO_SHOW_ELIGIBLE_AT)), ReservationState.CHECKED_IN, false),
            new BoundaryCase("no-show before noShowEligibleAt", ReservationTests::confirm,
                reservation -> reservation.markNoShow(at(NO_SHOW_ELIGIBLE_AT.minusNanos(1))), ReservationState.NO_SHOW, false),
            new BoundaryCase("no-show at noShowEligibleAt", ReservationTests::confirm,
                reservation -> reservation.markNoShow(at(NO_SHOW_ELIGIBLE_AT)), ReservationState.NO_SHOW, true)
        );
    }

    private static Stream<Arguments> effectiveCapacityCases() {
        return Stream.of(
            Arguments.of(ReservationState.HELD, EXPIRES_AT.minusNanos(1), ReservationState.HELD, true),
            Arguments.of(ReservationState.HELD, EXPIRES_AT, ReservationState.EXPIRED, false),
            Arguments.of(ReservationState.CONFIRMED, STARTS_AT, ReservationState.CONFIRMED, true),
            Arguments.of(ReservationState.CHECKED_IN, STARTS_AT, ReservationState.CHECKED_IN, true),
            Arguments.of(ReservationState.EXPIRED, EXPIRES_AT, ReservationState.EXPIRED, false),
            Arguments.of(ReservationState.CANCELLED, CREATED_AT, ReservationState.CANCELLED, false),
            Arguments.of(ReservationState.NO_SHOW, NO_SHOW_ELIGIBLE_AT, ReservationState.NO_SHOW, false),
            Arguments.of(ReservationState.COMPLETED, STARTS_AT, ReservationState.COMPLETED, false)
        );
    }

    private static Consumer<Reservation> command(ReservationState target) {
        return switch (target) {
            case CONFIRMED -> ReservationTests::confirm;
            case EXPIRED -> reservation -> reservation.expire(at(EXPIRES_AT));
            case CANCELLED -> reservation -> reservation.cancel(at(CREATED_AT));
            case CHECKED_IN -> reservation -> reservation.checkIn(at(STARTS_AT));
            case NO_SHOW -> reservation -> reservation.markNoShow(at(NO_SHOW_ELIGIBLE_AT));
            case COMPLETED -> Reservation::complete;
            case HELD -> throw new IllegalArgumentException("HELD has no reopening command");
        };
    }

    private static void moveTo(Reservation reservation, ReservationState target) {
        switch (target) {
            case HELD -> { }
            case CONFIRMED -> confirm(reservation);
            case CHECKED_IN -> checkIn(reservation);
            case EXPIRED -> reservation.expire(at(EXPIRES_AT));
            case CANCELLED -> reservation.cancel(at(CREATED_AT));
            case NO_SHOW -> {
                confirm(reservation);
                reservation.markNoShow(at(NO_SHOW_ELIGIBLE_AT));
            }
            case COMPLETED -> {
                checkIn(reservation);
                reservation.complete();
            }
        }
    }

    private static void confirm(Reservation reservation) {
        reservation.confirm(at(CREATED_AT));
    }

    private static void checkIn(Reservation reservation) {
        confirm(reservation);
        reservation.checkIn(at(STARTS_AT));
    }

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static Fixture fixture() {
        TenantId tenantId = TenantId.newId();
        VenueId venueId = VenueId.newId();
        Resource resource = resource(ResourceId.newId(), tenantId, venueId, ResourceStatus.ACTIVE, 4);
        SlotInventory slot = new SlotInventory(
            SlotInventoryId.newId(), tenantId, venueId, resource.id(),
            STARTS_AT, STARTS_AT.plusSeconds(1800), 1, 3
        );
        return new Fixture(
            ReservationId.newId(), CapacityAllocationId.newId(), tenantId, venueId,
            PrincipalId.newId(), resource, slot,
            deadlines(3, EXPIRES_AT, CANCEL_ALLOWED_UNTIL, NO_SHOW_ELIGIBLE_AT)
        );
    }

    private static Resource resource(
        ResourceId id,
        TenantId tenantId,
        VenueId venueId,
        ResourceStatus status,
        int seatingCapacity
    ) {
        return new Resource(id, tenantId, venueId, ResourceType.TABLE, "Table", seatingCapacity, status);
    }

    private static PolicyDeadlines deadlines(
        long version,
        Instant expiresAt,
        Instant cancelAllowedUntil,
        Instant noShowEligibleAt
    ) {
        return new PolicyDeadlines(version, expiresAt, cancelAllowedUntil, noShowEligibleAt);
    }

    private record TransitionCase(
        String name,
        Consumer<Reservation> prepare,
        Consumer<Reservation> command,
        ReservationState target,
        boolean allocationActive
    ) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record BoundaryCase(
        String name,
        Consumer<Reservation> prepare,
        Consumer<Reservation> command,
        ReservationState target,
        boolean allowed
    ) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record Fixture(
        ReservationId reservationId,
        CapacityAllocationId allocationId,
        TenantId tenantId,
        VenueId venueId,
        PrincipalId customerId,
        Resource resource,
        SlotInventory slot,
        PolicyDeadlines deadlines
    ) {
        Reservation reservation() {
            return hold(resource, slot, deadlines, new PartySize(4));
        }

        Reservation hold(
            Resource resourceToUse,
            SlotInventory slotToUse,
            PolicyDeadlines deadlinesToUse,
            PartySize partySize
        ) {
            return Reservation.hold(
                reservationId, allocationId, tenantId, venueId, resourceToUse, slotToUse,
                customerId, partySize, deadlinesToUse, at(CREATED_AT)
            );
        }
    }
}
