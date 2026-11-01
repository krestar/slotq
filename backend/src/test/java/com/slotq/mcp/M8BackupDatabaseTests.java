package com.slotq.mcp;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;
import static com.slotq.mcp.M8CapacityEvidence.*;
import static org.assertj.core.api.Assertions.*;

class M8BackupDatabaseTests {
    @Test void offlineReconciliationPreservesEveryCellIncludingMicrosecondExpiry() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.4").withCommand("--log-bin-trust-function-creators=1")) {
            mysql.start();
            var ds = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            var db = new JdbcTemplate(ds);
            Flyway.configure().dataSource(ds).target("22").load().migrate();
            assertThatThrownBy(() -> M8BackupProtocol.schema(db)).isInstanceOf(IllegalArgumentException.class);
            Flyway.configure().dataSource(ds).load().migrate();
            M8BackupProtocol.schema(db);
            assertThatThrownBy(() -> M8BackupDrill.deniedSqlCode(db, "SELECT * FROM nonexistent_probe_table", "insufficient-backup-privilege"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different reason");
            assertThatThrownBy(() -> M8BackupDrill.deniedSqlCode(db, "SELECT 1", "insufficient-backup-privilege"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsafe operation accepted");
            db.update("INSERT INTO auth_principals(id) VALUES(UNHEX(REPEAT('01',16)))");
            db.update("INSERT INTO auth_access_credentials(id,token_digest,audience,principal_id,expires_at,hold_party_size) "
                + "VALUES(UNHEX(REPEAT('02',16)),REPEAT('a',64),'ORIGINAL',UNHEX(REPEAT('01',16)),'2026-10-28 12:34:56.123456',2)");
            var before = M8BackupDatabase.snapshot(db);
            var encoded = JSON.valueToTree(before).path("auth_access_credentials").path("rows").get(0);
            try (var connection = ds.getConnection()) {
                M8BackupDrill.upsert(connection, "auth_access_credentials", JSON.readTree(encoded.asString()));
            }
            assertThat(M8BackupDatabase.snapshot(db)).isEqualTo(before);
            assertThat(M8BackupDatabase.invariants(db).values()).allMatch(v -> v == 0);
            // FK checks switched back on do not repair corrupted imported rows; the independent oracle must detect them.
            try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                statement.executeUpdate("UPDATE auth_access_credentials SET principal_id=UNHEX(REPEAT('03',16))");
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
            }
            assertThat(M8BackupDatabase.invariants(db).get("fk:auth_access_credentials:fk_access_credential_principal")).isEqualTo(1);
        }
    }
}
