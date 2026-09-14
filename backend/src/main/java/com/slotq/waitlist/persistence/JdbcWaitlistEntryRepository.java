package com.slotq.waitlist.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.slotq.auth.domain.PrincipalId;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.VenueId;
import com.slotq.waitlist.application.WaitlistEntryRepository;
import com.slotq.waitlist.domain.WaitlistDemand;
import com.slotq.waitlist.domain.WaitlistDemandId;
import com.slotq.waitlist.domain.WaitlistEntry;
import com.slotq.waitlist.domain.WaitlistEntryId;
import com.slotq.waitlist.domain.WaitlistEntryState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcWaitlistEntryRepository implements WaitlistEntryRepository {

    private static final String ENTRY_SELECT = """
        SELECT entry.id, entry.tenant_id, entry.venue_id, entry.customer_principal_id,
               entry.joined_at, entry.state, demand.id AS demand_id,
               demand.starts_at, demand.ends_at, demand.party_size
          FROM waitlist_entries entry
          JOIN waitlist_demands demand
            ON demand.tenant_id = entry.tenant_id
           AND demand.venue_id = entry.venue_id
           AND demand.id = entry.demand_id
        """;

    private final JdbcTemplate jdbc;

    JdbcWaitlistEntryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(WaitlistEntry entry) {
        jdbc.update("""
            INSERT INTO waitlist_entries (
                id, tenant_id, venue_id, customer_principal_id, demand_id, joined_at, state
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            JdbcWaitlistDemandStore.bytes(entry.id().value()),
            JdbcWaitlistDemandStore.bytes(entry.tenantId().value()),
            JdbcWaitlistDemandStore.bytes(entry.venueId().value()),
            JdbcWaitlistDemandStore.bytes(entry.customerPrincipalId().value()),
            JdbcWaitlistDemandStore.bytes(entry.demand().id().value()),
            Timestamp.from(entry.joinedAt()), entry.state().name()
        );
    }

    @Override
    public void updateState(VenueId venueId, WaitlistEntry entry) {
        if (!venueId.equals(entry.venueId())) {
            throw new IllegalArgumentException("Waitlist entry venue scope does not match");
        }
        int updated = jdbc.update("""
            UPDATE waitlist_entries SET state = ?
             WHERE tenant_id = ? AND venue_id = ? AND customer_principal_id = ? AND id = ?
            """,
            entry.state().name(),
            JdbcWaitlistDemandStore.bytes(entry.tenantId().value()),
            JdbcWaitlistDemandStore.bytes(venueId.value()),
            JdbcWaitlistDemandStore.bytes(entry.customerPrincipalId().value()),
            JdbcWaitlistDemandStore.bytes(entry.id().value())
        );
        if (updated != 1) {
            throw new IllegalStateException("Waitlist entry state update did not affect one row");
        }
    }

    @Override
    public Optional<WaitlistEntry> findOwned(
        VenueId venueId,
        PrincipalId customerPrincipalId,
        WaitlistEntryId entryId
    ) {
        return queryOne(
            " WHERE entry.venue_id = ? AND entry.customer_principal_id = ? AND entry.id = ?",
            JdbcWaitlistDemandStore.bytes(venueId.value()),
            JdbcWaitlistDemandStore.bytes(customerPrincipalId.value()),
            JdbcWaitlistDemandStore.bytes(entryId.value())
        );
    }

    @Override
    public Optional<WaitlistEntry> findOwnedForUpdate(
        VenueId venueId,
        PrincipalId customerPrincipalId,
        WaitlistEntryId entryId
    ) {
        return queryOne(
            " WHERE entry.venue_id = ? AND entry.customer_principal_id = ? AND entry.id = ? FOR UPDATE",
            JdbcWaitlistDemandStore.bytes(venueId.value()),
            JdbcWaitlistDemandStore.bytes(customerPrincipalId.value()),
            JdbcWaitlistDemandStore.bytes(entryId.value())
        );
    }

    @Override
    public Optional<WaitlistEntry> find(VenueId venueId, WaitlistEntryId entryId) {
        return queryOne(" WHERE entry.venue_id = ? AND entry.id = ?",
            JdbcWaitlistDemandStore.bytes(venueId.value()),
            JdbcWaitlistDemandStore.bytes(entryId.value()));
    }

    @Override
    public Optional<WaitlistEntry> findForUpdate(VenueId venueId, WaitlistEntryId entryId) {
        return queryOne(" WHERE entry.venue_id = ? AND entry.id = ? FOR UPDATE",
            JdbcWaitlistDemandStore.bytes(venueId.value()),
            JdbcWaitlistDemandStore.bytes(entryId.value()));
    }

    @Override
    public Optional<WaitlistEntry> findActiveForUpdate(
        TenantId tenantId,
        VenueId venueId,
        PrincipalId customerPrincipalId,
        WaitlistDemandId demandId
    ) {
        return queryOne(
            " WHERE entry.tenant_id = ? AND entry.venue_id = ?"
                + " AND entry.customer_principal_id = ? AND entry.demand_id = ?"
                + " AND entry.state IN ('WAITING', 'OFFERED') FOR UPDATE",
            JdbcWaitlistDemandStore.bytes(tenantId.value()),
            JdbcWaitlistDemandStore.bytes(venueId.value()),
            JdbcWaitlistDemandStore.bytes(customerPrincipalId.value()),
            JdbcWaitlistDemandStore.bytes(demandId.value())
        );
    }

    @Override
    public Page findAllOwned(
        TenantId tenantId,
        VenueId venueId,
        PrincipalId customerPrincipalId,
        java.time.Instant startsAt,
        java.time.Instant endsAt,
        Optional<Seek> seek,
        int fetchSize
    ) {
        StringBuilder sql = new StringBuilder(ENTRY_SELECT).append("""
             WHERE entry.tenant_id = ? AND entry.venue_id = ?
               AND entry.customer_principal_id = ?
               AND demand.starts_at >= ? AND demand.starts_at < ?
            """);
        List<Object> args = new ArrayList<>(List.of(
            JdbcWaitlistDemandStore.bytes(tenantId.value()),
            JdbcWaitlistDemandStore.bytes(venueId.value()),
            JdbcWaitlistDemandStore.bytes(customerPrincipalId.value()),
            Timestamp.from(startsAt), Timestamp.from(endsAt)
        ));
        appendSeek(sql, args, seek);
        sql.append(" ORDER BY entry.joined_at, entry.id LIMIT ?");
        args.add(fetchSize);
        return new Page(jdbc.query(sql.toString(), JdbcWaitlistEntryRepository::entry, args.toArray()));
    }

    @Override
    public Page findAllForVenue(
        TenantId tenantId,
        VenueId venueId,
        java.time.Instant startsAt,
        java.time.Instant endsAt,
        Optional<TimeWindow> demandWindow,
        Optional<Seek> seek,
        int fetchSize
    ) {
        StringBuilder sql = new StringBuilder(ENTRY_SELECT).append("""
             WHERE entry.tenant_id = ? AND entry.venue_id = ?
               AND demand.starts_at >= ? AND demand.starts_at < ?
            """);
        List<Object> args = new ArrayList<>(List.of(
            JdbcWaitlistDemandStore.bytes(tenantId.value()),
            JdbcWaitlistDemandStore.bytes(venueId.value()),
            Timestamp.from(startsAt), Timestamp.from(endsAt)
        ));
        demandWindow.ifPresent(window -> {
            sql.append(" AND demand.starts_at = ? AND demand.ends_at = ?");
            args.add(Timestamp.from(window.startsAt()));
            args.add(Timestamp.from(window.endsAt()));
        });
        appendSeek(sql, args, seek);
        sql.append(" ORDER BY entry.joined_at, entry.id LIMIT ?");
        args.add(fetchSize);
        return new Page(jdbc.query(sql.toString(), JdbcWaitlistEntryRepository::entry, args.toArray()));
    }

    private static void appendSeek(
        StringBuilder sql,
        List<Object> args,
        Optional<Seek> seek
    ) {
        seek.ifPresent(value -> {
            sql.append(" AND (entry.joined_at > ? OR (entry.joined_at = ? AND entry.id > ?))");
            args.add(Timestamp.from(value.joinedAt()));
            args.add(Timestamp.from(value.joinedAt()));
            args.add(JdbcWaitlistDemandStore.bytes(value.entryId().value()));
        });
    }

    private Optional<WaitlistEntry> queryOne(String where, Object... args) {
        return jdbc.query(ENTRY_SELECT + where, JdbcWaitlistEntryRepository::entry, args)
            .stream().findFirst();
    }

    private static WaitlistEntry entry(ResultSet row, int number) throws SQLException {
        TenantId tenantId = new TenantId(JdbcWaitlistDemandStore.uuid(row.getBytes("tenant_id")));
        VenueId venueId = new VenueId(JdbcWaitlistDemandStore.uuid(row.getBytes("venue_id")));
        WaitlistDemand demand = new WaitlistDemand(
            new WaitlistDemandId(JdbcWaitlistDemandStore.uuid(row.getBytes("demand_id"))),
            tenantId,
            venueId,
            instant(row, "starts_at"),
            instant(row, "ends_at"),
            row.getInt("party_size")
        );
        return new WaitlistEntry(
            new WaitlistEntryId(JdbcWaitlistDemandStore.uuid(row.getBytes("id"))),
            tenantId,
            venueId,
            new PrincipalId(JdbcWaitlistDemandStore.uuid(row.getBytes("customer_principal_id"))),
            demand,
            instant(row, "joined_at"),
            WaitlistEntryState.valueOf(row.getString("state"))
        );
    }

    private static java.time.Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
