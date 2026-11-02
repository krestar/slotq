package com.slotq.mcp;

import com.slotq.auth.domain.SystemPrincipal;
import com.slotq.events.application.*;
import com.slotq.events.persistence.JdbcEventDeliveryStore;
import com.slotq.tenancy.domain.TenantId;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;
import static com.slotq.mcp.M8CapacityEvidence.*;
import static com.slotq.mcp.M8BackupDatabase.*;

/** A populated V9 historical replay round trip on disposable copies; no HTTP or scheduler is started. */
public final class BackupReplayHistoryProbe {
    public static void main(String[] args) throws Exception {
        Path result = Path.of(args[0]).toRealPath();
        M8BackupProtocol.artifact(result, args[1]);
        var run = JSON.readTree(Files.readString(result));
        require(run.path("kind").asString().equals("EXECUTION") && run.path("complete").asBoolean(), "Missing qualified parent run");
        Path original = result.resolveSibling("database.sql");
        M8BackupProtocol.artifact(original, run.path("backupSha256").asString());
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        require(output.getParent().toRealPath().startsWith(Path.of("../build").toRealPath()), "Use a new ignored local evidence directory");
        Files.createDirectory(output);
        var acl = Files.getFileAttributeView(output, AclFileAttributeView.class);
        require(acl != null, "Requires protected Windows evidence storage");
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(output))
            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));

        Path dump = output.resolve("replay.sql");
        Map<String, Object> expected;
        String sourceUuid;
        String dumpDigest;
        try (var source = instance()) {
            source.start();
            var db = connect(source);
            importDump(source, original);
            M8BackupProtocol.schema(db);
            sourceUuid = db.queryForObject("SELECT @@server_uuid", String.class);
            var prior = db.queryForMap("SELECT BIN_TO_UUID(d.tenant_id) tenant,BIN_TO_UUID(d.event_id) event,"
                + "BIN_TO_UUID(d.registration_id) registration,d.lifetime_attempts,d.fencing_token,a.authority_epoch "
                + "FROM event_deliveries d JOIN event_registrations r ON r.registration_id=d.registration_id "
                + "JOIN event_transport_assignments a ON a.registration_id=d.registration_id "
                + "WHERE r.consumer_id='waitlist.promotion' AND a.transport='DB_DIRECT' AND d.state='DEAD'");
            var key = new DeliveryKey(new TenantId(UUID.fromString((String)prior.get("tenant"))),
                new EventId(UUID.fromString((String)prior.get("event"))), UUID.fromString((String)prior.get("registration")));
            var policy = new DeliveryPolicy(5, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(5), 2,
                List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2)));
            var store = new JdbcEventDeliveryStore(db);
            var transactions = new DeliveryTransactions(new DataSourceTransactionManager(db.getDataSource()), store, policy);
            var scope = new DeliveryExecutionScope("waitlist.promotion", "DB_DIRECT", ((Number)prior.get("authority_epoch")).longValue());
            new EventReplayService(store, transactions, scope).replay(SystemPrincipal.INSTANCE, key, "synthetic archived replay fixture");
            require(db.queryForObject("SELECT COUNT(*) FROM event_replay_audit WHERE tenant_id=UUID_TO_BIN(?) AND event_id=UUID_TO_BIN(?) "
                + "AND registration_id=UUID_TO_BIN(?) AND prior_state='DEAD' AND recovery_origin='TRUSTED_INTERNAL' "
                + "AND prior_fencing_token=? AND lifetime_attempts=?", Long.class, prior.get("tenant"), prior.get("event"),
                prior.get("registration"), prior.get("fencing_token"), prior.get("lifetime_attempts")) == 1, "Missing original replay identity");
            db.execute("SET GLOBAL super_read_only=ON");
            expected = snapshot(db);
            var saved = source.execInContainer("sh", "-c", "umask 077; mysqldump --defaults-extra-file=/tmp/replay.cnf "
                + "--single-transaction --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF --hex-blob --routines --events "
                + "--triggers --skip-add-drop-table --skip-add-locks m8_capacity > /tmp/replay.sql");
            require(saved.getExitCode() == 0, "Supplemental dump failed");
            source.copyFileFromContainer("/tmp/replay.sql", dump.toString());
            dumpDigest = digest(dump);
        }
        try (var target = instance()) {
            target.start();
            var db = connect(target);
            String targetUuid = db.queryForObject("SELECT @@server_uuid", String.class);
            require(!sourceUuid.equals(targetUuid) && count(db, "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()") == 0,
                "Target is not an independent empty MySQL");
            M8BackupProtocol.artifact(dump, dumpDigest);
            importDump(target, dump);
            var actual = snapshot(db);
            require(expected.equals(actual), "Historical replay restore differs from source");
            var oracle = invariants(db);
            require(oracle.values().stream().allMatch(value -> value == 0), "Restored invariant failed");
            write(output.resolve("replay-row.json"), actual.get("event_replay_audit"));
            write(output.resolve("result.json"), Map.of("kind", "SUPPLEMENTAL_REPLAY_ROUND_TRIP", "parentResultDigest", args[1],
                "dumpDigest", dumpDigest, "sourceServerUuid", sourceUuid, "targetServerUuid", targetUuid, "tables", actual.size(),
                "oracle", oracle, "complete", true));
        }
    }

    private static MySQLContainer instance() {
        return new MySQLContainer("mysql:8.4").withDatabaseName("m8_capacity").withEnv("MYSQL_ROOT_HOST", "%")
            .withCommand("--log-bin-trust-function-creators=1");
    }

    private static JdbcTemplate connect(MySQLContainer mysql) {
        String options = "[client]\nuser=root\npassword=" + mysql.getPassword() + "\n";
        mysql.copyFileToContainer(Transferable.of(options.getBytes(StandardCharsets.UTF_8), 0600), "/tmp/replay.cnf");
        return new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword()));
    }

    private static void importDump(MySQLContainer mysql, Path dump) throws Exception {
        mysql.copyFileToContainer(MountableFile.forHostPath(dump, 0600), "/tmp/restore.sql");
        require(mysql.execInContainer("sh", "-c", "mysql --defaults-extra-file=/tmp/replay.cnf m8_capacity < /tmp/restore.sql").getExitCode() == 0,
            "Supplemental import failed");
    }
}
