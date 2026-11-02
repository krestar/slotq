package com.slotq.mcp;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class M8BackupProtocolTests {
    @TempDir Path temporary;

    private Map<String, String> valid() {
        var checks = new HashMap<String, String>();
        M8BackupProtocol.REQUIRED.forEach(key -> checks.put(key, "PASS"));
        return checks;
    }

    @Test void everyMandatoryObservationBlocksResumeWhenAbsentOrUnknown() {
        for (String key : M8BackupProtocol.REQUIRED) {
            var checks = valid();
            checks.remove(key);
            assertThat(verdict(checks, 100L, 100L)).as(key).isEqualTo("INCOMPLETE");
            checks.put(key, "NOT_APPLICABLE");
            assertThat(verdict(checks, 100L, 100L)).as(key).isEqualTo("INCOMPLETE");
        }
    }

    @Test void explicitViolationCannotBeHiddenByMissingEvidence() {
        for (String key : M8BackupProtocol.REQUIRED) {
            assertThat(verdict(Map.of(key, "FAIL"), null, null)).as(key).isEqualTo("FAIL");
        }
    }

    @Test void timeLimitsAndMissingClocksAreNotSuccessfulRecovery() {
        assertThat(verdict(valid(), 100L, 100L)).isEqualTo("PASS");
        assertThat(verdict(valid(), 101L, 100L)).isEqualTo("FAIL");
        assertThat(verdict(valid(), 100L, 101L)).isEqualTo("FAIL");
        assertThat(verdict(valid(), -1L, 100L)).isEqualTo("FAIL");
        assertThat(verdict(valid(), null, 100L)).isEqualTo("INCOMPLETE");
        assertThat(verdict(valid(), 100L, null)).isEqualTo("INCOMPLETE");
    }

    @Test void corruptedMissingAndSubstitutedBackupAreRejected() throws Exception {
        Path backup = temporary.resolve("backup.sql");
        Files.writeString(backup, "original backup bytes");
        String digest = M8CapacityEvidence.digest(backup);
        M8BackupProtocol.artifact(backup, digest);
        Files.writeString(backup, "corrupted backup bytes");
        assertThatThrownBy(() -> M8BackupProtocol.artifact(backup, digest)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> M8BackupProtocol.artifact(temporary.resolve("missing"), digest)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> M8BackupProtocol.artifact(backup, "")).isInstanceOf(IllegalArgumentException.class);
    }

    private String verdict(Map<String, String> checks, Long rpo, Long rto) {
        return M8BackupProtocol.resumeVerdict(checks, rpo, rto, 100, 100);
    }

    @Test void securityAuthorityMustBelongToThisRunAndBothRecoveryBoundaries() {
        var point = java.time.Instant.parse("2026-10-28T00:00:00Z");
        var cutoff = point.plusSeconds(10);
        var fields = new HashMap<String, String>(Map.of("runId", "run", "recoveryPoint", point.toString(),
            "sourceCutoff", cutoff.toString(), "sha256", "a".repeat(64)));
        M8BackupProtocol.securityIdentity(M8CapacityEvidence.JSON.valueToTree(fields), "run", point, cutoff);
        for (String field : List.of("runId", "recoveryPoint", "sourceCutoff", "sha256")) {
            var changed = new HashMap<>(fields);
            changed.put(field, "wrong");
            assertThatThrownBy(() -> M8BackupProtocol.securityIdentity(M8CapacityEvidence.JSON.valueToTree(changed), "run", point, cutoff))
                .isInstanceOf(IllegalArgumentException.class);
            changed.remove(field);
            assertThatThrownBy(() -> M8BackupProtocol.securityIdentity(M8CapacityEvidence.JSON.valueToTree(changed), "run", point, cutoff))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void originalTerminalDeliveryAndFenceHistoryCannotBeRewritten() {
        var delivery = new TreeMap<>(Map.of("tenant_id", "01", "event_id", "02", "registration_id", "03",
            "state", hex("DONE"), "fencing_token", hex("3"), "lifetime_attempts", hex("2")));
        var original = M8CapacityEvidence.JSON.valueToTree(Map.of("event_deliveries", Map.of("rows",
            List.of(M8CapacityEvidence.JSON.writeValueAsString(delivery))), "event_records", Map.of("rows", List.of("immutable-event"))));
        assertThat(M8BackupDatabase.history(original, original).values()).allMatch(value -> value == 0);
        delivery.put("state", hex("PENDING"));
        delivery.put("fencing_token", hex("1"));
        var corrupted = M8CapacityEvidence.JSON.valueToTree(Map.of("event_deliveries", Map.of("rows",
            List.of(M8CapacityEvidence.JSON.writeValueAsString(delivery)))));
        var violations = M8BackupDatabase.history(original, corrupted);
        assertThat(violations.get("terminalDeliveryChanged")).isEqualTo(1);
        assertThat(violations.get("fenceOrLifetimeRegressed")).isEqualTo(1);
        assertThat(violations.get("event_records")).isEqualTo(1);
    }

    private String hex(String value) {
        return HexFormat.of().formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
