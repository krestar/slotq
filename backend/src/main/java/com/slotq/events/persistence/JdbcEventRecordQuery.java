package com.slotq.events.persistence;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;

import com.slotq.events.application.EventId;
import com.slotq.events.application.EventRecordQuery;
import com.slotq.events.application.StoredEvent;
import com.slotq.tenancy.domain.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class JdbcEventRecordQuery implements EventRecordQuery {
    private final JdbcTemplate jdbc;
    JdbcEventRecordQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<StoredEvent> find(TenantId tenantId, EventId eventId) {
        return jdbc.query("SELECT * FROM event_records WHERE tenant_id = ? AND event_id = ?",
            JdbcEventRecordStore::storedEvent, bytes(tenantId.value()), bytes(eventId.value())).stream().findFirst();
    }
    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }
}
