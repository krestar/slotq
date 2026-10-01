package com.slotq.experiments.waitlist;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;
import static org.assertj.core.api.Assertions.*;
import static com.slotq.experiments.waitlist.M5TransportComparisonRunner.JSON;
import static com.slotq.experiments.waitlist.M5TransportEvidence.*;
import static com.slotq.experiments.waitlist.WaitlistRecoveryDatabase.list;

class M5OperationsDrillEvidenceTests {
    private static final Path CANONICAL=Path.of("../docs/experiments/m5-transport/2026-10-01-drill");
    @TempDir Path copy;
    @Test void freshRepresentativeDrillReconcilesOriginalsReceiptsAuthorityAndRecovery() throws Exception {
        assertThat(M5OperationsDrillEvidence.summarize(raw())).containsEntry("result","PASS")
            .containsEntry("originalEvents",9L).containsEntry("doneTargets",18L).containsEntry("humanAudit",2);
    }
    @Test void phaseNamesWithoutActualExecutorSqlFailureCannotPass() throws Exception {
        var raw=raw();phase(raw,"database-outage-observed").put("runtimeSqlFailures",List.of());
        assertThatThrownBy(()->M5OperationsDrillEvidence.summarize(raw)).hasMessageContaining("executor JDBC failure missing");
    }
    @Test void changedTargetAttemptsAcrossRollbackCannotPass() throws Exception {
        var raw=raw();var after=phase(raw,"quiesced-after-rollback");
        var delivery=list(maps(after.get("tenants")).getFirst(),"event_deliveries").getFirst();
        delivery.put("cycle_attempts",number(delivery,"cycle_attempts")+1);
        assertThatThrownBy(()->M5OperationsDrillEvidence.summarize(raw)).hasMessageContaining("rollback changed original target/attempt/receipt");
    }
    @Test void wrongFinalAuthorityCannotPass() throws Exception {
        var raw=raw();phase(raw,"db-direct-converged").put("authorityEpoch",4);
        assertThatThrownBy(()->M5OperationsDrillEvidence.summarize(raw)).hasMessageContaining("rollback authority missing");
    }
    @Test void missingHumanAuditCannotPass() throws Exception {
        var raw=raw();phase(raw,"db-direct-converged").put("operations_recovery_audit",List.of());
        assertThatThrownBy(()->M5OperationsDrillEvidence.summarize(raw)).hasMessageContaining("human recovery duplicate/missing audit");
    }
    @Test void recalculationNeverRewritesAnExistingIntegrityArtifact() throws Exception {
        for(String file:List.of("manifest.json","raw.json.gz","summary.json","integrity.json"))Files.copy(CANONICAL.resolve(file),copy.resolve(file));
        Path integrity=copy.resolve("integrity.json");Files.writeString(integrity," \n"+Files.readString(integrity));
        byte[] prior=Files.readAllBytes(integrity);
        M5OperationsDrillEvidence.verify(copy);
        assertThat(Files.readAllBytes(integrity)).isEqualTo(prior);
    }
    private static Map<String,Object> raw() throws Exception {
        // Expand shared dictionary rows into independent objects for deliberate mutation cases.
        return JSON.readValue(JSON.writeValueAsString(M5CanonicalEvidence.read(CANONICAL)),new TypeReference<>(){});
    }
    private static Map<String,Object> phase(Map<String,Object> raw,String name){return maps(raw.get("timeline")).stream().filter(t->name.equals(t.get("phase"))).findFirst().orElseThrow();}
}
