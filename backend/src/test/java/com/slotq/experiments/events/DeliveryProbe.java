package com.slotq.experiments.events;

import java.util.Map;

/** Test-only protocol probe. Durable attempt reservation makes a crash consume a finite budget. */
final class DeliveryProbe {
    private final EventFixture f;
    DeliveryProbe(EventFixture fixture) { f = fixture; }

    void schema() {
        f.db.execute("""
            CREATE TABLE fixture_delivery (
              event_id CHAR(36) PRIMARY KEY, state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
              attempts INT NOT NULL DEFAULT 0, lifetime_attempts INT NOT NULL DEFAULT 0,
              token BIGINT NOT NULL DEFAULT 0, lease_until TIMESTAMP(6) NULL,
              last_error VARCHAR(80) NULL,
              FOREIGN KEY(event_id) REFERENCES fixture_event(event_id)) ENGINE=InnoDB
            """);
        f.db.execute("""
            CREATE TABLE fixture_replay (
              id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id CHAR(36) NOT NULL,
              tenant_id CHAR(36) NOT NULL, reason VARCHAR(200) NOT NULL,
              prior_attempts INT NOT NULL, lifetime_attempts INT NOT NULL,
              recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
              FOREIGN KEY(event_id) REFERENCES fixture_event(event_id)) ENGINE=InnoDB
            """);
    }

    Long claim(String id) {
        return f.tx.execute(status -> {
            // Discovery can be repeated after any crash, without process-local event memory.
            f.db.update("INSERT IGNORE INTO fixture_delivery(event_id) SELECT event_id FROM fixture_event WHERE event_id=?", id);
            Map<String, Object> row = f.db.queryForMap("""
                SELECT *, lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP(6) AS due
                FROM fixture_delivery WHERE event_id=? FOR UPDATE
                """, id);
            String state = row.get("state").toString();
            if (state.equals("DONE") || state.equals("DEAD")
                || ((Number) row.get("due")).intValue() == 0) return null;
            if (((Number) row.get("attempts")).intValue() >= 3) {
                f.db.update("UPDATE fixture_delivery SET state='DEAD',last_error='CRASH_EXHAUSTED' WHERE event_id=?", id);
                return null;
            }
            long token = ((Number) row.get("token")).longValue() + 1;
            f.db.update("""
                UPDATE fixture_delivery SET state='PROCESSING', attempts=attempts+1,
                  lifetime_attempts=lifetime_attempts+1, token=?,
                  lease_until=TIMESTAMPADD(MICROSECOND,300000,CURRENT_TIMESTAMP(6)) WHERE event_id=?
                """, token, id);
            return token;
        });
    }

    String deliver(EventFixture.Event event, long token, String fault) {
        try {
            String outcome = f.tx.execute(status -> {
                Map<String, Object> row = f.db.queryForMap("""
                    SELECT *, lease_until > CURRENT_TIMESTAMP(6) AS live
                    FROM fixture_delivery WHERE event_id=? FOR UPDATE
                    """, event.eventId());
                if (!row.get("state").equals("PROCESSING")
                    || ((Number) row.get("token")).longValue() != token
                    || ((Number) row.get("live")).intValue() == 0) return "STALE";
                f.effect(event, fault);
                return "APPLIED";
            });
            if (outcome.equals("STALE")) return outcome;
            EventFixture.crash(fault, "AFTER_EFFECT");
            int updated = f.db.update("""
                UPDATE fixture_delivery SET state='DONE',lease_until=NULL,last_error=NULL
                WHERE event_id=? AND state='PROCESSING' AND token=? AND lease_until > CURRENT_TIMESTAMP(6)
                """, event.eventId(), token);
            return updated == 1 ? "DONE" : "STALE_ACK";
        } catch (RuntimeException failure) {
            String code = failure instanceof IllegalArgumentException ? failure.getMessage() : "TRANSIENT_FIXTURE";
            f.db.update("""
                UPDATE fixture_delivery SET state=IF(? OR attempts>=3,'DEAD','PENDING'),
                  lease_until=NULL,last_error=? WHERE event_id=? AND token=? AND state='PROCESSING'
                  AND lease_until > CURRENT_TIMESTAMP(6)
                """, failure instanceof IllegalArgumentException, code, event.eventId(), token);
            return code;
        }
    }

    void replay(String tenant, String id, String reason, boolean trustedInternal) {
        if (!trustedInternal || reason == null || reason.isBlank()) throw new SecurityException("REPLAY_DENIED");
        f.tx.executeWithoutResult(status -> {
            Map<String, Object> row = f.db.queryForMap("""
                SELECT d.* FROM fixture_delivery d JOIN fixture_event e ON e.event_id=d.event_id
                WHERE d.event_id=? AND e.tenant_id=? FOR UPDATE
                """, id, tenant);
            if (!row.get("state").equals("DEAD")) throw new IllegalStateException("NOT_DEAD");
            f.db.update("""
                INSERT INTO fixture_replay(event_id,tenant_id,reason,prior_attempts,lifetime_attempts)
                VALUES(?,?,?,?,?)
                """, id, tenant, reason, row.get("attempts"), row.get("lifetime_attempts"));
            f.db.update("""
                UPDATE fixture_delivery SET state='PENDING',attempts=0,token=token+1,
                  lease_until=NULL,last_error=NULL WHERE event_id=?
                """, id);
        });
    }
}
