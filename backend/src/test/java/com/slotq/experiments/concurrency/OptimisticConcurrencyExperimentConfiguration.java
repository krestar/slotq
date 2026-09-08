package com.slotq.experiments.concurrency;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.booking.application.ReservationCommand;
import com.slotq.booking.application.ReservationRepository;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.booking.application.SlotInventoryRepository;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.ReservationId;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.booking.domain.SlotInventoryId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.ResourceId;
import com.slotq.venue.domain.VenueId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("optimistic-concurrency-experiment")
class OptimisticConcurrencyExperimentConfiguration {

    @Bean
    OptimisticConcurrencyExperimentState optimisticConcurrencyExperimentState() {
        return new OptimisticConcurrencyExperimentState();
    }

    @Bean
    @Primary
    SlotInventoryRepository optimisticSlotInventoryRepository(
        @Qualifier("slotInventoryPersistenceAdapter") SlotInventoryRepository delegate,
        JdbcTemplate jdbcTemplate,
        OptimisticConcurrencyExperimentState state
    ) {
        return new OptimisticSlotInventoryRepository(delegate, jdbcTemplate, state);
    }

    @Bean
    @Primary
    ReservationRepository optimisticReservationRepository(
        @Qualifier("reservationPersistenceAdapter") ReservationRepository delegate,
        JdbcTemplate jdbcTemplate,
        OptimisticConcurrencyExperimentState state
    ) {
        return new OptimisticReservationRepository(delegate, jdbcTemplate, state);
    }

    @Bean
    @Primary
    ReservationUseCase optimisticReservationUseCase(
        @Qualifier("reservationService") ReservationUseCase delegate,
        OptimisticConcurrencyExperimentState state
    ) {
        return new BoundedRetryReservationUseCase(delegate, state);
    }

    private static final class OptimisticSlotInventoryRepository
        implements SlotInventoryRepository {

        private final SlotInventoryRepository delegate;
        private final JdbcTemplate jdbcTemplate;
        private final OptimisticConcurrencyExperimentState state;

        private OptimisticSlotInventoryRepository(
            SlotInventoryRepository delegate,
            JdbcTemplate jdbcTemplate,
            OptimisticConcurrencyExperimentState state
        ) {
            this.delegate = delegate;
            this.jdbcTemplate = jdbcTemplate;
            this.state = state;
        }

        @Override
        public void save(SlotInventory slotInventory) {
            delegate.save(slotInventory);
        }

        @Override
        public Optional<SlotInventory> find(
            TenantId tenantId,
            VenueId venueId,
            ResourceId resourceId,
            SlotInventoryId slotInventoryId
        ) {
            return delegate.find(tenantId, venueId, resourceId, slotInventoryId);
        }

        @Override
        public Optional<SlotInventory> find(VenueId venueId, SlotInventoryId slotInventoryId) {
            return delegate.find(venueId, slotInventoryId);
        }

        @Override
        public Optional<SlotInventory> findForUpdate(
            VenueId venueId,
            SlotInventoryId slotInventoryId
        ) {
            Optional<SlotInventory> slot = delegate.find(venueId, slotInventoryId);
            slot.ifPresent(found -> state.observe(new OptimisticConcurrencyExperimentState.ExpectedVersion(
                found.tenantId(), found.venueId(), found.id(), jdbcTemplate.queryForObject(
                    """
                    SELECT capacity_version
                      FROM slot_inventories
                     WHERE tenant_id = ? AND venue_id = ? AND id = ?
                    """,
                    Long.class,
                    bytes(found.tenantId().value()),
                    bytes(found.venueId().value()),
                    bytes(found.id().value())
                )
            )));
            return slot;
        }

        @Override
        public List<SlotInventory> findAll(
            TenantId tenantId,
            VenueId venueId,
            java.time.Instant startsAt,
            java.time.Instant endsAt
        ) {
            return delegate.findAll(tenantId, venueId, startsAt, endsAt);
        }

        @Override
        public boolean overlaps(
            TenantId tenantId,
            VenueId venueId,
            ResourceId resourceId,
            java.time.Instant startsAt,
            java.time.Instant endsAt
        ) {
            return delegate.overlaps(tenantId, venueId, resourceId, startsAt, endsAt);
        }
    }

    private static final class OptimisticReservationRepository implements ReservationRepository {

        private final ReservationRepository delegate;
        private final JdbcTemplate jdbcTemplate;
        private final OptimisticConcurrencyExperimentState state;

        private OptimisticReservationRepository(
            ReservationRepository delegate,
            JdbcTemplate jdbcTemplate,
            OptimisticConcurrencyExperimentState state
        ) {
            this.delegate = delegate;
            this.jdbcTemplate = jdbcTemplate;
            this.state = state;
        }

        @Override
        public void save(Reservation reservation) {
            OptimisticConcurrencyExperimentState.ExpectedVersion expected = state.expected();
            if (expected != null) {
                int updated = jdbcTemplate.update("""
                    UPDATE slot_inventories
                       SET capacity_version = capacity_version + 1
                     WHERE tenant_id = ? AND venue_id = ? AND id = ? AND capacity_version = ?
                    """,
                    bytes(expected.tenantId().value()),
                    bytes(expected.venueId().value()),
                    bytes(expected.slotInventoryId().value()),
                    expected.version()
                );
                if (updated != 1) {
                    throw new OptimisticLockingFailureException(
                        "Experiment SlotInventory capacity version changed"
                    );
                }
            }
            delegate.save(reservation);
        }

        @Override
        public Optional<Reservation> find(VenueId venueId, ReservationId reservationId) {
            return delegate.find(venueId, reservationId);
        }

        @Override
        public Optional<Reservation> findForUpdate(VenueId venueId, ReservationId reservationId) {
            return delegate.findForUpdate(venueId, reservationId);
        }

        @Override
        public Optional<Reservation> findCurrent(VenueId venueId, ReservationId reservationId) {
            return delegate.findCurrent(venueId, reservationId);
        }

        @Override
        public List<Reservation> findAll(
            TenantId tenantId,
            VenueId venueId,
            java.time.Instant startsAt,
            java.time.Instant endsAt
        ) {
            return delegate.findAll(tenantId, venueId, startsAt, endsAt);
        }

        @Override
        public boolean existsEffectiveCapacityConsumer(
            TenantId tenantId,
            VenueId venueId,
            ResourceId resourceId,
            SlotInventoryId slotInventoryId,
            java.time.Instant now
        ) {
            return delegate.existsEffectiveCapacityConsumer(
                tenantId, venueId, resourceId, slotInventoryId, now
            );
        }
    }

    private static final class BoundedRetryReservationUseCase implements ReservationUseCase {

        private static final int MAX_ATTEMPTS = 2;

        private final ReservationUseCase delegate;
        private final OptimisticConcurrencyExperimentState state;

        private BoundedRetryReservationUseCase(
            ReservationUseCase delegate,
            OptimisticConcurrencyExperimentState state
        ) {
            this.delegate = delegate;
            this.state = state;
        }

        @Override
        public ReservationDetails createHold(CreateHold command) {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                state.beginAttempt();
                try {
                    return delegate.createHold(command);
                } catch (OptimisticLockingFailureException exception) {
                    if (attempt == MAX_ATTEMPTS) {
                        state.recordExhaustion();
                        throw exception;
                    }
                    state.recordRetry();
                } finally {
                    state.endAttempt();
                }
            }
            throw new IllegalStateException("Optimistic experiment retry loop did not terminate");
        }

        @Override
        public ReservationDetails getReservation(
            VenueId venueId,
            ReservationId reservationId,
            AuthenticatedPrincipal principal
        ) {
            return delegate.getReservation(venueId, reservationId, principal);
        }

        @Override
        public ReservationDetails transition(
            VenueId venueId,
            ReservationId reservationId,
            AuthenticatedPrincipal principal,
            ReservationCommand command
        ) {
            return delegate.transition(venueId, reservationId, principal, command);
        }
    }

    private static byte[] bytes(java.util.UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits()).array();
    }
}

final class OptimisticConcurrencyExperimentState {

    private final ThreadLocal<ExpectedVersion> expected = new ThreadLocal<>();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong exhaustions = new AtomicLong();

    void beginAttempt() {
        expected.remove();
    }

    void observe(ExpectedVersion version) {
        expected.set(version);
    }

    ExpectedVersion expected() {
        return expected.get();
    }

    void endAttempt() {
        expected.remove();
    }

    void recordRetry() {
        retries.incrementAndGet();
    }

    void recordExhaustion() {
        exhaustions.incrementAndGet();
    }

    Snapshot snapshot() {
        return new Snapshot(retries.get(), exhaustions.get());
    }

    record ExpectedVersion(
        TenantId tenantId,
        VenueId venueId,
        SlotInventoryId slotInventoryId,
        long version
    ) {
    }

    record Snapshot(long retries, long exhaustions) {
    }
}
