package com.slotq;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.slotq.auth.application.AccessControlProvisioning;
import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.booking.application.PromotionalReservationUseCase;
import com.slotq.booking.application.ReservationRepository;
import com.slotq.booking.application.SlotInventoryUseCase;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.tenancy.application.TenantUseCase;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.venue.application.ResourceUseCase;
import com.slotq.venue.application.VenueConfigurationUseCase;
import com.slotq.venue.domain.BookingPolicyTerms;
import com.slotq.venue.domain.DailyOperatingHours;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.WeeklyOperatingHours;
import com.slotq.waitlist.application.WaitlistOfferUseCase;
import com.slotq.waitlist.application.WaitlistRegistrationKey;
import com.slotq.waitlist.application.WaitlistUseCase;
import com.slotq.waitlist.domain.WaitlistEntryId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/** Diagnostic only: failed candidates are not Product remedies or C3 acceptance tests. */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "SLOTQ_CAPACITY_LOCK_EVIDENCE", matches = "true")
@SpringBootTest(properties = "slotq.events.delivery.scheduler-enabled=false")
@Import(BookingCapacityLockEvidenceTests.Configuration.class)
class BookingCapacityLockEvidenceTests {
    static final Instant NOW = Instant.parse("2026-08-30T09:00:00Z");
    static final Instant START = Instant.parse("2026-08-30T11:00:00Z");
    @Container @ServiceConnection
    static final org.testcontainers.mysql.MySQLContainer MYSQL =
        new org.testcontainers.mysql.MySQLContainer("mysql:8.4").withDatabaseName("capacity_lock_evidence");
    @Autowired TenantUseCase tenants;
    @Autowired VenueConfigurationUseCase venues;
    @Autowired ResourceUseCase resources;
    @Autowired SlotInventoryUseCase slots;
    @Autowired AccessControlProvisioning access;
    @Autowired WaitlistUseCase waitlist;
    @Autowired WaitlistOfferUseCase offers;
    @Autowired PromotionalReservationUseCase promotional;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.slotq.booking.application.ReservationUseCase ordinary;
    @Autowired com.slotq.booking.application.SlotInventoryRepository slotRepository;
    @MockitoSpyBean ReservationRepository reservations;

    enum Scenario {
        FIXED_TARGET, CAPACITY_ONLY, FORCE_RESERVATION_INDEX, FORCE_ALLOCATION_INDEX,
        CURRENT_FOR_UPDATE, CURRENT_NOWAIT, IDENTITY_ONLY, OFFER_ONLY, NO_GAP_NEGATIVE_CONTROL
    }

    @Test void narrowingLockToSlotOrDiscoveringIdsWithSnapshotMissesCommittedCapacity() throws Exception {
        Fixture fixture = fixture(null, START);
        try (var pool = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE tenant_id=?", Integer.class,
                    bytes(fixture.tenant.id().value()))).isZero();
                try {
                    pool.submit(() -> ordinary.createHold(new com.slotq.booking.application.ReservationUseCase.CreateHold(
                        fixture.venue.id(), fixture.slot.id(), fixture.customer, 2))).get(5, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new IllegalStateException(failure); }
                slotRepository.findForUpdate(fixture.venue.id(), fixture.slot.id()).orElseThrow();
                // A two-step snapshot ID discovery followed by unique current reads also misses this new ID.
                assertThat(jdbc.queryForList("SELECT id FROM reservations WHERE slot_inventory_id=?", bytes(fixture.slot.id().value()))).isEmpty();
                assertThat(jdbc.queryForList("""
                    SELECT r.id FROM slot_inventories s
                    JOIN reservations r ON r.tenant_id=s.tenant_id AND r.venue_id=s.venue_id
                        AND r.resource_id=s.resource_id AND r.slot_inventory_id=s.id
                    JOIN capacity_allocations a ON a.tenant_id=r.tenant_id AND a.venue_id=r.venue_id
                        AND a.resource_id=r.resource_id AND a.slot_inventory_id=r.slot_inventory_id AND a.reservation_id=r.id
                    WHERE s.id=? AND a.active=TRUE
                        AND (r.state IN ('CONFIRMED','CHECKED_IN') OR (r.state='HELD' AND r.expires_at>?))
                    LIMIT 1 FOR SHARE OF s
                    """, bytes(fixture.slot.id().value()), java.sql.Timestamp.from(NOW))).isEmpty();
                assertThat(reservations.existsEffectiveCapacityConsumerCurrent(fixture.tenant.id(), fixture.venue.id(),
                    fixture.resource.id(), fixture.slot.id(), null, NOW)).isTrue();
            });
        }
    }

    @ParameterizedTest @EnumSource(Scenario.class)
    void differentSlotsRecordHeldGapsAndActualOrmInsertCycle(Scenario scenario) throws Exception {
        Fixture first = fixture(null, START);
        Fixture second = fixture(first, START.plusSeconds(1800));
        // Distinct Demand/time/Resource/Slot/Entry; no candidate-selection query or M3 exists here.
        assertThat(first.slot.id()).isNotEqualTo(second.slot.id());
        assertThat(first.entry).isNotEqualTo(second.entry);
        if (scenario != Scenario.FIXED_TARGET && scenario != Scenario.IDENTITY_ONLY) {
            doReturn(Optional.empty()).when(reservations).findPromotionalCurrent(any(), any());
        }
        CountDownLatch reads = new CountDownLatch(2), proceed = new CountDownLatch(1);
        doAnswer(call -> {
            boolean occupied = switch (scenario) {
                case FIXED_TARGET, CAPACITY_ONLY -> (boolean) call.callRealMethod();
                case FORCE_RESERVATION_INDEX, FORCE_ALLOCATION_INDEX, CURRENT_FOR_UPDATE, CURRENT_NOWAIT ->
                    probeCapacity(call.getArgument(0, com.slotq.tenancy.domain.TenantId.class).value(),
                        call.getArgument(1, com.slotq.venue.domain.VenueId.class).value(),
                        call.getArgument(2, com.slotq.venue.domain.ResourceId.class).value(),
                        call.getArgument(3, com.slotq.booking.domain.SlotInventoryId.class).value(), scenario);
                case IDENTITY_ONLY, OFFER_ONLY, NO_GAP_NEGATIVE_CONTROL -> false;
            };
            assertThat(occupied).isFalse();
            reads.countDown(); await(proceed);
            return occupied;
        }).when(reservations).existsEffectiveCapacityConsumerCurrent(any(), any(), any(), any(), any(), any());
        String before;
        boolean bothReads;
        List<Attempt> result;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> attempt(first, scenario));
            var two = pool.submit(() -> attempt(second, scenario));
            try {
                bothReads = reads.await(3, TimeUnit.SECONDS);
                before = observeLocks();
            } finally { proceed.countDown(); }
            result = List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        }
        // SHOW ENGINE retains an older victim after a successful scenario; never attribute it here.
        String trace = result.stream().anyMatch(attempt -> attempt.code == 1213) ? innodbStatus() : "NO NEW DEADLOCK";
        Path output = Path.of("build", "capacity-lock-evidence", scenario.name() + ".txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "MYSQL=" + jdbc.queryForObject("SELECT VERSION()", String.class)
            + " isolation=" + jdbc.queryForObject("SELECT @@transaction_isolation", String.class)
            + "\nscenario=" + scenario + " bothReadsBeforeInsert=" + bothReads + "\nfirst=" + first + "\nsecond=" + second
            + "\nresult=" + result + "\nBEFORE INSERT:\n" + before + "\n" + trace);
        if (scenario == Scenario.NO_GAP_NEGATIVE_CONTROL) {
            assertThat(result).allMatch(attempt -> attempt.success);
        } else {
            // FOR UPDATE may first wait on an unrelated next-key record while already holding
            // its compatible gap, then deadlock against the first reader's INSERT.
            if (scenario == Scenario.CURRENT_FOR_UPDATE && !bothReads) assertThat(before).contains("WAITING");
            else assertThat(bothReads).isTrue();
            assertThat(result.stream().filter(Attempt::success).count()).isEqualTo(1);
            assertThat(result.stream().filter(attempt -> attempt.code == 1213).count()).isEqualTo(1);
            assertThat(trace).contains("LATEST DETECTED DEADLOCK", "insert intention waiting");
        }
        int committed = (int) result.stream().filter(Attempt::success).count();
        for (String table : List.of("reservations", "capacity_allocations")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class,
                bytes(first.tenant.id().value()))).as(table).isEqualTo(committed);
        }
        if (scenario == Scenario.FIXED_TARGET || scenario == Scenario.OFFER_ONLY) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_offers WHERE tenant_id=?", Integer.class,
                bytes(first.tenant.id().value()))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM waitlist_entries WHERE tenant_id=? AND state='WAITING'",
                Integer.class, bytes(first.tenant.id().value()))).isEqualTo(1);
        }
    }

    private boolean probeCapacity(UUID tenantId, UUID venueId, UUID resourceId, UUID slotId, Scenario scenario) {
        String source = scenario == Scenario.FORCE_ALLOCATION_INDEX
            ? "capacity_allocations a FORCE INDEX (idx_capacity_allocations_effective) STRAIGHT_JOIN reservations r ON r.id=a.reservation_id"
            : "reservations r FORCE INDEX (idx_reservations_effective_occupancy) STRAIGHT_JOIN capacity_allocations a ON a.reservation_id=r.id";
        String locking = scenario == Scenario.CURRENT_FOR_UPDATE ? "FOR UPDATE"
            : scenario == Scenario.CURRENT_NOWAIT ? "FOR SHARE NOWAIT" : "FOR SHARE";
        // Preserve the full scoped join/predicate; hints and locking clause alone vary.
        return !jdbc.queryForList("SELECT r.id FROM " + source
            + " WHERE r.tenant_id=? AND r.venue_id=? AND r.resource_id=? AND r.slot_inventory_id=?"
            + " AND a.tenant_id=r.tenant_id AND a.venue_id=r.venue_id AND a.resource_id=r.resource_id"
            + " AND a.slot_inventory_id=r.slot_inventory_id AND a.active=TRUE"
            + " AND (r.state IN ('CONFIRMED','CHECKED_IN') OR (r.state='HELD' AND r.expires_at>?)) LIMIT 1 " + locking,
            bytes(tenantId), bytes(venueId), bytes(resourceId), bytes(slotId), java.sql.Timestamp.from(NOW)).isEmpty();
    }

    private Attempt attempt(Fixture fixture, Scenario scenario) {
        try {
            if (scenario == Scenario.FIXED_TARGET || scenario == Scenario.OFFER_ONLY) {
                var result = offers.createTarget(SystemPrincipal.INSTANCE, fixture.venue.id(),
                    new WaitlistEntryId(fixture.entry), fixture.slot.id());
                assertThat(result.outcome()).isEqualTo(WaitlistOfferUseCase.TargetOutcome.CREATED);
            } else {
                var result = promotional.createHold(new PromotionalReservationUseCase.CreateCommand(
                    SystemPrincipal.INSTANCE, fixture.entry, fixture.tenant.id(), fixture.venue.id(), fixture.slot.id(),
                    fixture.customer.principalId(), fixture.slot.startsAt(), fixture.slot.endsAt(), 2, NOW),
                    ignored -> PromotionalReservationUseCase.GateDecision.PROCEED);
                assertThat(result.outcome()).isEqualTo(PromotionalReservationUseCase.CreateOutcome.CREATED);
            }
            return new Attempt(true, 0, "committed");
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) return new Attempt(false, sql.getErrorCode(), sql.getMessage());
            }
            throw failure;
        }
    }

    private String observeLocks() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement(); var rows = statement.executeQuery("""
                 SELECT ENGINE_TRANSACTION_ID,OBJECT_NAME,INDEX_NAME,LOCK_TYPE,LOCK_MODE,LOCK_STATUS,LOCK_DATA
                 FROM performance_schema.data_locks WHERE OBJECT_SCHEMA='capacity_lock_evidence'
                 ORDER BY ENGINE_TRANSACTION_ID,OBJECT_NAME,INDEX_NAME,LOCK_DATA
                 """)) {
            StringBuilder value = new StringBuilder();
            while (rows.next()) {
                for (int column = 1; column <= 7; column++) value.append(rows.getString(column)).append('|');
                value.append('\n');
            }
            return value.toString();
        }
    }
    private String innodbStatus() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW ENGINE INNODB STATUS")) {
            rows.next(); String status = rows.getString("Status");
            int start = status.indexOf("LATEST DETECTED DEADLOCK");
            return start < 0 ? "NO DEADLOCK" : status.substring(start, status.indexOf("TRANSACTIONS", start));
        }
    }
    private Fixture fixture(Fixture previous, Instant start) {
        Tenant tenant = previous == null ? tenants.createTenant() : previous.tenant;
        Venue venue = previous == null ? venues.createVenue(new VenueConfigurationUseCase.CreateVenue(tenant.id(), "Evidence", "UTC",
            new WeeklyOperatingHours(Map.of(DayOfWeek.SUNDAY, new DailyOperatingHours(LocalTime.of(9, 0), LocalTime.of(14, 0)))),
            new BookingPolicyTerms(30, 5, 20, 10))) : previous.venue;
        Resource resource = resources.createResource(new ResourceUseCase.CreateResource(tenant.id(), venue.id(), "Table", 4));
        SlotInventory slot = slots.createSlot(new SlotInventoryUseCase.CreateSlot(tenant.id(), venue.id(), resource.id(), start.toString()));
        var customer = new AuthenticatedPrincipal(PrincipalId.newId());
        access.registerPrincipal(customer.principalId());
        UUID entry = waitlist.register(new WaitlistUseCase.CreateRegistration(venue.id(), slot.id(), 2,
            new WaitlistRegistrationKey(UUID.randomUUID()), customer)).entry().id();
        return new Fixture(tenant, venue, resource, slot, customer, entry);
    }
    private static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    private static void await(CountDownLatch latch) throws InterruptedException { assertThat(latch.await(12, TimeUnit.SECONDS)).isTrue(); }
    record Fixture(Tenant tenant, Venue venue, Resource resource, SlotInventory slot, AuthenticatedPrincipal customer, UUID entry) { }
    record Attempt(boolean success, int code, String message) { }
    @TestConfiguration static class Configuration {
        @Bean @Primary Clock evidenceClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
