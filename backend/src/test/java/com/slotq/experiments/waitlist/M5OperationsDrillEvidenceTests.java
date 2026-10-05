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
    @TempDir Path copy;
    @Test void syntheticDrillInputReconcilesOriginalsReceiptsAuthorityAndRecovery() throws Exception {
        assertThat(M5OperationsDrillEvidence.summarize(raw())).containsEntry("result","PASS")
            .containsEntry("originalEvents",1L).containsEntry("doneTargets",2L).containsEntry("humanAudit",2);
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
        var raw=raw();
        M5TransportComparisonRunner.write(copy.resolve("manifest.json"),Map.of("fixture","synthetic oracle input"));
        var dictionary=new TreeMap<String,Object>();
        M5CanonicalEvidence.writeRaw(copy,Map.of("schemaVersion","slotq-m5-canonical/v1",
            "manifestSha256",M5CanonicalEvidence.sha(Files.readAllBytes(copy.resolve("manifest.json"))),
            "rows",dictionary,"data",M5CanonicalEvidence.encode(raw,dictionary)));
        M5TransportComparisonRunner.write(copy.resolve("summary.json"),M5OperationsDrillEvidence.summarize(raw));
        M5OperationsDrillEvidence.verify(copy);
        Path integrity=copy.resolve("integrity.json");Files.writeString(integrity," \n"+Files.readString(integrity));
        byte[] prior=Files.readAllBytes(integrity);
        M5OperationsDrillEvidence.verify(copy);
        assertThat(Files.readAllBytes(integrity)).isEqualTo(prior);
    }
    private static Map<String,Object> raw() throws Exception {
        // Deterministic unit input, not an execution report or proof that a physical drill ran.
        var timeline=new ArrayList<Map<String,Object>>();
        var phases=List.of("healthy","dashboard-query","trace-query","linked-attempt-search",
            "metrics-only-network-boundary","observer-stop","observer-down-waitlist-done","observer-recovered",
            "waitlist-fault-input","waitlist-dead-observer-done","handler-cause-corrected",
            "human-recovery-admitted","human-recovery-completed","broker-stop","broker-unavailable",
            "broker-recovered","publication-ack-unknown","publication-unknown-expired-lease",
            "publication-redelivery-converged","database-pause","before-db-outage",
            "database-outage-observed","database-recovered","quiesced-before-rollback",
            "quiesced-after-rollback","manual-kafka-db-rollback","db-direct-after-rollback",
            "db-observer-stop","db-observer-down-waitlist-done","db-observer-recovered",
            "db-waitlist-fault-input","db-waitlist-dead-observer-done","db-human-recovery-admitted",
            "db-human-recovery-completed","db-direct-converged");
        for(String name:phases) {
            String waitlist=name.endsWith("waitlist-dead-observer-done")?"DEAD":name.equals("before-db-outage")?"PROCESSING":"DONE";
            String observer=name.endsWith("observer-down-waitlist-done")?"PROCESSING":"DONE";
            var tenant=row("tenantId","tenant","businessNow","2026-10-01T00:00:00Z",
                "event_records",List.of(row("event_id","e","tenant_id","tenant","event_type","waitlist.promotion-requested","schema_version",1,"boundary_sequence",3)),
                "registrations",List.of(registration("r1","waitlist.promotion"),registration("r2","operations.event-observation")),
                "event_deliveries",List.of(delivery("r1",waitlist),delivery("r2",observer)),
                "waitlist_promotion_receipts",waitlist.equals("DONE")?List.of(row("tenant_id","tenant","event_id","e","outcome","NO_CANDIDATE")):List.of(),
                "waitlist_offers",List.of(),"waitlist_notification_requests",List.of(),
                "slot_inventories",List.of(row("id","slot","capacity",1)),
                "reservations",List.of(row("id","reservation","state","CONFIRMED")),
                "capacity_allocations",List.of(row("slot_inventory_id","slot","reservation_id","reservation","active",true,"units",1)));
            var observation=row("phase",name,"eventId","e","at","2026-10-01T00:00:00Z","nanos",timeline.size()*1_000_000L,
                "tenants",List.of(tenant),"authorityEpoch",3,"transport","DB_DIRECT",
                "event_transport_assignments",List.of(row("registration_id","r1","transport","DB_DIRECT","authority_epoch",3),row("registration_id","r2","transport","DB_DIRECT","authority_epoch",3)),
                "event_observation_projections",observer.equals("DONE")?List.of(row("tenant_id","tenant","event_id","e")):List.of(),
                "event_kafka_intake_records",List.of(row("event_id","e"),row("event_id","e"),row("event_id","e"),row("event_id","e")),
                "event_kafka_target_intakes",List.of(row("event_id","e","registration_id","r1"),row("event_id","e","registration_id","r2")),
                "event_kafka_publications",List.of(row("state","PUBLISHED")),
                "operations_recovery_operations",List.of(row("operation_id","o1","result_json","result1"),row("operation_id","o2","result_json","result2")),
                "operations_recovery_audit",List.of(row("operation_id","o1","result_json","result1"),row("operation_id","o2","result_json","result2")),
                "admission","accepted","sameOperationRetry","accepted",
                "scrapeStatus",200,"healthyEvents",false,
                "runtimeSqlFailures",List.of(row("entrypoint","EventDeliveryWorker.runCycle","exceptionClasses",List.of("java.sql.SQLException"))));
            timeline.add(observation);
        }
        for(String alert:List.of("SlotqExecutionRuntimeUnavailable","SlotqConsumerDeadDelivery","SlotqDeadDelivery",
            "SlotqKafkaLagUnavailable","SlotqPublicationClaimExpired","SlotqDatabaseSampleUnavailable"))
            for(boolean firing:List.of(true,false))timeline.add(row("phase","alert-transition","alert",alert,"firing",firing));
        // Each observation owns independent mutable rows for the deliberate corruption cases.
        return JSON.readValue(JSON.writeValueAsString(Map.of("timeline",timeline)),new TypeReference<>(){});
    }
    private static Map<String,Object> registration(String id,String consumer){return row("registration_id",id,"consumer_id",consumer,"event_type","waitlist.promotion-requested","schema_version",1,"activation_boundary",0,"deactivation_boundary",null);}
    private static Map<String,Object> delivery(String registration,String state){return row("event_id","e","registration_id",registration,"state",state,"cycle_attempts",1);}
    private static Map<String,Object> row(Object...pairs){var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;}
    private static Map<String,Object> phase(Map<String,Object> raw,String name){return maps(raw.get("timeline")).stream().filter(t->name.equals(t.get("phase"))).findFirst().orElseThrow();}
}
