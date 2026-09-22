package com.slotq.experiments.waitlist;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class WaitlistProcessRecoveryEvidenceTests {
    @Test void bothSpringFixedDelayOverloadsRespectTheTestOnlyPause() throws Exception {
        WaitlistProcessRecoveryRunner.mode="RECOVER_BACKLOG";WaitlistProcessRecoveryRunner.ticks=false;
        var scheduler=new WaitlistProcessRecoveryRunner.ChildConfiguration().taskScheduler();scheduler.initialize();
        var calls=new AtomicInteger();var called=new CountDownLatch(2);
        Runnable work=()->{calls.incrementAndGet();called.countDown();};
        try {
            scheduler.scheduleWithFixedDelay(work,Duration.ofMillis(10));
            scheduler.scheduleWithFixedDelay(work,Instant.now(),Duration.ofMillis(10));
            Thread.sleep(60);assertThat(calls.get()).isZero();
            WaitlistProcessRecoveryRunner.ticks=true;assertThat(called.await(2,TimeUnit.SECONDS)).isTrue();
        } finally {WaitlistProcessRecoveryRunner.ticks=false;scheduler.shutdown();}
    }

    @Test void checkedInEvidenceUsesRealBusinessStateAcrossFivePhysicalProcessFaults() throws Exception {
        Path root=Path.of("").toAbsolutePath();if(!Files.isDirectory(root.resolve("docs")))root=root.getParent();
        var report=new ObjectMapper().readTree(Files.readString(root.resolve("docs/experiments/waitlist-recovery/report.json")));
        assertThat(report.path("schemaVersion").asText()).isEqualTo(WaitlistProcessRecoveryRunner.SCHEMA);
        var env=report.path("environment");
        assertThat(env.path("applicationRevision").asText()).isEqualTo("eee4948d0e7063adb80bd1cd56774cc9806767da");
        assertThat(env.has("dirty")).isTrue(); // Honest worktree provenance, not a fabricated clean revision.
        assertThat(env.path("productionSourceUnchanged").asBoolean()).isTrue();
        assertThat(env.path("javaVersion").asText()).startsWith("25.");
        assertThat(env.path("mysqlVersion").asText()).startsWith("8.4.");
        assertThat(env.path("transactionIsolation").asText()).isEqualTo("REPEATABLE-READ");
        assertThat(env.path("testConfiguration").path("productionSlo").asBoolean(true)).isFalse();
        for(String file:List.of("WaitlistProcessRecoveryRunner.java","WaitlistRecoveryDatabase.java")) {
            String source=Files.readString(root.resolve("backend/src/test/java/com/slotq/experiments/waitlist/"+file)).replace("\r\n","\n");
            // Evidence preserves exact execution bytes; Git checkout may convert LF <-> CRLF.
            var hashes=new ArrayList<String>();
            for(String text:List.of(source,source.replace("\n","\r\n"))) hashes.add(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            assertThat(env.path("harnessSourceSha256").path(file).asText()).isIn(hashes);
        }
        assertThat(report.path("summary").path("complete").asBoolean()).isTrue();
        assertThat(report.path("summary").path("passed").asInt()).isEqualTo(5);
        Map<String,JsonNode> cases=new HashMap<>();
        for(var row:report.path("cases")) {
            assertThat(cases.put(row.path("name").asText(),row)).isNull();
            assertThat(row.path("outcome").asText()).isEqualTo("PASS");
            assertThat(row.path("recoveryExitCode").asInt(-1)).isZero();
            assertThat(row.path("firstAuthoritativeState").path("registrations"))
                .isEqualTo(row.path("recoveryAuthoritativeState").path("registrations"));
            assertCorrespondence(row.path("recoveryAuthoritativeState"));
        }
        assertThat(cases).containsOnlyKeys("RELEASE_CRASH","EFFECT_CRASH","COMMIT_CRASH","BACKLOG_SEED","OUTAGE_SEED");
        var release=cases.get("RELEASE_CRASH");assertThat(release.path("childExitCode").asInt()).isEqualTo(86);
        assertThat(stat(first(release),"event_records")).isEqualTo(1);assertThat(stat(first(release),"event_deliveries")).isZero();
        assertThat(first(release).path("reservations").get(0).path("expires_at").asText()).isEqualTo("2026-08-30T09:05:00Z");
        assertThat(first(release).path("event_records").get(0).path("occurred_at").asText()).isEqualTo("2026-08-30T09:00:00Z");
        assertThat(stat(first(release),"activeAllocations")).isZero();single(last(release),1);
        var effect=cases.get("EFFECT_CRASH");assertThat(effect.path("childExitCode").asInt()).isEqualTo(87);
        assertThat(stat(effect.path("uncommittedFaultDiagnostic"),"promoted")).isEqualTo(1);
        assertThat(stat(first(effect),"processing")).isEqualTo(1);assertThat(stat(first(effect),"reservations")).isEqualTo(1);
        assertThat(stat(first(effect),"capacity_allocations")).isEqualTo(1);assertThat(stat(first(effect),"waiting")).isEqualTo(1);
        for(String table:List.of("waitlist_offers","waitlist_promotion_receipts","waitlist_notification_requests"))assertThat(first(effect).path(table)).isEmpty();
        single(last(effect),2);
        var commit=cases.get("COMMIT_CRASH");assertThat(commit.path("childExitCode").asInt()).isEqualTo(88);
        single(first(commit),1);single(last(commit),1);
        for(String table:WaitlistRecoveryDatabase.TABLES)assertThat(first(commit).path(table)).isEqualTo(last(commit).path(table));
        for(String name:List.of("BACKLOG_SEED","OUTAGE_SEED")) {
            var row=cases.get(name);var before=first(row);var progress=row.path("progressAuthoritativeState");var after=last(row);
            assertThat(stat(before,"dueHeld")).isEqualTo(6);assertThat(stat(before,"pendingOffers")).isEqualTo(3);
            assertThat(stat(before,"dueWaiting")).isEqualTo(3);assertThat(stat(before,"waiting")).isEqualTo(12);
            assertThat(stat(progress,"pendingOffers")).isEqualTo(9);assertThat(stat(progress,"waitlist_offers")).isEqualTo(12);
            assertThat(stat(progress,"waitlist_promotion_requests")).isGreaterThanOrEqualTo(3);
            assertThat(stat(after,"waitlist_offers")).isEqualTo(12);assertThat(stat(after,"waitlist_notification_requests")).isEqualTo(12);
            assertThat(stat(after,"expiredEntries")).isEqualTo(15);
            for(String counter:List.of("dueHeld","dueWaiting","pendingOffers","waiting","activeAllocations","held"))assertThat(stat(after,counter)).isZero();
        }
        var outage=cases.get("OUTAGE_SEED");var proof=outage.path("databaseOutageProbe");
        assertThat(outage.path("childExitCode").asInt()).isEqualTo(90);
        assertThat(proof.path("contextReadyBeforePause").asBoolean()).isTrue();
        assertThat(proof.path("databaseAccessFailureObserved").asBoolean()).isTrue();
        assertThat(proof.path("entryPoint").asText()).isEqualTo("EventDeliveryWorker.runCycle");
        assertThat(proof.path("failureType").asText()).matches("^(java\\.sql\\.|org\\.springframework\\.(dao|jdbc)\\.|com\\.mysql\\.).+");
        for(String table:WaitlistRecoveryDatabase.TABLES)assertThat(first(outage).path(table)).isEqualTo(outage.path("afterOutageAuthoritativeState").path(table));
        var claimed=find(first(outage).path("event_deliveries"),"state","PROCESSING");
        var recovered=find(last(outage).path("event_deliveries"),"event_id",claimed.path("event_id").asText());
        assertThat(claimed.path("cycle_attempts").asInt()).isEqualTo(1);
        assertThat(recovered.path("cycle_attempts").asInt()).isEqualTo(2);
        assertThat(recovered.path("fencing_token").asLong()).isEqualTo(claimed.path("fencing_token").asLong()+1);
    }
    private static void assertCorrespondence(JsonNode s) {
        assertThat(s.path("event_records").size()).isEqualTo(s.path("event_deliveries").size()).isEqualTo(s.path("waitlist_promotion_receipts").size());
        assertThat(stat(s,"duplicateLogicalEffects")).isZero();assertThat(stat(s,"occupancyViolations")).isZero();
        assertThat(stat(s,"dead")).isZero();assertThat(stat(s,"partialReceipts")).isZero();assertThat(stat(s,"unfinishedRequestLinks")).isZero();
        for(var event:s.path("event_records")) {
            String id=event.path("event_id").asText();var delivery=find(s.path("event_deliveries"),"event_id",id);
            assertThat(delivery.path("state").asText()).isEqualTo("DONE");find(s.path("waitlist_promotion_receipts"),"event_id",id);
        }
        for(var offer:s.path("waitlist_offers")) {
            String id=offer.path("id").asText();var receipt=find(s.path("waitlist_promotion_receipts"),"offer_id",id);
            assertThat(receipt.path("outcome").asText()).isEqualTo("PROMOTED");
            find(s.path("waitlist_notification_requests"),"offer_id",id);
            find(s.path("reservations"),"id",offer.path("reservation_id").asText());
            find(s.path("capacity_allocations"),"reservation_id",offer.path("reservation_id").asText());
            find(s.path("waitlist_entries"),"id",offer.path("entry_id").asText());
        }
        for(var link:s.path("waitlist_promotion_requests")) if(!link.path("last_event_id").isNull())
            find(s.path("waitlist_promotion_receipts"),"event_id",link.path("last_event_id").asText());
    }
    private static JsonNode find(JsonNode rows,String key,String value) {
        var found=new ArrayList<JsonNode>();for(var row:rows)if(row.path(key).asText().equals(value))found.add(row);
        assertThat(found).as(key+"="+value).hasSize(1);return found.getFirst();
    }
    private static void single(JsonNode s,int attempts) {
        assertCorrespondence(s);assertThat(stat(s,"promoted")).isEqualTo(1);assertThat(stat(s,"event_records")).isEqualTo(1);
        assertThat(s.path("event_deliveries").get(0).path("cycle_attempts").asInt()).isEqualTo(attempts);
        assertThat(s.path("event_deliveries").get(0).path("fencing_token").asInt()).isEqualTo(attempts);
    }
    private static JsonNode first(JsonNode row){return row.path("firstAuthoritativeState");}
    private static JsonNode last(JsonNode row){return row.path("recoveryAuthoritativeState");}
    private static long stat(JsonNode s,String key){return s.path("stats").path(key).asLong(-1);}
}
