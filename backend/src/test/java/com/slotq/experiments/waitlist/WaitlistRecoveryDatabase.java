package com.slotq.experiments.waitlist;

import java.nio.ByteBuffer;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only, tenant-scoped authoritative oracle; no synthetic effect tables or recovery writes. */
final class WaitlistRecoveryDatabase {
    static final List<String> TABLES = List.of("event_records", "event_deliveries", "waitlist_promotion_requests",
        "waitlist_promotion_receipts", "reservations", "capacity_allocations", "waitlist_entries",
        "waitlist_offers", "waitlist_notification_requests", "slot_inventories", "waitlist_demands");
    private final JdbcTemplate db;
    WaitlistRecoveryDatabase(JdbcTemplate db) { this.db = db; }

    Map<String,Object> snapshot(UUID tenant, Instant now) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
        tx.setReadOnly(true);
        return tx.execute(status -> read(db, tenant, now));
    }

    // Also used inside the faulting effect transaction: that diagnostic is NOT committed-state evidence.
    static Map<String,Object> read(JdbcTemplate db, UUID tenant, Instant now) {
        var result = new LinkedHashMap<String,Object>();
        result.put("tenantId", tenant.toString()); result.put("businessNow", now.toString());
        result.put("databaseNow", db.queryForObject("SELECT CAST(UTC_TIMESTAMP(6) AS CHAR)", String.class));
        for (String table : TABLES) result.put(table, rows(db, "SELECT * FROM " + table + " WHERE tenant_id=?", bytes(tenant)));
        result.put("registrations", rows(db, "SELECT * FROM event_registrations ORDER BY activation_boundary"));
        result.put("eventBoundary", db.queryForObject("SELECT sequence_value FROM event_boundary", Long.class));
        result.put("discoveryCursor", db.queryForObject("SELECT boundary_sequence FROM event_discovery", Long.class));
        var stats = new LinkedHashMap<String,Long>();
        for (String table : TABLES) stats.put(table, (long) list(result, table).size());
        stats.put("done", count(result,"event_deliveries","state","DONE"));
        stats.put("dead", count(result,"event_deliveries","state","DEAD"));
        stats.put("processing", count(result,"event_deliveries","state","PROCESSING"));
        stats.put("pendingOffers", count(result,"waitlist_offers","state","PENDING"));
        stats.put("waiting", count(result,"waitlist_entries","state","WAITING"));
        stats.put("offered", count(result,"waitlist_entries","state","OFFERED"));
        stats.put("expiredEntries", count(result,"waitlist_entries","state","EXPIRED"));
        stats.put("held", count(result,"reservations","state","HELD"));
        stats.put("promoted", count(result,"waitlist_promotion_receipts","outcome","PROMOTED"));
        stats.put("partialReceipts", list(result,"waitlist_promotion_receipts").stream().filter(r -> r.get("outcome")==null).count());
        stats.put("duplicateLogicalEffects", duplicates(result,"waitlist_promotion_receipts","event_id")
            + duplicates(result,"waitlist_offers","entry_id") + duplicates(result,"waitlist_offers","reservation_id")
            + duplicates(result,"waitlist_notification_requests","offer_id"));
        String scope = " WHERE tenant_id=?";
        stats.put("dueHeld", db.queryForObject("SELECT COUNT(*) FROM reservations"+scope+" AND state='HELD' AND expires_at<=?",
            Long.class,bytes(tenant),java.time.LocalDateTime.ofInstant(now,java.time.ZoneOffset.UTC)));
        stats.put("dueWaiting", db.queryForObject("SELECT COUNT(*) FROM waitlist_entries e JOIN waitlist_demands d ON d.id=e.demand_id"
            +" WHERE e.tenant_id=? AND e.state='WAITING' AND d.starts_at<=?",Long.class,bytes(tenant),java.time.LocalDateTime.ofInstant(now,java.time.ZoneOffset.UTC)));
        stats.put("activeAllocations", db.queryForObject("SELECT COUNT(*) FROM capacity_allocations"+scope+" AND active=TRUE",Long.class,bytes(tenant)));
        stats.put("occupancyViolations", db.queryForObject("SELECT COUNT(*) FROM (SELECT a.slot_inventory_id FROM capacity_allocations a"
            +" JOIN reservations r ON r.id=a.reservation_id WHERE a.tenant_id=? AND a.active=TRUE"
            +" AND (r.state IN ('CONFIRMED','CHECKED_IN') OR (r.state='HELD' AND r.expires_at>?))"
            +" GROUP BY a.slot_inventory_id HAVING SUM(a.units)>1) occupied",Long.class,bytes(tenant),java.time.LocalDateTime.ofInstant(now,java.time.ZoneOffset.UTC)));
        stats.put("unfinishedRequestLinks", db.queryForObject("SELECT COUNT(*) FROM waitlist_promotion_requests q"
            +" LEFT JOIN waitlist_promotion_receipts r ON r.tenant_id=q.tenant_id AND r.event_id=q.last_event_id"
            +" AND r.consumer_id='waitlist.promotion' WHERE q.tenant_id=? AND q.last_event_id IS NOT NULL"
            +" AND (r.event_id IS NULL OR r.outcome IS NULL)",Long.class,bytes(tenant)));
        result.put("stats",stats); return result;
    }
    private static List<Map<String,Object>> rows(JdbcTemplate db,String sql,Object... args) {
        var rows=db.query(sql,(row,n)->{
            var values=new LinkedHashMap<String,Object>(); ResultSetMetaData meta=row.getMetaData();
            for(int i=1;i<=meta.getColumnCount();i++) {
                Object value=row.getObject(i);
                if(meta.getColumnType(i)==java.sql.Types.TIMESTAMP && value!=null)
                    value=row.getObject(i,java.time.LocalDateTime.class).toInstant(java.time.ZoneOffset.UTC).toString();
                else if(value instanceof byte[] bytes) value=HexFormat.of().formatHex(bytes);
                else if(value!=null && !(value instanceof Number) && !(value instanceof Boolean)) value=value.toString();
                values.put(meta.getColumnLabel(i),value);
            }
            return (Map<String,Object>)values;
        },args);
        rows.sort(Comparator.comparing(Object::toString));return rows;
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> list(Map<String,Object> s,String key) { return (List<Map<String,Object>>)s.get(key); }
    static long count(Map<String,Object> s,String table,String key,String value) { return list(s,table).stream().filter(r->value.equals(r.get(key))).count(); }
    static long duplicates(Map<String,Object> s,String table,String key) {
        var ids=list(s,table).stream().map(r->r.get(key)).filter(Objects::nonNull).toList();return ids.size()-ids.stream().distinct().count();
    }
    static long stat(Map<String,Object> s,String key) { return ((Number)((Map<?,?>)s.get("stats")).get(key)).longValue(); }
    static boolean drained(Map<String,Object> s) { return stat(s,"event_records")==stat(s,"done")
        && stat(s,"done")==stat(s,"waitlist_promotion_receipts") && stat(s,"partialReceipts")==0
        && stat(s,"dead")==0 && stat(s,"unfinishedRequestLinks")==0; }
    static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
}
