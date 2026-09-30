package com.slotq.experiments.waitlist;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import static com.slotq.experiments.waitlist.M5TransportComparisonRunner.*;
import static com.slotq.experiments.waitlist.M5TransportEvidence.*;
import static com.slotq.experiments.waitlist.WaitlistRecoveryDatabase.*;

/** Independent durable-row reconciliation and required transition checks; logs are not a PASS oracle. */
final class M5OperationsDrillEvidence {
    static Map<String,Object> summarize(Map<String,Object> raw) {
        var timeline=maps(raw.get("timeline"));
        for(String phase:List.of("healthy","dashboard-query","trace-query","linked-attempt-search","metrics-only-network-boundary","observer-down-waitlist-done","observer-recovered","waitlist-dead-observer-done","handler-cause-corrected","human-recovery-admitted","human-recovery-completed","broker-unavailable","broker-recovered","publication-ack-unknown","publication-unknown-expired-lease","publication-redelivery-converged","before-db-outage","database-outage-observed","database-recovered","quiesced-before-rollback","quiesced-after-rollback","manual-kafka-db-rollback","db-direct-after-rollback","db-observer-down-waitlist-done","db-observer-recovered","db-waitlist-dead-observer-done","db-human-recovery-admitted","db-human-recovery-completed","db-direct-converged"))require(timeline.stream().anyMatch(t->phase.equals(t.get("phase"))),"missing drill phase "+phase);
        var originals=new HashMap<String,Map<String,Object>>();
        for(var observation:timeline) if(observation.containsKey("tenants")) for(var tenant:maps(observation.get("tenants"))) {
            for(var e:list(tenant,"event_records")) {
                var prior=originals.putIfAbsent(e.get("event_id").toString(),e);require(prior==null||prior.equals(e),"original event changed during drill");
            }
            for(String table:List.of("waitlist_promotion_receipts","waitlist_offers","waitlist_notification_requests")) {
                String key=table.equals("waitlist_promotion_receipts")?"event_id":table.equals("waitlist_offers")?"entry_id":"offer_id";
                require(duplicates(tenant,table,key)==0,"duplicate logical business effect");
            }
            for(var receipt:list(tenant,"waitlist_promotion_receipts"))require(receipt.get("outcome")!=null&&list(tenant,"event_deliveries").stream().anyMatch(d->Objects.equals(d.get("event_id"),receipt.get("event_id"))&&"DONE".equals(d.get("state"))&&list(tenant,"registrations").stream().anyMatch(r->Objects.equals(r.get("registration_id"),d.get("registration_id"))&&WAITLIST.equals(r.get("consumer_id")))),"partial Waitlist receipt/DONE");
            for(var receipt:maps(observation.get("event_observation_projections")))if(receipt.get("tenant_id").equals(tenant.get("tenantId").toString().replace("-","")))require(list(tenant,"event_deliveries").stream().anyMatch(d->Objects.equals(d.get("event_id"),receipt.get("event_id"))&&"DONE".equals(d.get("state"))&&list(tenant,"registrations").stream().anyMatch(r->Objects.equals(r.get("registration_id"),d.get("registration_id"))&&OBSERVER.equals(r.get("consumer_id")))),"partial observer receipt/DONE");
            Instant now=Instant.parse(tenant.get("businessNow").toString());
            for(var slot:list(tenant,"slot_inventories")) {
                long occupied=list(tenant,"capacity_allocations").stream().filter(a->Boolean.TRUE.equals(a.get("active"))&&Objects.equals(a.get("slot_inventory_id"),slot.get("id"))&&list(tenant,"reservations").stream().anyMatch(r->Objects.equals(r.get("id"),a.get("reservation_id"))&&(Set.of("CONFIRMED","CHECKED_IN").contains(r.get("state"))||("HELD".equals(r.get("state"))&&Instant.parse(r.get("expires_at").toString()).isAfter(now))))).mapToLong(a->number(a,"units")).sum();
                require(occupied<=number(slot,"capacity"),"effective capacity violation");
            }
        }
        var end=timeline.stream().filter(t->"db-direct-converged".equals(t.get("phase"))).findFirst().orElseThrow();
        long done=0,events=0;
        for(var tenant:maps(end.get("tenants")))for(var event:list(tenant,"event_records")) {
            events++;for(String consumer:CONSUMERS) {
                var members=list(tenant,"registrations").stream().filter(r->consumer.equals(r.get("consumer_id"))&&member(event,r)).toList();require(members.size()==1,"original membership missing");
                var registration=members.getFirst();var deliveries=list(tenant,"event_deliveries").stream().filter(d->Objects.equals(d.get("event_id"),event.get("event_id"))&&Objects.equals(d.get("registration_id"),registration.get("registration_id"))).toList();
                require(deliveries.size()==1&&"DONE".equals(deliveries.getFirst().get("state")),"unexplained original loss or remaining DEAD");done++;
                var assignment=maps(end.get("event_transport_assignments")).stream().filter(a->Objects.equals(a.get("registration_id"),registration.get("registration_id"))).toList();require(assignment.size()==1&&"DB_DIRECT".equals(assignment.getFirst().get("transport"))&&number(assignment.getFirst(),"authority_epoch")==number(end,"authorityEpoch"),"rollback authority missing");
                var receipts=consumer.equals(WAITLIST)?list(tenant,"waitlist_promotion_receipts"):maps(end.get("event_observation_projections"));require(receipts.stream().filter(r->Objects.equals(r.get("tenant_id"),event.get("tenant_id"))&&Objects.equals(r.get("event_id"),event.get("event_id"))).count()==1,"missing/duplicate logical receipt");
            }
        }
        require(events==originals.size(),"historical original lost");
        require(maps(end.get("event_kafka_intake_records")).stream().noneMatch(i->"QUARANTINED".equals(i.get("disposition"))),"unexplained quarantine");
        require(maps(end.get("event_kafka_publications")).stream().allMatch(p->"PUBLISHED".equals(p.get("state"))),"unresolved publication responsibility");
        var redelivery=phase(timeline,"publication-redelivery-converged");String duplicateId=redelivery.get("eventId").toString().replace("-","");
        require(maps(redelivery.get("event_kafka_intake_records")).stream().filter(i->duplicateId.equals(i.get("event_id"))).count()>=4,"physical redelivery not demonstrated");
        var before=phase(timeline,"quiesced-before-rollback");var after=phase(timeline,"quiesced-after-rollback");
        for(var tenant:maps(before.get("tenants"))) {
            var preserved=maps(after.get("tenants")).stream().filter(t->Objects.equals(t.get("tenantId"),tenant.get("tenantId"))).findFirst().orElseThrow();
            for(String table:List.of("event_records","event_deliveries","waitlist_promotion_receipts","waitlist_offers"))require(list(tenant,table).equals(list(preserved,table)),"rollback changed original target/attempt/receipt/business outcome");
        }
        var audit=maps(end.get("operations_recovery_audit"));var operations=maps(end.get("operations_recovery_operations"));
        require(audit.size()==2&&operations.size()==2,"human recovery duplicate/missing audit");
        for(var operation:operations){var matching=audit.stream().filter(a->Objects.equals(a.get("operation_id"),operation.get("operation_id"))).toList();require(matching.size()==1&&Objects.equals(matching.getFirst().get("result_json"),operation.get("result_json")),"operation/audit result differs");}
        for(String prefix:List.of("","db-")){
            var admitted=phase(timeline,prefix+"human-recovery-admitted");require(admitted.get("admission").equals(admitted.get("sameOperationRetry")),"duplicate recovery cycle");
            var stopped=phase(timeline,prefix+"observer-down-waitlist-done");require(state(stopped,WAITLIST).equals("DONE")&&!state(stopped,OBSERVER).equals("DONE"),"observer fault did not isolate Waitlist progress");
            var failure=phase(timeline,prefix+"waitlist-dead-observer-done");require(state(failure,WAITLIST).equals("DEAD")&&state(failure,OBSERVER).equals("DONE"),"Waitlist fault did not isolate observer progress");
        }
        var alerts=timeline.stream().filter(t->"alert-transition".equals(t.get("phase"))).toList();
        for(String alert:List.of("SlotqExecutionRuntimeUnavailable","SlotqConsumerDeadDelivery","SlotqDeadDelivery","SlotqKafkaLagUnavailable","SlotqPublicationClaimExpired","SlotqDatabaseSampleUnavailable"))for(boolean firing:List.of(true,false))require(alerts.stream().anyMatch(t->alert.equals(t.get("alert"))&&Objects.equals(t.get("firing"),firing)),"alert firing/clear missing "+alert);
        var intervals=new TreeMap<String,Object>();
        for(var pair:Map.of("observer",List.of("observer-stop","observer-recovered"),"waitlistHuman",List.of("waitlist-fault-input","human-recovery-completed"),"broker",List.of("broker-stop","broker-recovered"),"publicationUnknown",List.of("publication-ack-unknown","publication-redelivery-converged"),"database",List.of("database-pause","database-recovered"),"rollback",List.of("quiesced-before-rollback","db-direct-after-rollback"),"dbObserver",List.of("db-observer-stop","db-observer-recovered"),"dbWaitlistHuman",List.of("db-waitlist-fault-input","db-human-recovery-completed")).entrySet()) {
            var start=phase(timeline,pair.getValue().getFirst());var finish=phase(timeline,pair.getValue().getLast());intervals.put(pair.getKey(),Map.of("startAt",start.get("at"),"endAt",finish.get("at"),"observedConvergenceMs",(number(finish,"nanos")-number(start,"nanos"))/1_000_000.0));
        }
        var result=new LinkedHashMap<String,Object>();result.put("result","PASS");result.put("originalEvents",events);result.put("doneTargets",done);result.put("unexplainedLoss",0);result.put("duplicateEffect",0);result.put("partialReceiptDone",0);result.put("effectiveCapacityViolation",0);result.put("remainingDeadQuarantine",0);result.put("humanOperations",operations.size());result.put("humanAudit",audit.size());result.put("alerts",alerts);result.put("recoveryIntervals",intervals);result.put("bookingApi",timeline.stream().filter(t->"booking-http".equals(t.get("phase"))).map(t->Map.of("during",t.get("during"),"status",t.get("status"))).toList());result.put("authorityEpoch",end.get("authorityEpoch"));result.put("transport",end.get("transport"));result.put("fifoCurrentState","real single eligible waiting entry in each drill fixture; multi-candidate FIFO/current-state guards separately exercised by repeated comparison trace");return result;
    }
    private static Map<String,Object> phase(List<Map<String,Object>> timeline,String phase){return timeline.stream().filter(t->phase.equals(t.get("phase"))).findFirst().orElseThrow();}
    private static String state(Map<String,Object> observation,String consumer){String event=observation.get("eventId").toString().replace("-","");for(var tenant:maps(observation.get("tenants")))for(var d:list(tenant,"event_deliveries"))if(event.equals(d.get("event_id"))&&list(tenant,"registrations").stream().anyMatch(r->Objects.equals(r.get("registration_id"),d.get("registration_id"))&&consumer.equals(r.get("consumer_id"))))return d.get("state").toString();return "UNMATERIALIZED";}
    static void verify(Path folder) throws Exception {
        var raw=M5CanonicalEvidence.read(folder);var summary=summarize(raw);require(JSON.readTree(Files.readString(folder.resolve("summary.json"))).equals(JSON.readTree(JSON.writeValueAsString(summary))),"drill raw -> summary mismatch");
        var hashes=new TreeMap<String,String>();for(Path file:List.of(folder.resolve("manifest.json"),M5CanonicalEvidence.rawPath(folder),folder.resolve("summary.json")))hashes.put(file.getFileName().toString(),M5CanonicalEvidence.sha(Files.readAllBytes(file)));
        Path integrity=folder.resolve("integrity.json");if(Files.exists(integrity)){Map<String,Object> prior=JSON.readValue(Files.readString(integrity),new TypeReference<>(){});require(hashes.equals(map(prior.get("sha256"))),"drill file integrity mismatch");}
        write(integrity,Map.of("result","PASS","sha256",hashes));System.out.println("#111 drill raw -> summary PASS");
    }
}
