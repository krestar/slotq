package com.slotq.evaluation;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;

/** Fresh JDBC reads outside the application's persistence context; only digests leave this observer. */
public final class M8BusinessSnapshot {
    private static final List<String> TABLES = List.of(
        "reservations", "capacity_allocations", "hold_idempotency_records",
        "waitlist_demands", "waitlist_entries", "waitlist_registration_requests", "waitlist_offers",
        "waitlist_promotion_requests", "waitlist_promotion_receipts", "waitlist_notification_requests",
        "event_records", "event_deliveries", "event_observation_projections"
    );

    public static Map<String, String> capture(DataSource source, UUID tenant) {
        var result = new TreeMap<String, String>();
        try (var connection = source.getConnection()) {
            connection.setReadOnly(true);
            for (String table : TABLES) {
                var rows = new ArrayList<String>();
                try (var query = connection.prepareStatement("SELECT * FROM " + table + " WHERE tenant_id=?")) {
                    query.setBytes(1, java.nio.ByteBuffer.allocate(16)
                        .putLong(tenant.getMostSignificantBits()).putLong(tenant.getLeastSignificantBits()).array());
                    try (var values = query.executeQuery()) {
                        while (values.next()) {
                            var fields = new ArrayList<String>();
                            for (int column = 1; column <= values.getMetaData().getColumnCount(); column++) {
                                Object value = values.getObject(column);
                                String normalized = value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes)
                                    : value == null ? "NULL" : value.getClass().getName() + ":" + value;
                                fields.add(normalized.length() + ":" + normalized);
                            }
                            rows.add(String.join("", fields));
                        }
                    }
                }
                Collections.sort(rows);
                result.put(table, M8FixtureValidator.digest(String.join("\n", rows).getBytes(StandardCharsets.UTF_8)));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Independent business observation unavailable");
        }
        return Map.copyOf(result);
    }

    private M8BusinessSnapshot() { }
}
