package com.slotq.experiments.waitlist;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class M5TransportEvidenceTests {
    @Test void twoConsumerReceiptsHaveDifferentMeaningAndLagIsNotBusinessDone() {
        var o=fixture("DB_DIRECT");
        assertThat(M5TransportEvidence.reconcile(o,true)).containsEntry("originalEvents",1L).containsEntry("originalTargets",2L).containsEntry("doneTargets",2L).containsEntry("durableTargetIntakes",0);
        deliveries(o).getLast().put("state","PENDING");projections(o).clear();
        o.put("broker",Map.of("lag",0));
        assertThat(M5TransportEvidence.reconcile(o,false)).containsEntry("outstandingTargets",1L).containsEntry("doneTargets",1L);
        assertThatThrownBy(()->M5TransportEvidence.reconcile(o,true)).hasMessageContaining("incomplete");
    }
    @Test void originalMembershipAuthorityAndTargetCannotDisappear() {
        for(String defect:List.of("registration","assignment","target","extra-target")) {
            var o=fixture("DB_DIRECT");
            switch(defect) {
                case "registration" -> registrations(o).removeLast();
                case "assignment" -> rows(o,"assignments").removeLast();
                case "target" -> deliveries(o).removeLast();
                case "extra-target" -> deliveries(o).add(row("event_id","unknown","registration_id","r1"));
            }
            assertThatThrownBy(()->M5TransportEvidence.reconcile(o,true)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void partialAndDuplicateObserverReceiptAreRejected() {
        for(String defect:List.of("missing","duplicate","wrong-tenant","receipt-before-done")) {
            var o=fixture("DB_DIRECT");
            switch(defect) {
                case "missing" -> projections(o).clear();
                case "duplicate" -> projections(o).add(new LinkedHashMap<>(projections(o).getFirst()));
                case "wrong-tenant" -> projections(o).getFirst().put("tenant_id","other");
                case "receipt-before-done" -> deliveries(o).getLast().put("state","PROCESSING");
            }
            assertThatThrownBy(()->M5TransportEvidence.reconcile(o,false)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void intakeEpochAndUnknownTenantQuarantineRemainCorrectnessSignals() {
        var o=fixture("KAFKA");
        assertThat(M5TransportEvidence.reconcile(o,true)).containsEntry("durableTargetIntakes",2);
        rows(o,"event_kafka_target_intakes").getFirst().put("authority_epoch",1L);
        assertThatThrownBy(()->M5TransportEvidence.reconcile(o,true)).hasMessageContaining("epoch");
        var q=fixture("DB_DIRECT");q.put("quarantine",List.of(Map.of("failure_code","UNKNOWN_ORIGINAL")));
        assertThatThrownBy(()->M5TransportEvidence.reconcile(q,true)).hasMessageContaining("quarantine");
    }
    private static Map<String,Object> fixture(String transport) {
        var tenant=row("tenantId","tenant","event_records",new ArrayList<>(List.of(row("event_id","e","tenant_id","tenant","event_type","waitlist.promotion-requested","schema_version",1,"boundary_sequence",3))),
            "registrations",new ArrayList<>(List.of(registration("r1","waitlist.promotion"),registration("r2","operations.event-observation"))),
            "event_deliveries",new ArrayList<>(List.of(delivery("r1"),delivery("r2"))));
        var projections=new ArrayList<>(List.of(row("tenant_id","tenant","event_id","e","registration_id","r2","event_type","waitlist.promotion-requested","intaken_at",transport.equals("KAFKA")?"2026-09-30T00:00:00Z":null)));
        var intake=new ArrayList<Map<String,Object>>();if(transport.equals("KAFKA"))for(String id:List.of("r1","r2"))intake.add(row("event_id","e","registration_id",id,"authority_epoch",2L));
        return row("tenants",List.of(tenant),"event_observation_projections",projections,"event_kafka_target_intakes",intake,"event_kafka_intake_records",List.of(),"assignments",new ArrayList<>(List.of(row("registration_id","r1","transport",transport,"authority_epoch",2L),row("registration_id","r2","transport",transport,"authority_epoch",2L))));
    }
    private static Map<String,Object> registration(String id,String consumer) {return row("registration_id",id,"consumer_id",consumer,"event_type","waitlist.promotion-requested","schema_version",1,"activation_boundary",0,"deactivation_boundary",null);}
    private static Map<String,Object> delivery(String registration) {return row("event_id","e","registration_id",registration,"state","DONE","lifetime_attempts",1L);}
    static Map<String,Object> row(Object...pairs) {var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1]);return result;}
    private static List<Map<String,Object>> rows(Map<String,Object>o,String key) {return M5TransportEvidence.maps(o.get(key));}
    private static List<Map<String,Object>> deliveries(Map<String,Object>o) {return rows(rows(o,"tenants").getFirst(),"event_deliveries");}
    private static List<Map<String,Object>> registrations(Map<String,Object>o) {return rows(rows(o,"tenants").getFirst(),"registrations");}
    private static List<Map<String,Object>> projections(Map<String,Object>o) {return rows(o,"event_observation_projections");}
}
