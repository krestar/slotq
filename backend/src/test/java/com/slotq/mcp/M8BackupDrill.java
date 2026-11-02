package com.slotq.mcp;

import com.slotq.auth.access.*;
import com.slotq.auth.domain.*;
import com.slotq.auth.persistence.ActorAccessService;
import com.slotq.booking.application.ReservationUseCase;
import com.slotq.booking.application.ReservationCommand;
import com.slotq.booking.domain.*;
import com.slotq.knowledge.domain.Corpus;
import com.slotq.integration.mcp.product.HoldApprovals;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import static com.slotq.mcp.M8CapacityEvidence.*;
import static com.slotq.mcp.M8BackupDatabase.*;
import static com.slotq.mcp.RepresentativeFlowIntegrationTests.bytes;
import static org.awaitility.Awaitility.await;

/** Disposable, opt-in restoration drill. Never points at an operator-supplied database. */
final class M8BackupDrill {
    private static final List<String> SECURITY_TABLES = List.of("auth_principals", "tenant_memberships", "venue_grants",
        "auth_access_credentials", "auth_access_delegations", "knowledge_documents", "knowledge_versions",
        "operations_operators", "operations_credentials", "operations_grants");
    private final ConfigurableApplicationContext application;
    private final RepresentativeFlowIntegrationTests f;
    private final MySQLContainer source;
    private final Path output;
    private final JdbcTemplate sourceDb;
    private final List<Map<String, Object>> timeline = new ArrayList<>();
    private final Map<String, String> checks = new TreeMap<>();
    private final String runId = UUID.randomUUID().toString();
    private final boolean pilot = System.getProperty("slotq.capacity.backupPlan", "").equals("pilot");
    private JsonNode frozen;
    private Instant recoveryPoint;
    private Instant sourceCutoff;
    private long recoveryStarted;
    private Path dump;
    private String dumpHash;
    private String authorityDigest;
    private String sourceServerUuid;
    private UUID knownReservation;
    private UUID safeSlot;
    private UUID revokedPrincipal;
    private UUID managerPrincipal;
    private Corpus.VersionReference withdrawn;
    private String freshMcp;
    private String freshOriginal;
    private Map<String, Object> freshHoldArguments;
    private Map<String, Object> replayArguments;
    private UUID replayIntent;
    private String staleRead;
    private UUID postBackupReservation;
    private String revokedMcp;
    private String expiredMcp;
    private Instant approvalExpiry;
    private String removedManagerMcp;
    private Map<String, Object> revokedIntentArguments;
    private Map<String, Object> expiredIntentArguments;
    private final List<UUID> backlogReservations = new ArrayList<>();
    private Corpus.VersionReference changedPublication;
    private Corpus.VersionReference survivor;
    private final Map<UUID, UUID> expectedOffers = new LinkedHashMap<>();
    private String oldSession;
    private final String scrapeToken = UUID.randomUUID().toString() + UUID.randomUUID();
    private Long verifiedRtoMillis;

    M8BackupDrill(ConfigurableApplicationContext application, RepresentativeFlowIntegrationTests fixture,
            MySQLContainer source, Path output) throws Exception {
        this.application = application;
        this.f = fixture;
        this.source = source;
        this.output = output.resolve("backup");
        Files.createDirectory(this.output);
        protect(this.output);
        sourceDb = db(source);
        if (!pilot) frozen = M8BackupProtocol.plan(Path.of(System.getProperty("slotq.capacity.backupPlan")),
            System.getProperty("slotq.capacity.backupDigest"), Instant.now(),
            Integer.getInteger("slotq.capacity.size", 16), Integer.getInteger("slotq.capacity.rate", 20));
    }

    static void preflight() throws Exception {
        if (!Boolean.getBoolean("slotq.capacity.backup")) return;
        require(M8RecoveryDrill.enabled(), "Backup requires the #182 operator/audit fixture and support workload");
        require(Integer.getInteger("slotq.capacity.pool", 4) == 4 && Long.getLong("slotq.capacity.seed", 18101L) == 18101L,
            "Backup requires the measured pool-4 seed-18101 support configuration");
        for (String key : List.of("previousBinary", "candidateBinary")) {
            require(Files.isRegularFile(Path.of(System.getProperty("slotq.capacity." + key, ""))), "Missing actual " + key);
        }
        if (!System.getProperty("slotq.capacity.backupPlan", "").equals("pilot")) {
            M8BackupProtocol.plan(Path.of(System.getProperty("slotq.capacity.backupPlan", "")),
                System.getProperty("slotq.capacity.backupDigest", ""), Instant.now(),
                Integer.getInteger("slotq.capacity.size", 16), Integer.getInteger("slotq.capacity.rate", 20));
        }
    }

    void run() throws Exception {
        boolean complete = false;
        sourceServerUuid = sourceDb.queryForObject("SELECT @@server_uuid", String.class);
        record("started", Map.of("sourceContainer", source.getContainerId(), "sourceServerUuid", sourceServerUuid,
            "supportSize", Integer.getInteger("slotq.capacity.size", 16)));
        try {
            prepare();
            application.getBean(ScheduledAnnotationBeanPostProcessor.class).destroy();
            for (UUID reservation : backlogReservations) {
                f.reservations.transition(f.venue, new ReservationId(reservation),
                    new AuthenticatedPrincipal(new PrincipalId(f.customer.principal())), ReservationCommand.CANCEL);
            }
            application.close();
            require(!application.isActive(), "Original writer did not stop");
            record("original-writers-stopped", Map.of("httpClosed", true, "schedulersClosed", true));
            backup();
            postBackupChanges();
            source.stop();
            checks.put("sourceStopped", source.isRunning() ? "FAIL" : "PASS");
            recoveryStarted = System.nanoTime();
            record("recovery-start", Map.of("definition", "before provisioning an empty independent MySQL through verified service recovery"));
            try (var target = new MySQLContainer("mysql:8.4").withDatabaseName("m8_capacity")
                    .withEnv("MYSQL_ROOT_HOST", "%").withCommand("--log-bin-trust-function-creators=1")) {
                target.start();
                require(!target.getContainerId().equals(source.getContainerId()), "Restore target aliases source");
                var restored = db(target);
                require(!sourceServerUuid.equals(restored.queryForObject("SELECT @@server_uuid", String.class)), "Restore reused the source server");
                require(count(restored, "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()") == 0,
                    "Restore target is not empty");
                checks.put("emptyTarget", "PASS");
                restore(target);
                var actual = snapshot(restored);
                write(output.resolve("restored-before-reconcile.json"), actual);
                require(JSON.readTree(Files.readString(output.resolve("snapshot.json"))).equals(JSON.valueToTree(actual)),
                    "Restored cells/identities differ from recovery point");
                checks.put("sameRecoveryPoint", "PASS");
                require(count(restored, "SELECT COUNT(*) FROM reservations WHERE id=UNHEX('" + hex(postBackupReservation) + "')") == 0,
                    "Post-backup committed reservation unexpectedly restored");
                record("actual-data-loss", Map.of("postBackupCommittedReservation", postBackupReservation.toString(),
                    "absentAfterRestore", true, "callerOutcome", "UNKNOWN is not reclassified or replayed"));
                schema(restored);
                oracle(restored, "before-security");
                security(restored);
                negatives(target, restored);
                binaryEvidence();
                currentCredentials(target);
                checks.put("credentials", "PASS");
                var configuration = deploymentProperties(target);
                var contract = publicConfiguration(configuration);
                String origin = "https://localhost:" + configuration.get("server.port");
                require(origin.equals(configuration.get("slotq.mcp.origin")) && origin.equals(configuration.get("slotq.mcp.product-origin")),
                    "MCP and Product must use the current isolated loopback origin");
                contract.put("slotq.mcp.origin", "https://localhost:<loopback-test-port>");
                contract.put("slotq.mcp.product-origin", "https://localhost:<loopback-test-port>");
                contract.put("spring.datasource.url", "jdbc:mysql://<isolated-target>/m8_capacity");
                contract.put("server.port", "<loopback-test-port>");
                contract.put("server.ssl.key-store", "<per-run-loopback-keystore>");
                Path configEvidence = output.resolve("deployment-contract.json");
                write(configEvidence, contract);
                if (!pilot) M8BackupProtocol.artifact(configEvidence, frozen.path("deploymentConfigSha256").asString());
                checks.put("configuration", "PASS");
                String admitted = M8BackupProtocol.resumeVerdict(checks, rpo(), elapsed(), rpoLimit(), rtoLimit());
                require(admitted.equals("PASS"), "Resume admission " + admitted);
                record("resume-admitted", Map.of("checks", Map.copyOf(checks), "rpoMillis", rpo(), "elapsedMillis", elapsed()));
                resume(restored, "candidateBinary", configuration);
                resume(restored, "previousBinary", configuration);
                complete = M8BackupProtocol.resumeVerdict(checks, rpo(), verifiedRtoMillis, rpoLimit(), rtoLimit()).equals("PASS");
                require(complete, "Final RPO/RTO did not satisfy frozen criteria");
            }
        } finally {
            var result = new LinkedHashMap<String, Object>();
            result.put("schemaVersion", "slotq-m8-backup-execution/v1");
            result.put("kind", pilot ? "PILOT_EXECUTION" : "EXECUTION");
            result.put("runId", runId);
            result.put("complete", complete);
            result.put("endedAt", Instant.now().toString());
            result.put("timeline", timeline);
            result.put("checks", checks);
            result.put("rpoMillis", recoveryPoint == null || sourceCutoff == null ? null : rpo());
            result.put("rtoMillis", verifiedRtoMillis != null ? verifiedRtoMillis : recoveryStarted == 0 ? null : elapsed());
            result.put("planDigest", pilot ? "PILOT_NOT_QUALIFICATION" : System.getProperty("slotq.capacity.backupDigest"));
            result.put("backupSha256", dumpHash);
            result.put("sourceManifestSha256", digest(output.getParent().resolve("manifest.json")));
            result.put("supportRawSha256", digest(output.getParent().resolve("raw.json")));
            result.put("recoveryRawSha256", digest(output.getParent().resolve("recovery.json")));
            var artifacts = new TreeMap<String, String>();
            try (var files = Files.list(output)) {
                for (var file : files.filter(Files::isRegularFile).toList()) artifacts.put(file.getFileName().toString(), digest(file));
            }
            result.put("artifactDigests", artifacts);
            result.put("limitations", List.of("single local Docker host, logical dump, two declared #181 support points only",
                "offline security authority requires preserved current source; unavailable authority forbids resume",
                "process-local Run/quota/session and external provider results are not restored"));
            write(output.resolve("result.json"), result);
        }
    }

    private void prepare() throws Exception {
        UUID replaySlot = newSlot("Pre-backup idempotency target");
        var review = f.approvals.prepare(f.customer.original().value(), f.customer.delegation().delegationId(), replaySlot, 2);
        replayIntent = review.intentId();
        var approval = f.approvals.approve(f.customer.original().value(), replayIntent);
        var prepared = f.engine.call(f.engine.context(f.access.authenticateMcp(f.customer.delegation().value()), UUID.randomUUID()),
            "reservation.hold", holdArguments(review, approval));
        var created = JSON.valueToTree(prepared.structuredContent());
        require(created.path("outcome").asString().equals("succeeded") && created.path("productStatus").asInt() == 201,
            "Pre-backup explicit MCP HOLD did not complete");
        var actor = new AuthenticatedPrincipal(new PrincipalId(f.customer.principal()));
        knownReservation = UUID.fromString(created.path("data").path("id").asString());
        f.reservations.transition(f.venue, new ReservationId(knownReservation), actor, ReservationCommand.CONFIRM);
        for (int i = 0; i < 3; i++) {
            // FIFO is scoped to Venue/time/party demand, not to a specific resource.
            UUID target = newSlot("Backup backlog " + i, String.format(Locale.ROOT, "%02d:30:00", 9 + i));
            var held = f.reservations.createHold(new ReservationUseCase.CreateHold(f.venue, new SlotInventoryId(target), actor, 2)).reservation();
            f.reservations.transition(f.venue, held.id(), actor, ReservationCommand.CONFIRM);
            var waiting = f.identity("CUSTOMER", f.tenant, f.venue);
            var entry = application.getBean(com.slotq.waitlist.application.WaitlistUseCase.class).register(
                new com.slotq.waitlist.application.WaitlistUseCase.CreateRegistration(f.venue, new SlotInventoryId(target), 2,
                    new com.slotq.waitlist.application.WaitlistRegistrationKey(UUID.randomUUID()),
                    new AuthenticatedPrincipal(new PrincipalId(waiting.principal()))));
            expectedOffers.put(target, entry.entry().id());
            backlogReservations.add(held.id().value());
        }
        UUID deadTarget = newSlot("Backup preserves DEAD");
        M8RecoveryDrill.backupFailureTarget(deadTarget);
        try {
            var held = f.reservations.createHold(new ReservationUseCase.CreateHold(f.venue, new SlotInventoryId(deadTarget), actor, 2)).reservation();
            f.reservations.transition(f.venue, held.id(), actor, ReservationCommand.CANCEL);
            await().atMost(Duration.ofSeconds(45)).until(() -> count(sourceDb, "SELECT COUNT(*) FROM event_deliveries WHERE state='DEAD'") > 0);
        } finally { M8RecoveryDrill.backupFailureTarget(null); }
        safeSlot = newSlot("Recovery explicit new HOLD");
        var rejected = f.identity("CUSTOMER", f.tenant, f.venue);
        revokedPrincipal = rejected.principal();
        revokedMcp = rejected.delegation().value();
        var manager = f.identity("MANAGER", f.tenant, f.venue);
        managerPrincipal = manager.principal();
        removedManagerMcp = manager.delegation().value();
        withdrawn = f.publish(Corpus.Visibility.VENUE_PUBLIC, "backup-withdrawal-sentinel synthetic only");
        changedPublication = f.publish(Corpus.Visibility.VENUE_PUBLIC, "backup-old-version-sentinel synthetic only");
        survivor = f.publish(Corpus.Visibility.VENUE_PUBLIC, "backup-survivor-marker synthetic only");
        oldSession = initialize(f.customer.delegation().value()).headers().firstValue("Mcp-Session-Id").orElseThrow();
        var intent = f.approvals.prepare(rejected.original().value(), rejected.delegation().delegationId(), safeSlot, 2);
        var confirmation = f.approvals.approve(rejected.original().value(), intent.intentId());
        revokedIntentArguments = Map.of("intentId", intent.intentId().toString(), "confirmationId", confirmation.id().toString(),
            "slotInventoryId", safeSlot.toString(), "partySize", 2, "idempotencyKey", intent.idempotencyKey());
        staleRead = f.access.issueProduct(rejected.delegation().delegationId(), ProductOperation.RESERVATION_GET,
            knownReservation, Instant.now().plusSeconds(240)).value();
        var shortOriginal = f.access.provisionOriginal(new PrincipalId(f.customer.principal()), Instant.now().plusSeconds(8));
        var shortDelegation = f.delegate(shortOriginal, f.customer.principal(), "CUSTOMER", f.venue);
        expiredMcp = shortDelegation.value();
        var expiringIntent = f.approvals.prepare(shortOriginal.value(), shortDelegation.delegationId(), safeSlot, 2);
        var expiringConfirmation = f.approvals.approve(shortOriginal.value(), expiringIntent.intentId());
        approvalExpiry = expiringConfirmation.expiresAt();
        expiredIntentArguments = Map.of("intentId", expiringIntent.intentId().toString(), "confirmationId", expiringConfirmation.id().toString(),
            "slotInventoryId", safeSlot.toString(), "partySize", 2, "idempotencyKey", expiringIntent.idempotencyKey());
        write(output.resolve("security-meta.json"), Map.of("revokedPrincipal", revokedPrincipal, "managerPrincipal", managerPrincipal,
            "withdrawnDocument", withdrawn.document().documentId(), "intent", intent.intentId(),
            "dispatchedIntents", count(sourceDb, "SELECT COUNT(*) FROM mcp_hold_intents WHERE first_dispatch_at IS NOT NULL")));
    }

    private void backup() throws Exception {
        // A disconnected unfinished transaction must leave neither the parent nor its dependent row.
        UUID unfinished = UUID.randomUUID();
        try (var transaction = DriverManager.getConnection(source.getJdbcUrl(), "root", source.getPassword())) {
            transaction.setAutoCommit(false);
            try (var insert = transaction.prepareStatement("INSERT INTO auth_principals(id) VALUES(?)")) {
                insert.setBytes(1, bytes(unfinished)); insert.executeUpdate();
            }
            try (var insert = transaction.prepareStatement("INSERT INTO tenant_memberships(tenant_id,principal_id,role) VALUES(?,?,'OWNER')")) {
                insert.setBytes(1, bytes(f.tenant)); insert.setBytes(2, bytes(unfinished)); insert.executeUpdate();
            }
            require(count(sourceDb, "SELECT COUNT(*) FROM auth_principals WHERE id=UNHEX('" + hex(unfinished) + "')") == 0,
                "Uncommitted transaction visible to independent reader");
        }
        require(count(sourceDb, "SELECT COUNT(*) FROM auth_principals WHERE id=UNHEX('" + hex(unfinished) + "')") == 0,
            "Disconnected transaction partially committed");
        sourceDb.execute("SET GLOBAL super_read_only=ON");
        denySql(sourceDb, "INSERT INTO auth_principals(id) VALUES(UUID_TO_BIN(UUID()))", "quiesced-write");
        denySql(sourceDb, "CREATE TABLE forbidden_backup_ddl(id INT)", "quiesced-ddl");
        require(count(sourceDb, "SELECT COUNT(*) FROM information_schema.innodb_trx") == 0, "Open writer transaction at backup boundary");
        require(unfinished(sourceDb, "waitlist.promotion") > 2, "Backup lacks more than one delivery batch of unfinished work");
        for (UUID entry : expectedOffers.values()) {
            require(sourceDb.queryForObject("SELECT COUNT(*) FROM waitlist_entries WHERE id=? AND state='WAITING'", Long.class, bytes(entry)) == 1,
                "Expected FIFO entry was already served before backup");
        }
        recoveryPoint = Instant.now();
        require(approvalExpiry.isAfter(recoveryPoint), "Expiry probe was already expired before the backup");
        write(output.resolve("snapshot.json"), snapshot(sourceDb));
        configureClient(source);
        var execution = source.execInContainer("sh", "-c", "umask 077; mysqldump --defaults-extra-file=/tmp/slotq-client.cnf "
            + "--single-transaction --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF --hex-blob "
            + "--routines --events --triggers --skip-add-drop-table --skip-add-locks m8_capacity > /tmp/slotq-backup.sql");
        require(execution.getExitCode() == 0, "Backup command failed (secret-bearing output suppressed)");
        dump = output.resolve("database.sql");
        source.copyFileFromContainer("/tmp/slotq-backup.sql", dump.toString());
        dumpHash = digest(dump);
        checks.put("backup", "PASS");
        write(output.resolve("backup-identity.json"), Map.of("runId", runId, "recoveryPoint", recoveryPoint.toString(),
            "dumpSha256", dumpHash, "sourceServerUuid", sourceDb.queryForObject("SELECT @@server_uuid", String.class),
            "unfinishedTransaction", unfinished.toString(), "allOrNothing", "ROLLBACK",
            "method", "QUIESCED_LOGICAL_DUMP", "createdAt", Instant.now().toString()));
        record("backup-complete", Map.of("recoveryPoint", recoveryPoint.toString(), "sha256", dumpHash));
        sourceDb.execute("SET GLOBAL super_read_only=OFF");
        sourceDb.execute("SET GLOBAL read_only=OFF");
    }

    private void postBackupChanges() throws Exception {
        // Only the isolated source is running here; no restored writer exists yet.
        try (var current = M8CapacityRunner.startContext(source, 4)) {
            current.getAutowireCapableBeanFactory().autowireBean(f);
            f.access = current.getBean(ActorAccessService.class);
            var revoked = sourceDb.queryForList("SELECT id FROM auth_access_credentials WHERE principal_id=?", byte[].class, bytes(revokedPrincipal));
            for (byte[] credential : revoked) f.access.revokeCredential(uuid(credential));
            sourceDb.update("DELETE FROM venue_grants WHERE principal_id=?", bytes(managerPrincipal));
            sourceDb.update("UPDATE tenant_memberships SET role='STAFF' WHERE principal_id=?", bytes(managerPrincipal));
            var metadata = f.corpus.inspect(f.owner.original().value(), f.venue, withdrawn.document().documentId());
            f.corpus.withdraw(f.owner.original().value(), f.venue, withdrawn.document().documentId(), metadata.revision());
            var old = f.corpus.inspect(f.owner.original().value(), f.venue, changedPublication.document().documentId());
            String updatedContent = "backup-operator-version-sentinel synthetic only";
            var updated = f.corpus.stage(f.owner.original().value(), f.venue, new Corpus.VersionInput(old.key().documentId(), UUID.randomUUID(),
                new Corpus.Source(UUID.randomUUID(), "seed:backup-version-change", "Synthetic updated guide"),
                Corpus.Visibility.VENUE_OPERATOR, updatedContent, Corpus.digest(updatedContent)), old.revision());
            f.corpus.validate(f.owner.original().value(), updated.reference());
            f.corpus.publish(f.owner.original().value(), updated.reference());
            var replacement = f.access.provisionOriginal(new PrincipalId(f.customer.principal()), Instant.now().plusSeconds(600));
            freshOriginal = replacement.value();
            var delegation = f.delegate(replacement, f.customer.principal(), "CUSTOMER", f.venue);
            freshMcp = delegation.value();
            var lostSlot = newSlot("Committed after backup, intentionally lost");
            postBackupReservation = f.reservations.createHold(new ReservationUseCase.CreateHold(f.venue,
                new SlotInventoryId(lostSlot), new AuthenticatedPrincipal(new PrincipalId(f.customer.principal())), 2)).reservation().id().value();
            record("post-backup-commit", Map.of("reservation", postBackupReservation.toString()));
        }
        sourceCutoff = Instant.now();
        sourceDb.execute("SET GLOBAL super_read_only=ON");
        var authority = snapshot(sourceDb);
        var security = new TreeMap<String, Object>();
        SECURITY_TABLES.forEach(table -> security.put(table, authority.get(table)));
        write(output.resolve("security-authority.json"), security);
        authorityDigest = digest(output.resolve("security-authority.json"));
        write(output.resolve("security-authority-identity.json"), Map.of("runId", runId,
            "sourceCutoff", sourceCutoff.toString(), "recoveryPoint", recoveryPoint.toString(),
            "sha256", authorityDigest, "sourceServerUuid", sourceServerUuid, "trustedOrigin", "isolated original DB after explicit security changes and writer shutdown"));
        checks.put("securityAuthority", "PASS");
    }

    private void restore(MySQLContainer target) throws Exception {
        var identity = JSON.readTree(Files.readString(output.resolve("backup-identity.json")));
        require(identity.path("runId").asString().equals(runId) && identity.path("recoveryPoint").asString().equals(recoveryPoint.toString())
            && identity.path("sourceServerUuid").asString().equals(sourceServerUuid) && identity.path("dumpSha256").asString().equals(dumpHash),
            "Wrong source, recovery point or dump identity");
        M8BackupProtocol.artifact(dump, dumpHash);
        configureClient(target);
        target.copyFileToContainer(MountableFile.forHostPath(dump, 0600), "/tmp/slotq-backup.sql");
        var imported = target.execInContainer("sh", "-c", "mysql --defaults-extra-file=/tmp/slotq-client.cnf m8_capacity < /tmp/slotq-backup.sql");
        require(imported.getExitCode() == 0, "Restore failed (secret-bearing output suppressed)");
        record("restored", Map.of("targetContainer", target.getContainerId(), "serverUuid",
            db(target).queryForObject("SELECT @@server_uuid", String.class), "dumpSha256", dumpHash));
    }

    private void security(JdbcTemplate restored) throws Exception {
        require(!approvalExpiry.isAfter(Instant.now()), "Natural post-backup credential/approval expiry was not observed");
        record("post-backup-expiry", Map.of("expiry", approvalExpiry.toString(), "validAtRecoveryPoint", true, "expiredBeforeResume", true));
        require(count(restored, "SELECT COUNT(*) FROM auth_access_credentials WHERE principal_id=UNHEX('" + hex(revokedPrincipal)
            + "') AND revoked_at IS NULL") > 0, "Stale credential resurrection fixture absent");
        require(count(restored, "SELECT COUNT(*) FROM knowledge_documents WHERE document_id=UNHEX('" + hex(withdrawn.document().documentId())
            + "') AND state='ACTIVE'") == 1, "Stale publication resurrection fixture absent");
        record("stale-snapshot-risk-observed", Map.of("credentialWouldBeActive", true, "withdrawnPublicationWouldBeActive", true,
            "serviceStillStopped", true));
        var identity = JSON.readTree(Files.readString(output.resolve("security-authority-identity.json")));
        require(identity.path("sourceServerUuid").asString().equals(sourceServerUuid) && identity.path("sha256").asString().equals(authorityDigest),
            "Security inventory differs from the pinned original source authority");
        M8BackupProtocol.artifact(output.resolve("security-authority.json"), authorityDigest);
        M8BackupProtocol.securityIdentity(identity, runId, recoveryPoint, sourceCutoff);
        var authority = JSON.readTree(Files.readString(output.resolve("security-authority.json")));
        try (var connection = restored.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try {
                // This fixture has exactly one removed Venue grant. The authority inventory proves its absence.
                try (var statement = connection.prepareStatement("DELETE FROM venue_grants WHERE principal_id=?")) {
                    statement.setBytes(1, bytes(managerPrincipal)); statement.executeUpdate();
                }
                for (String table : List.of("auth_principals", "tenant_memberships", "auth_access_credentials", "auth_access_delegations",
                        "venue_grants", "knowledge_versions", "knowledge_documents", "operations_operators", "operations_credentials", "operations_grants")) {
                    // Credential/delegation cycles are restored in two passes; ORIGINAL rows first.
                    for (var encoded : authority.path(table).path("rows")) {
                        var row = JSON.readTree(encoded.asString());
                        if (table.equals("auth_access_credentials") && !decode(row.path("audience")).equals("ORIGINAL")) continue;
                        upsert(connection, table, row);
                    }
                }
                for (var encoded : authority.path("auth_access_credentials").path("rows")) {
                    var row = JSON.readTree(encoded.asString());
                    if (!decode(row.path("audience")).equals("ORIGINAL")) upsert(connection, "auth_access_credentials", row);
                }
                connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
        }
        var reconciled = snapshot(restored);
        write(output.resolve("security-reconciled-snapshot.json"), reconciled);
        for (String table : SECURITY_TABLES) require(authority.path(table).equals(JSON.valueToTree(reconciled.get(table))),
            "Current security authority mismatch: " + table);
        checks.put("securityReconciled", "PASS");
        record("security-reconciled", Map.of("authoritySha256", identity.path("sha256").asString(),
            "revokedCredentialCount", count(restored, "SELECT COUNT(*) FROM auth_access_credentials WHERE revoked_at IS NOT NULL"),
            "publicationWithdrawal", true, "currentRoleAndVenue", true, "newCredentialProvidedSeparatelyInMemory", true));
    }

    static void upsert(Connection connection, String table, JsonNode row) throws Exception {
        require(SECURITY_TABLES.contains(table), "Unexpected security table");
        var columns = new ArrayList<String>();
        row.propertyNames().forEach(columns::add);
        require(columns.stream().allMatch(name -> name.matches("[a-z_]+")), "Unexpected security column");
        var types = new TreeMap<String, Integer>();
        try (var query = connection.createStatement(); var empty = query.executeQuery("SELECT * FROM `" + table + "` WHERE 1=0")) {
            var metadata = empty.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) types.put(metadata.getColumnName(i), metadata.getColumnType(i));
        }
        require(types.keySet().equals(new TreeSet<>(columns)), "Security inventory does not match schema");
        String names = String.join(",", columns.stream().map(c -> "`" + c + "`").toList());
        String values = String.join(",", Collections.nCopies(columns.size(), "?"));
        String updates = String.join(",", columns.stream().map(c -> "`" + c + "`=VALUES(`" + c + "`)").toList());
        try (var statement = connection.prepareStatement("INSERT INTO `" + table + "` (" + names + ") VALUES (" + values
                + ") ON DUPLICATE KEY UPDATE " + updates)) {
            for (int i = 0; i < columns.size(); i++) {
                var value = row.path(columns.get(i));
                int type = types.get(columns.get(i));
                if (value.isNull()) statement.setNull(i + 1, type);
                else if (Set.of(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB).contains(type)) {
                    statement.setBytes(i + 1, HexFormat.of().parseHex(value.asString()));
                } else {
                    // MySQL interprets a binary literal in numeric context as its byte value ("2" becomes 50).
                    // Preserve JDBC text for numeric, temporal and text columns; only actual binary columns use bytes.
                    statement.setString(i + 1, decode(value));
                }
            }
            statement.executeUpdate();
        }
    }

    private void schema(JdbcTemplate restored) {
        M8BackupProtocol.schema(restored);
        checks.put("schema", "PASS");
    }

    private void oracle(JdbcTemplate restored, String phase) throws Exception {
        var oracle = invariants(restored);
        write(output.resolve("oracle-" + phase + ".json"), oracle);
        require(oracle.values().stream().allMatch(v -> v == 0), "Independent database oracle failed: " + phase);
        checks.put("databaseOracle", "PASS");
    }

    private void negatives(MySQLContainer target, JdbcTemplate restored) throws Exception {
        denySql(restored, "UPDATE operations_recovery_audit SET recorded_at=recorded_at", "append-only-update");
        denySql(restored, "DELETE FROM operations_recovery_audit", "append-only-delete");
        String restricted = "backup_denied_" + UUID.randomUUID().toString().replace("-", "");
        restored.execute("CREATE USER 'backup_denied'@'%' IDENTIFIED BY '" + restricted + "'");
        // Permit selection of this database; otherwise MySQL denies USE before testing each statement.
        restored.execute("GRANT SELECT ON m8_capacity.flyway_schema_history TO 'backup_denied'@'%'");
        var denied = new JdbcTemplate(new DriverManagerDataSource(target.getJdbcUrl(), "backup_denied", restricted));
        denySql(denied, "SELECT * FROM reservations", "insufficient-backup-privilege");
        denySql(denied, "INSERT INTO auth_principals(id) VALUES(UUID_TO_BIN(UUID()))", "insufficient-restore-privilege");
        denySql(denied, "UPDATE auth_access_credentials SET revoked_at=UTC_TIMESTAMP(6)", "re-revocation-permission-denied");
        var missing = new JdbcTemplate(new DriverManagerDataSource(target.getJdbcUrl(), "missing_backup_user", ""));
        denySql(missing, "SELECT 1", "missing-credential");
        Path corrupt = output.resolve("corrupt-backup.fixture");
        Files.writeString(corrupt, "Explicit corrupted backup fixture; never executed as SQL");
        reject(() -> M8BackupProtocol.artifact(corrupt, dumpHash), "corrupt-checksum");
        reject(() -> M8BackupProtocol.artifact(output.resolve("missing-backup.sql"), dumpHash), "missing-backup");
        var beforeNegative = snapshot(restored);
        try (var connection = restored.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            var isolated = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
            try {
                int changed = isolated.update("UPDATE waitlist_promotion_receipts SET outcome=NULL,offer_id=NULL,entry_id=NULL,reservation_id=NULL LIMIT 1");
                require(changed == 1 && invariants(isolated).get("partialReceipt") == 1, "Partial receipt was not detected");
                record("partial-receipt-negative", Map.of("detected", true, "transaction", "rolled back before resume"));
            } finally { connection.rollback(); }
            try {
                int changed = isolated.update("UPDATE event_deliveries d JOIN event_registrations r ON r.registration_id=d.registration_id "
                    + "JOIN waitlist_promotion_receipts p ON p.tenant_id=d.tenant_id AND p.event_id=d.event_id "
                    + "SET d.state='DEAD' WHERE r.consumer_id='waitlist.promotion' AND d.state='DONE'");
                require(changed > 0 && invariants(isolated).get("receiptWithoutDone") > 0, "Receipt/DONE mismatch was not detected");
                record("receipt-done-negative", Map.of("detected", true, "transaction", "rolled back before resume"));
            } finally { connection.rollback(); }
            try {
                int changed = isolated.update("UPDATE waitlist_promotion_receipts SET resource_id=UUID_TO_BIN(UUID()) WHERE outcome='PROMOTED' LIMIT 1");
                require(changed == 1 && invariants(isolated).get("receiptEffect") == 1, "Mismatched receipt/business effect was not detected");
                record("receipt-effect-negative", Map.of("detected", true, "transaction", "rolled back before resume"));
            } finally { connection.rollback(); }
        }
        require(snapshot(restored).equals(beforeNegative), "Negative oracle probe changed restored state");
        for (String gate : M8BackupProtocol.REQUIRED) {
            var absent = new TreeMap<>(checks);
            absent.remove(gate);
            require(!M8BackupProtocol.resumeVerdict(absent, rpo(), elapsed(), rpoLimit(), rtoLimit()).equals("PASS"), "Missing gate admitted");
            var failed = new TreeMap<>(checks);
            failed.put(gate, "FAIL");
            require(M8BackupProtocol.resumeVerdict(failed, rpo(), elapsed(), rpoLimit(), rtoLimit()).equals("FAIL"), "Failed gate admitted");
        }
        record("negative-resume-gates", Map.of("eachRequiredMissingOrFailed", M8BackupProtocol.REQUIRED,
            "kind", "DECISION_REGRESSION_NOT_DATABASE_FAULT"));
    }

    private void binaryEvidence() throws Exception {
        var expected = new TreeMap<String, String>();
        try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                expected.put(file.getFileName().toString(), hash(Files.readString(file).replace("\r\n", "\n")));
            }
        }
        for (String key : List.of("previousBinary", "candidateBinary")) {
            Path binary = Path.of(System.getProperty("slotq.capacity." + key));
            if (!pilot) M8BackupProtocol.artifact(binary, frozen.path(key).path("sha256").asString());
            var actual = new TreeMap<String, String>();
            var raw = new TreeMap<String, String>();
            try (var jar = new java.util.jar.JarFile(binary.toFile())) {
                for (var entry : Collections.list(jar.entries())) {
                    String prefix = "BOOT-INF/classes/db/migration/";
                    if (entry.getName().startsWith(prefix) && entry.getName().endsWith(".sql")) {
                        try (var bytes = jar.getInputStream(entry)) {
                            byte[] content = bytes.readAllBytes();
                            String name = entry.getName().substring(prefix.length());
                            raw.put(name, HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content)));
                            actual.put(name, hash(new String(content, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n")));
                        }
                    }
                }
            }
            require(actual.equals(expected), "Unreviewed binary/schema migration combination: " + key);
            write(output.resolve(key + "-identity.json"), Map.of("jarSha256", digest(binary), "rawMigrationDigests", raw,
                "lfMigrationDigests", actual, "compatibility", "same complete V1-V23 SQL; only CRLF/LF checkout representation normalized for comparison; actual Flyway validation/startup remains mandatory"));
        }
        checks.put("binary", "PASS");
    }

    private void reject(Throwing action, String scenario) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "Invalid input was admitted: " + scenario);
        record(scenario, Map.of("rejectedBeforeResume", true));
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }

    private Map<String, String> deploymentProperties(MySQLContainer target) {
        var properties = new TreeMap<String, String>();
        properties.put("spring.datasource.url", target.getJdbcUrl());
        properties.put("spring.datasource.username", target.getUsername());
        properties.put("spring.datasource.password", target.getPassword());
        properties.put("spring.datasource.hikari.maximum-pool-size", "4");
        properties.put("spring.datasource.hikari.minimum-idle", "4");
        properties.put("spring.datasource.hikari.connection-timeout", "2000");
        properties.put("spring.datasource.hikari.data-source-properties.socketTimeout", "2000");
        properties.put("spring.datasource.hikari.data-source-properties.connectTimeout", "2000");
        properties.put("spring.datasource.hikari.connection-init-sql", "SET SESSION innodb_lock_wait_timeout=2, lock_wait_timeout=2, wait_timeout=5, max_execution_time=2000");
        properties.put("spring.transaction.default-timeout", "10s");
        properties.put("spring.jpa.properties.jakarta.persistence.query.timeout", "2000");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", String.valueOf(RepresentativeFlowIntegrationTests.PORT));
        properties.put("server.tomcat.threads.max", "64");
        properties.put("server.ssl.enabled", "true");
        properties.put("server.ssl.key-store", McpHttpIntegrationTests.KEYSTORE.toUri().toString());
        properties.put("server.ssl.key-store-password", McpHttpIntegrationTests.PASSWORD);
        properties.put("server.ssl.key-store-type", "PKCS12");
        properties.put("slotq.auth.dev-bootstrap-enabled", "false");
        properties.put("slotq.mcp.enabled", "true");
        properties.put("slotq.mcp.origin", RepresentativeFlowIntegrationTests.ORIGIN);
        properties.put("slotq.mcp.product-origin", RepresentativeFlowIntegrationTests.ORIGIN);
        properties.put("slotq.mcp.workers", "8");
        properties.put("slotq.mcp.ingress", "8");
        properties.put("slotq.mcp.quota.rate", "200");
        properties.put("slotq.mcp.quota.burst", "200");
        properties.put("slotq.mcp.quota.concurrency", "4");
        properties.put("slotq.mcp.handler-budget", "PT5S");
        properties.put("slotq.operations.observation.enabled", "true");
        properties.put("slotq.operations.recovery.enabled", "true");
        properties.put("slotq.observability.scrape-token", scrapeToken);
        properties.put("slotq.waitlist.promotion.enabled", "true");
        properties.put("slotq.waitlist.promotion.maintenance-enabled", "true");
        properties.put("slotq.waitlist.promotion.maintenance-interval", "PT1S");
        properties.put("slotq.waitlist.promotion.maintenance-batch-size", "8");
        properties.put("slotq.waitlist.promotion.discovery-batch-size", "8");
        properties.put("slotq.events.delivery.scheduler-enabled", "true");
        properties.put("slotq.events.delivery.poll-interval", "PT0.2S");
        properties.put("slotq.events.delivery.batch-size", "2");
        properties.put("spring.task.scheduling.pool.size", "2");
        properties.put("logging.level.root", "ERROR");
        properties.put("spring.main.banner-mode", "off");
        return properties;
    }

    private Map<String, String> publicConfiguration(Map<String, String> properties) {
        var publicConfig = new TreeMap<>(properties);
        publicConfig.keySet().removeIf(name -> name.contains("password") || name.contains("token"));
        publicConfig.put("jvm.jdk.httpclient.disableRetryConnect", "true");
        publicConfig.put("jvm.jdk.httpclient.enableAllMethodRetry", "false");
        publicConfig.put("jvm.jdk.httpclient.redirects.retrylimit", "1");
        return publicConfig;
    }

    private void resume(JdbcTemplate restored, String key, Map<String, String> properties) throws Exception {
        Path binary = Path.of(System.getProperty("slotq.capacity." + key)).toAbsolutePath();
        if (!pilot) M8BackupProtocol.artifact(binary, frozen.path(key).path("sha256").asString());
        write(output.resolve(key + "-config.json"), publicConfiguration(properties));
        Path trust = trustStore();
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-Xmx768m", "-Djdk.httpclient.disableRetryConnect=true", "-Djdk.httpclient.enableAllMethodRetry=false",
            "-Djdk.httpclient.redirects.retrylimit=1", "-Djavax.net.ssl.trustStore=" + trust,
            "-Djavax.net.ssl.trustStoreType=JKS", "-jar", binary.toString());
        process.environment().put("SPRING_APPLICATION_JSON", JSON.writeValueAsString(properties));
        process.redirectErrorStream(true).redirectOutput(output.resolve(key + "-process.log").toFile());
        long launch = System.nanoTime();
        Process child = process.start();
        try {
            var readySession = new java.util.concurrent.atomic.AtomicReference<String>();
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(250)).until(() -> {
                require(child.isAlive(), "Restored binary terminated before readiness");
                try {
                    if (readySession.get() == null) readySession.set(newSession(freshMcp));
                    var read = call(freshMcp, readySession.get(), "reservation.get", Map.of("reservationId", knownReservation.toString()));
                    return read.path("outcome").asString().equals("succeeded") && read.path("productStatus").asInt() == 200
                        && read.path("data").path("id").asString().equals(knownReservation.toString());
                }
                catch (java.io.IOException unavailable) { return false; }
            });
            long readyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - launch);
            require(readyMillis <= 60000, "Readiness exceeded #182 limit");
            require(get(staleRead, knownReservation).statusCode() == 401, "Revoked snapshot credential authorized");
            require(initialize(revokedMcp).statusCode() == 401, "Revoked live delegation authorized");
            require(initialize(expiredMcp).statusCode() == 401, "Expired credential/approval authority authorized");
            require(Set.of(401, 403).contains(initialize(removedManagerMcp).statusCode()), "Removed Manager scope authorized");
            require(f.rpc(f.customer.delegation().value(), oldSession, "tools/list", Map.of(), 1).statusCode() == 404,
                "Process-local old session was resumed");
            String session = readySession.get();
            long expectedDispatched = JSON.readTree(Files.readString(output.resolve("security-meta.json"))).path("dispatchedIntents").asLong();
            require(count(restored, "SELECT COUNT(*) FROM mcp_hold_intents WHERE first_dispatch_at IS NOT NULL") == expectedDispatched,
                "Restored intent automatically dispatched at startup");
            // The scheduler may legitimately create FIFO reservations on other slots during these calls.
            long beforeRejectedApproval = reservationsOnSlot(restored, safeSlot);
            for (var oldApproval : List.of(revokedIntentArguments, expiredIntentArguments)) {
                var denied = call(freshMcp, session, "reservation.hold", oldApproval);
                require(denied.path("outcome").asString().equals("not_dispatched") && denied.path("category").asString().equals("forbidden"),
                    "Old approval was accepted by a replacement delegation");
            }
            require(reservationsOnSlot(restored, safeSlot) == beforeRejectedApproval
                && count(restored, "SELECT COUNT(*) FROM mcp_hold_intents WHERE first_dispatch_at IS NOT NULL") == expectedDispatched,
                "Old approval caused a business mutation");
            for (String marker : List.of("backup-withdrawal-sentinel", "backup-old-version-sentinel", "backup-operator-version-sentinel")) {
                var hidden = call(freshMcp, session, "knowledge.search", Map.of("query", marker, "limit", 5));
                require(Set.of("evidence", "insufficient", "no_answer").contains(hidden.path("category").asString())
                    && hidden.path("results").isArray() && !hidden.toString().contains(marker)
                    && !hidden.toString().contains(withdrawn.document().documentId().toString())
                    && !hidden.toString().contains(changedPublication.document().documentId().toString()), "Unauthorized publication or missing search evidence");
            }
            var visible = call(freshMcp, session, "knowledge.search", Map.of("query", "backup-survivor-marker", "limit", 1));
            var hit = visible.path("results").path(0);
            require(hit.path("documentId").asString().equals(survivor.document().documentId().toString())
                && hit.path("versionId").asString().equals(survivor.versionId().toString())
                && hit.path("excerpt").asString().contains("backup-survivor-marker"), "Fresh derived search omitted current publication");
            if (key.equals("previousBinary")) {
                UUID replaySlot = UUID.fromString((String) replayArguments.get("slotInventoryId"));
                long reservationsBeforeReplay = reservationsOnSlot(restored, replaySlot);
                String replaySession = newSession(f.customer.delegation().value());
                var replay = call(f.customer.delegation().value(), replaySession, "reservation.hold", replayArguments);
                require(replay.path("outcome").asString().equals("succeeded")
                    && replay.path("data").path("id").asString().equals(knownReservation.toString())
                    && reservationsOnSlot(restored, replaySlot) == reservationsBeforeReplay,
                    "Original completed idempotency key did not replay the exact identity");
                var response = call(freshMcp, session, "reservation.hold", freshHoldArguments);
                require(response.path("outcome").asString().equals("succeeded") && response.path("productStatus").asInt() == 201,
                    "Explicit safe HOLD failed after binary rollback");
                require(count(restored, "SELECT COUNT(*) FROM reservations WHERE slot_inventory_id=UNHEX('" + hex(safeSlot) + "')") == 1
                    && count(restored, "SELECT COUNT(*) FROM hold_idempotency_records WHERE slot_inventory_id=UNHEX('" + hex(safeSlot) + "')") == 1,
                    "Safe mutation lacks atomic idempotency/reservation");
            }
            await().atMost(Duration.ofSeconds(90)).until(() -> unfinished(restored, "waitlist.promotion") == 0);
            for (var expected : expectedOffers.entrySet()) {
                require(restored.queryForObject("SELECT COUNT(*) FROM waitlist_offers o JOIN reservations r ON r.id=o.reservation_id "
                    + "WHERE r.slot_inventory_id=? AND o.entry_id=?", Long.class, bytes(expected.getKey()), bytes(expected.getValue())) == 1,
                    "Restored backlog did not create exactly the original FIFO entry's Offer");
            }
            quiet(key);
            record("binary-resumed-" + key, Map.of("sha256", digest(binary), "configSha256", digest(output.resolve(key + "-config.json")),
                "readyMillis", readyMillis, "convergenceMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - launch),
                "exactGet", 200, "revokedCredential", 401, "mcpNewSession", true));
            if (key.equals("previousBinary")) {
                converged(restored, key, launch);
                oracle(restored, "after-service");
                var finalState = snapshot(restored);
                write(output.resolve("final-snapshot.json"), finalState);
                var original = JSON.readTree(Files.readString(output.resolve("snapshot.json")));
                require(original.path("flyway_schema_history").equals(JSON.valueToTree(finalState.get("flyway_schema_history"))),
                    "Binary rollback changed the V23 schema history");
                var history = M8BackupDatabase.history(original, JSON.valueToTree(finalState));
                write(output.resolve("preserved-history.json"), history);
                require(history.values().stream().allMatch(value -> value == 0), "Original event/receipt/replay/intent/audit history changed");
                require(count(restored, "SELECT COUNT(*) FROM mcp_hold_intents WHERE first_dispatch_at IS NOT NULL") == expectedDispatched + 1,
                    "Unexpected dispatch while serving restored data");
                var lastRead = call(freshMcp, session, "reservation.get", Map.of("reservationId", knownReservation.toString()));
                require(child.isAlive() && lastRead.path("productStatus").asInt() == 200
                    && lastRead.path("data").path("id").asString().equals(knownReservation.toString()), "Previous binary unavailable at end of rollback");
                verifiedRtoMillis = elapsed();
                record("verified-service-live", Map.of("rtoMillis", verifiedRtoMillis, "binary", key, "schemaHistoryUnchanged", true));
            }
        } finally {
            child.destroy();
            if (!child.waitFor(20, TimeUnit.SECONDS)) { child.destroyForcibly(); require(child.waitFor(10, TimeUnit.SECONDS), "Child writer did not exit"); }
        }
        if (key.equals("candidateBinary")) {
            drainObserver(binary, properties, restored, key);
            converged(restored, key, launch);
        }
    }

    private void converged(JdbcTemplate restored, String key, long launch) {
        long convergedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - launch);
        require(convergedMillis <= 90000 && unfinished(restored, "waitlist.promotion") == 0
            && unfinished(restored, "operations.event-observation") == 0, "Restored target did not fully converge within #182 bound");
        record("all-consumers-converged-" + key, Map.of("millisFromProductLaunch", convergedMillis,
            "observerExecution", "sequential isolated supported DB_DIRECT consumer, never concurrent Product writers"));
    }

    private long reservationsOnSlot(JdbcTemplate db, UUID slot) {
        return db.queryForObject("SELECT COUNT(*) FROM reservations WHERE tenant_id=? AND venue_id=? AND slot_inventory_id=?",
            Long.class, bytes(f.tenant), bytes(f.venue.value()), bytes(slot));
    }

    private static long unfinished(JdbcTemplate db, String consumer) {
        return db.queryForObject("SELECT COUNT(*) FROM event_records e JOIN event_registrations r ON r.event_type=e.event_type "
            + "AND r.schema_version=e.schema_version AND e.boundary_sequence>r.activation_boundary "
            + "AND (r.deactivation_boundary IS NULL OR e.boundary_sequence<r.deactivation_boundary) "
            + "LEFT JOIN event_deliveries d ON d.tenant_id=e.tenant_id AND d.event_id=e.event_id AND d.registration_id=r.registration_id "
            + "WHERE r.consumer_id=? AND (d.event_id IS NULL OR d.state IN ('PENDING','PROCESSING'))", Long.class, consumer);
    }

    private void drainObserver(Path binary, Map<String, String> product, JdbcTemplate restored, String key) throws Exception {
        if (unfinished(restored, "operations.event-observation") == 0) return;
        var properties = new TreeMap<>(product);
        properties.put("spring.main.web-application-type", "none");
        properties.put("slotq.events.runtime-role", "consumer");
        properties.put("slotq.events.delivery.consumer-id", "operations.event-observation");
        properties.put("slotq.mcp.enabled", "false");
        properties.put("slotq.waitlist.promotion.enabled", "false");
        properties.put("slotq.waitlist.promotion.maintenance-enabled", "false");
        var safeConfig = new TreeMap<>(properties);
        safeConfig.keySet().removeIf(name -> name.contains("password") || name.contains("token"));
        write(output.resolve(key + "-observer-config.json"), safeConfig);
        var command = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Xmx768m", "-jar", binary.toString());
        command.environment().put("SPRING_APPLICATION_JSON", JSON.writeValueAsString(properties));
        command.redirectErrorStream(true).redirectOutput(output.resolve(key + "-observer.log").toFile());
        long started = System.nanoTime();
        Process observer = command.start();
        try {
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                require(observer.isAlive(), "Isolated observer binary exited before convergence");
                return unfinished(restored, "operations.event-observation") == 0;
            });
            record("observer-drained-" + key, Map.of("launchToDurableObservationMillis",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), "configSha256", digest(output.resolve(key + "-observer-config.json"))));
        } finally {
            observer.destroy();
            if (!observer.waitFor(20, TimeUnit.SECONDS)) { observer.destroyForcibly(); require(observer.waitFor(10, TimeUnit.SECONDS), "Observer did not stop"); }
        }
    }

    private void quiet(String key) throws Exception {
        long started = System.nanoTime();
        var samples = new ArrayList<Map<String, Object>>();
        var consecutive = new java.util.concurrent.atomic.AtomicInteger();
        try {
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).until(() -> {
                var response = f.http.send(HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN + "/actuator/prometheus"))
                    .timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer " + scrapeToken).GET().build(), HttpResponse.BodyHandlers.ofString());
                require(response.statusCode() == 200, "Missing fresh resource observation");
                double active = metric(response.body(), "hikaricp_connections_active");
                double pending = metric(response.body(), "hikaricp_connections_pending");
                samples.add(Map.of("at", Instant.now().toString(), "active", active, "pending", pending));
                if (active == 0 && pending == 0) consecutive.incrementAndGet();
                else consecutive.set(0);
                return consecutive.get() >= 3;
            });
        } finally {
            write(output.resolve(key + "-quiet.json"), Map.of("samples", samples, "elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                "scope", "fresh Hikari samples after completed Product/MCP calls; process-local Agent work is not restored"));
        }
    }

    private static double metric(String text, String name) {
        var match = java.util.regex.Pattern.compile("(?m)^" + name + "(?:\\{[^}]*})? ([0-9.]+)$").matcher(text);
        require(match.find(), "Missing metric " + name);
        return Double.parseDouble(match.group(1));
    }

    private void currentCredentials(MySQLContainer target) {
        // Private administrative composition of the unchanged Auth services; no HTTP, worker or development bootstrap.
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var pool = new com.zaxxer.hikari.HikariDataSource();
            pool.setJdbcUrl(target.getJdbcUrl());
            pool.setUsername(target.getUsername());
            pool.setPassword(target.getPassword());
            pool.setMaximumPoolSize(4);
            pool.setConnectionTimeout(2000);
            pool.addDataSourceProperty("socketTimeout", "2000");
            pool.addDataSourceProperty("connectTimeout", "2000");
            pool.addDataSourceProperty("connectionTimeZone", "UTC");
            pool.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
            pool.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=2, lock_wait_timeout=2, wait_timeout=5, max_execution_time=2000");
            var transaction = new org.springframework.jdbc.datasource.DataSourceTransactionManager(pool);
            transaction.setDefaultTimeout(10);
            context.registerBean(javax.sql.DataSource.class, () -> pool, definition -> definition.setDestroyMethodName("close"));
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(pool));
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class, () -> transaction);
            context.registerBean(Clock.class, Clock::systemUTC);
            context.registerBean(com.slotq.auth.application.AuthorizationUseCase.class);
            context.scan("com.slotq.auth.persistence");
            context.refresh();
            var authority = context.getBean(ActorAccessService.class);
            var current = authority.authenticateMcp(freshMcp);
            if (freshHoldArguments == null) {
                var approvals = new HoldApprovals(authority, context.getBean(JdbcTemplate.class), transaction, Clock.systemUTC());
                var review = approvals.prepare(freshOriginal, current.delegationId(), safeSlot, 2);
                freshHoldArguments = holdArguments(review, approvals.approve(freshOriginal, review.intentId()));
                // Explicitly reapprove the known completed request in its original, still-authorized delegation.
                // This fixture never had an Agent Run or an unknown outcome. No key or target is replaced.
                var replay = approvals.review(f.customer.original().value(), replayIntent);
                replayArguments = holdArguments(replay, approvals.approve(f.customer.original().value(), replayIntent));
            }
        }
    }

    private static Map<String, Object> holdArguments(HoldApprovals.Review review, HoldApprovals.Confirmation approval) {
        return Map.of("intentId", review.intentId().toString(), "confirmationId", approval.id().toString(),
            "slotInventoryId", review.slotInventoryId().toString(), "partySize", review.partySize(), "idempotencyKey", review.idempotencyKey());
    }

    private String newSession(String token) throws Exception {
        var initialized = initialize(token);
        require(initialized.statusCode() == 200, "Fresh MCP session unavailable");
        String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
        require(f.rpc(token, session, "notifications/initialized", Map.of(), null).statusCode() == 202, "Fresh session not ready");
        return session;
    }

    private JsonNode call(String token, String session, String tool, Map<String, Object> arguments) throws Exception {
        var response = f.rpc(token, session, "tools/call", Map.of("name", tool, "arguments", arguments), 10);
        require(response.statusCode() == 200, "MCP transport unavailable");
        return JSON.readTree(response.body()).path("result").path("structuredContent");
    }

    private HttpResponse<String> get(String token, UUID reservation) throws Exception {
        return f.http.send(HttpRequest.newBuilder(URI.create(RepresentativeFlowIntegrationTests.ORIGIN + "/api/v1/venues/"
            + f.venue.value() + "/reservations/" + reservation)).timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> initialize(String token) throws Exception {
        return f.rpc(token, null, "initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(),
            "clientInfo", Map.of("name", "backup-negative", "version", "1")), 1);
    }

    private Path trustStore() throws Exception {
        Path path = output.resolve("loopback-trust.jks");
        if (Files.exists(path)) return path;
        var original = java.security.KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(McpHttpIntegrationTests.KEYSTORE)) {
            original.load(input, McpHttpIntegrationTests.PASSWORD.toCharArray());
        }
        var trust = java.security.KeyStore.getInstance("JKS");
        trust.load(null, null);
        var aliases = original.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            trust.setCertificateEntry(alias, original.getCertificate(alias));
        }
        try (var stream = Files.newOutputStream(path)) { trust.store(stream, new char[0]); }
        return path;
    }

    private UUID newSlot(String name) {
        return newSlot(name, "17:00:00");
    }

    private UUID newSlot(String name, String time) {
        var resource = f.resources.createResource(new com.slotq.venue.application.ResourceUseCase.CreateResource(
            new com.slotq.tenancy.domain.TenantId(f.tenant), f.venue, name, 4));
        return f.slots.createSlot(new com.slotq.booking.application.SlotInventoryUseCase.CreateSlot(
            new com.slotq.tenancy.domain.TenantId(f.tenant), f.venue, resource.id(), f.day + "T" + time + "Z")).id().value();
    }

    private void denySql(JdbcTemplate db, String sql, String scenario) {
        int code = deniedSqlCode(db, sql, scenario);
        record(scenario, Map.of("rejected", true, "mysqlErrorCode", code));
    }

    static int deniedSqlCode(JdbcTemplate db, String sql, String scenario) {
        int expectedCode = switch (scenario) {
            case "quiesced-write", "quiesced-ddl" -> 1290;
            case "append-only-update", "append-only-delete" -> 1644;
            case "missing-credential" -> 1045;
            case "insufficient-backup-privilege", "insufficient-restore-privilege", "re-revocation-permission-denied" -> 1142;
            default -> throw new IllegalArgumentException("Unknown SQL refusal scenario");
        };
        try { db.execute(sql); }
        catch (org.springframework.dao.DataAccessException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sqlFailure && sqlFailure.getErrorCode() == expectedCode) return expectedCode;
            }
            Throwable root = failure.getMostSpecificCause();
            throw new IllegalArgumentException("SQL failed for a different reason: " + scenario
                + (root instanceof SQLException sqlCause ? " (MySQL " + sqlCause.getErrorCode() + ")" : ""));
        }
        throw new IllegalArgumentException("Unsafe operation accepted: " + scenario);
    }

    private static void configureClient(MySQLContainer mysql) throws Exception {
        String options = "[client]\nuser=root\npassword=" + mysql.getPassword() + "\n";
        mysql.copyFileToContainer(org.testcontainers.images.builder.Transferable.of(options.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0600), "/tmp/slotq-client.cnf");
    }

    private static void protect(Path path) throws Exception {
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        require(acl != null, "Protected local evidence storage requires an ACL-capable filesystem");
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
            .setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
    }

    private void record(String name, Map<String, ?> details) {
        timeline.add(Map.of("name", name, "at", Instant.now().toString(), "details", details));
    }

    private long elapsed() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - recoveryStarted); }
    private long rpo() { return Duration.between(recoveryPoint, sourceCutoff).toMillis(); }
    private long rpoLimit() { return pilot ? Long.MAX_VALUE : frozen.path("rpoMillis").asLong(); }
    private long rtoLimit() { return pilot ? Long.MAX_VALUE : frozen.path("rtoMillis").asLong(); }
    private static JdbcTemplate db(MySQLContainer mysql) { return new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword())); }
    private static UUID uuid(byte[] value) { var b = java.nio.ByteBuffer.wrap(value); return new UUID(b.getLong(), b.getLong()); }
    private static String hex(UUID value) { return HexFormat.of().formatHex(bytes(value)); }
    private static String decode(JsonNode value) { return new String(HexFormat.of().parseHex(value.asString()), java.nio.charset.StandardCharsets.UTF_8); }
}
