package com.slotq.mcp;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.slotq.mcp.M8CapacityEvidence.*;

class M8RecoveryPlanTests {
    @TempDir Path temporary;
    private final Instant now = Instant.parse("2026-10-27T09:00:00Z");

    @Test void frozenMatchingProfileIsAcceptedWithoutDockerOrCredentials() throws Exception {
        Path file = save(plan());
        assertThat(M8RecoveryDrill.readPlan(file, digest(file), 4, 16, 20, now).path("state").asString()).isEqualTo("FROZEN");
    }

    @Test void missingOrChangedPlanCannotStartAnExperiment() throws Exception {
        Path file = save(plan());
        String original = digest(file);
        Files.writeString(file, Files.readString(file) + " ");
        assertThatThrownBy(() -> M8RecoveryDrill.readPlan(file, original, 4, 16, 20, now)).hasMessageContaining("changed");
        assertThatThrownBy(() -> M8RecoveryDrill.readPlan(temporary.resolve("missing"), original, 4, 16, 20, now)).hasMessageContaining("Missing");
    }

    @Test void pendingOrFutureCriteriaAndDifferentSupportProfileAreRejected() throws Exception {
        var pending = plan(); pending.put("state", "PENDING");
        reject(pending, "frozen");
        var future = plan(); future.put("frozenAt", now.plusSeconds(1).toString());
        reject(future, "frozen");
        Path file = save(plan());
        assertThatThrownBy(() -> M8RecoveryDrill.readPlan(file, digest(file), 3, 16, 20, now)).hasMessageContaining("profile");
        assertThatThrownBy(() -> M8RecoveryDrill.readPlan(file, digest(file), 4, 64, 20, now)).hasMessageContaining("profile");
    }

    @Test void relaxedOrMissingTimeAndConsistencyCriteriaAreRejectedEvenWithNewDigest() throws Exception {
        var relaxed = plan();
        gates(relaxed).put("durableConvergenceMillis", 90001);
        reject(relaxed, "time criteria");
        var missing = plan(); gates(missing).remove("fifoViolations");
        reject(missing, "fifoViolations");
        var effects = plan(); gates(effects).put("duplicateEffects", 1);
        reject(effects, "duplicateEffects");
    }

    private void reject(Map<String, Object> value, String message) throws Exception {
        Path file = save(value);
        assertThatThrownBy(() -> M8RecoveryDrill.readPlan(file, digest(file), 4, 16, 20, now)).hasMessageContaining(message);
    }

    private Path save(Map<String, Object> value) throws Exception {
        Path file = temporary.resolve(UUID.randomUUID() + ".json");
        Files.writeString(file, JSON.writeValueAsString(value));
        return file;
    }

    @SuppressWarnings("unchecked") private Map<String, Object> gates(Map<String, Object> plan) {
        return (Map<String, Object>) plan.get("gates");
    }

    private Map<String, Object> plan() {
        var gates = new LinkedHashMap<String, Object>(Map.of("detectionMillis", 45000, "restartReadyMillis", 60000,
            "durableConvergenceMillis", 90000, "postRecoveryQuietMillis", 15000));
        for (String key : List.of("partialEffects", "duplicateEffects", "capacityViolations", "fifoViolations", "missingAudit", "automaticMutationAfterRestart")) {
            gates.put(key, 0);
        }
        return new LinkedHashMap<>(Map.of("schemaVersion", "slotq-m8-recovery-plan/v1", "state", "FROZEN",
            "frozenAt", now.minusSeconds(1).toString(), "gates", gates,
            "supportInputs", List.of(Map.of("pool", 4, "size", 16, "rate", 20, "seed", 18101, "criteriaDigest", "1".repeat(64)))));
    }
}
