package com.slotq.experiments.waitlist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class WaitlistBaselineEvidenceTests {
    private static final String NOW = "2026-09-26T09:00:00Z", LATER = "2026-09-26T10:00:00Z";
    @TempDir Path directory;

    @Test void recalculatesRawCountsAndTimingWithoutTrustingSnapshotStatsOrProvidedSummary() throws Exception {
        var raw = fixture();
        raw.put("summary", Map.of("complete", false));
        snapshot(raw).put("stats", Map.of("promoted", 999, "done", 999));
        var summary = WaitlistBaselineEvidence.summarize(raw);
        assertThat(summary.get("oracle")).isEqualTo("PASS");
        assertThat(summary.get("complete")).isEqualTo(true);
        assertThat(counts(summary)).containsEntry("originalEvents", 2L).containsEntry("originalMembershipTargets", 2L)
            .containsEntry("doneTargets", 2L).containsEntry("promoted", 1L).containsEntry("normalNoOps", 1L)
            .containsEntry("lifetimeAttempts", 3L).containsEntry("retryAttempts", 1L).containsEntry("retriedTargets", 1L);
        assertThat(summary.get("successfulCommandInvocations")).isEqualTo(1L);
        assertThat(object(summary.get("commandLatency"))).containsEntry("p50", 2.0).containsEntry("p95", 2.0);
        var phase = object(object(summary.get("phases")).get("drain"));
        assertThat(phase).containsEntry("claimedAttempts", 2L);
        Path csv = directory.resolve("correspondence.csv");
        WaitlistBaselineEvidence.writeCsv(csv, raw);
        assertThat(Files.readString(csv)).contains("NO_CAPACITY", "PROMOTED", "registration", "event-noop");
        assertThat(Files.readAllLines(csv)).hasSize(3);
    }

    @Test void measuresDrainBoundWhenPreWorkerBacklogBecomesDrained() {
        var raw = fixture();
        var before = fixture();
        table(before, "event_deliveries").removeLast();
        table(before, "waitlist_promotion_receipts").removeLast();
        snapshot(before).put("discoveryCursor", 2L);
        prependObservation(raw, before, 0L, 1_000_000L);
        var phase = object(object(WaitlistBaselineEvidence.summarize(raw).get("phases")).get("drain"));
        assertThat(object(phase.get("firstCounts"))).containsEntry("outstandingTargets", 1L);
        assertThat(object(phase.get("lastCounts"))).containsEntry("outstandingTargets", 0L);
        assertThat(phase).containsEntry("drainObservedUpperBoundMs", 8.0).containsKey("drainBoundOrigin");
    }

    @Test void idleWorkerCyclesDoNotCreateDrainLatencyWhenPhaseStartsDrained() {
        var raw = fixture();
        prependObservation(raw, fixture(), 0L, 1_000_000L);
        raw.put("cycles", List.of(row("phase", "drain", "startNanos", 2_000_000L, "endNanos", 8_000_000L, "claimed", 0)));
        var phase = object(object(WaitlistBaselineEvidence.summarize(raw).get("phases")).get("drain"));
        assertThat(phase).containsEntry("workerCycles", 1).containsEntry("claimedAttempts", 0L)
            .containsEntry("workerWindowMs", 6.0).doesNotContainKeys("drainObservedUpperBoundMs", "drainBoundOrigin");
    }

    @Test void drainBoundRequiresBacklogObservationCompletedBeforeWorkerStart() {
        var raw = fixture();
        var before = fixture();
        table(before, "event_deliveries").removeLast();
        table(before, "waitlist_promotion_receipts").removeLast();
        snapshot(before).put("discoveryCursor", 2L);
        prependObservation(raw, before, 1_000_000L, 3_000_000L);
        assertThat(object(object(WaitlistBaselineEvidence.summarize(raw).get("phases")).get("drain")))
            .doesNotContainKeys("drainObservedUpperBoundMs", "drainBoundOrigin");
        raw = fixture();
        assertThat(object(object(WaitlistBaselineEvidence.summarize(raw).get("phases")).get("drain")))
            .doesNotContainKeys("drainObservedUpperBoundMs", "drainBoundOrigin");
    }

    @Test void alreadyDrainedLatestPreWorkerObservationDoesNotReuseEarlierBacklog() {
        var raw = fixture();
        prependObservation(raw, fixture(), 1_000_000L, 1_500_000L);
        var before = fixture();
        table(before, "event_deliveries").removeLast(); table(before, "waitlist_promotion_receipts").removeLast();
        snapshot(before).put("discoveryCursor", 2L);
        prependObservation(raw, before, 0L, 500_000L);
        assertThat(object(object(WaitlistBaselineEvidence.summarize(raw).get("phases")).get("drain")))
            .doesNotContainKeys("drainObservedUpperBoundMs", "drainBoundOrigin");
    }

    @Test void undiscoveredMembershipIsOutstandingAndCsvPreservesIt() throws Exception {
        var raw = fixture();
        table(raw, "event_deliveries").removeIf(d -> "event-noop".equals(d.get("event_id")));
        table(raw, "waitlist_promotion_receipts").removeIf(d -> "event-noop".equals(d.get("event_id")));
        snapshot(raw).put("discoveryCursor", 2L);
        var summary = WaitlistBaselineEvidence.summarize(raw);
        assertThat(summary.get("complete")).isEqualTo(false);
        assertThat(counts(summary)).containsEntry("undiscoveredTargets", 1L).containsEntry("outstandingTargets", 1L);
        Path csv = directory.resolve("outstanding.csv"); WaitlistBaselineEvidence.writeCsv(csv, raw);
        assertThat(Files.readString(csv)).contains("UNDISCOVERED");
    }

    @Test void registrationMembershipUsesOpenBoundaryIntervalAndOriginalRouteVersion() {
        var raw = fixture();
        var registration = table(raw, "registrations").getFirst();
        registration.put("activation_boundary", 2L);
        fails(raw, "registration");
        registration.put("activation_boundary", 1L); registration.put("deactivation_boundary", 2L);
        fails(raw, "registration");
        registration.put("deactivation_boundary", null); registration.put("schema_version", 2);
        fails(raw, "registration");
    }
    @Test void rejectsDiscoveryCursorAdvancingPastMissingOriginalTarget() {
        var raw = fixture(); table(raw, "event_deliveries").removeLast(); table(raw, "waitlist_promotion_receipts").removeLast();
        fails(raw, "discovery cursor passed");
    }
    @Test void committedLogicalReceiptCannotChangeAcrossObservations() {
        var raw = fixture(); var later = fixture();
        table(later, "waitlist_promotion_receipts").getLast().put("outcome", "NO_CANDIDATE");
        raw.put("observations", List.of(((List<?>)raw.get("observations")).getFirst(), ((List<?>)later.get("observations")).getFirst()));
        fails(raw, "completed logical receipt changed");
    }

    @Test void rejectsDuplicateLogicalReceiptRegardlessOfDeliveryAttemptOrToken() {
        var raw = fixture(); table(raw, "waitlist_promotion_receipts").add(new LinkedHashMap<>(table(raw, "waitlist_promotion_receipts").getFirst()));
        fails(raw, "duplicate identity/effect");
    }
    @Test void rejectsDuplicateOfferEffectAcrossDistinctEvents() {
        var raw = fixture(); var receipt = table(raw, "waitlist_promotion_receipts").getLast();
        receipt.put("outcome", "PROMOTED"); receipt.put("offer_id", "offer"); receipt.put("entry_id", "entry"); receipt.put("reservation_id", "reservation");
        fails(raw, "duplicate logical promotion effect");
    }
    @Test void rejectsCommittedPartialReceipt() {
        var raw = fixture(); table(raw, "waitlist_promotion_receipts").getFirst().put("outcome", null); fails(raw, "partial receipt");
    }
    @Test void rejectsDoneWithoutReceiptAndEffectWithoutDone() {
        var raw = fixture(); table(raw, "waitlist_promotion_receipts").removeLast(); fails(raw, "DONE without atomic receipt");
        raw = fixture(); table(raw, "event_deliveries").getFirst().put("state", "PROCESSING"); fails(raw, "receipt/effect without atomic DONE");
    }
    @Test void permitsEveryDocumentedNormalNoOpWithoutInventingAnOffer() {
        for (String outcome : List.of("NO_CAPACITY", "NO_CANDIDATE", "NOT_ELIGIBLE", "SLOT_PAST", "DEFERRED")) {
            var raw = fixture(); table(raw, "waitlist_promotion_receipts").getLast().put("outcome", outcome);
            assertThat(counts(WaitlistBaselineEvidence.summarize(raw))).containsEntry("promoted", 1L).containsEntry("normalNoOps", 1L);
        }
    }
    @Test void rejectsNoOpWithAnEffect() {
        var raw = fixture(); table(raw, "waitlist_promotion_receipts").getLast().put("offer_id", "offer"); fails(raw, "normal no-op has business effect");
    }
    @Test void rejectsReceiptWithDifferentImmutableSignalSourceTimestampOrPayload() {
        var raw = fixture(); table(raw, "waitlist_promotion_receipts").getFirst().put("signal_type", "PROMOTION_REQUESTED"); fails(raw, "immutable signal");
        raw = fixture(); table(raw, "waitlist_promotion_receipts").getFirst().put("source_id", "other"); fails(raw, "immutable source");
        raw = fixture(); table(raw, "waitlist_promotion_receipts").getFirst().put("occurred_at", LATER); fails(raw, "immutable occurredAt");
        raw = fixture(); table(raw, "waitlist_promotion_receipts").getFirst().put("resource_id", "other"); fails(raw, "immutable payload scope");
        raw = fixture(); table(raw, "waitlist_promotion_receipts").getFirst().put("from_state", "CONFIRMED"); fails(raw, "immutable transition");
    }
    @Test void rejectsCrossTenantRowAndCrossScopeJoin() {
        var raw = fixture(); table(raw, "capacity_allocations").getFirst().put("tenant_id", "other"); fails(raw, "cross-tenant");
        raw = fixture(); table(raw, "waitlist_notification_requests").getFirst().put("venue_id", "other"); fails(raw, "scope/link differs");
    }
    @Test void countsEffectiveCapacityAtExactlyTheObservationTime() {
        var raw = fixture(); addOrdinaryReservation(raw, "competitor", LATER); fails(raw, "effective capacity invariant");
        raw = fixture(); addOrdinaryReservation(raw, "expired-competitor", NOW);
        assertThat(WaitlistBaselineEvidence.summarize(raw).get("oracle")).isEqualTo("PASS");
        snapshot(raw).put("businessNow", LATER); fails(raw, "verificationNow");
    }
    @Test void rejectsMissingOfferLinksAndLifecycleMismatch() {
        var raw = fixture(); table(raw, "waitlist_notification_requests").clear(); fails(raw, "Offer without notification");
        raw = fixture(); table(raw, "waitlist_entries").getFirst().put("state", "WAITING"); fails(raw, "Offer/Entry partial lifecycle");
        raw = fixture(); table(raw, "capacity_allocations").clear(); fails(raw, "missing authoritative link");
        raw = fixture(); table(raw, "reservations").getFirst().put("promotional_request_id", "other"); fails(raw, "promotional Reservation identity");
    }
    @Test void deadPendingAndProcessingAreCountedWithoutPretendingTheyAreEffects() {
        for (String state : List.of("DEAD", "PENDING", "PROCESSING")) {
            var raw = fixture(); table(raw, "event_deliveries").getLast().put("state", state);
            table(raw, "waitlist_promotion_receipts").removeLast();
            var summary = WaitlistBaselineEvidence.summarize(raw);
            assertThat(summary.get("complete")).isEqualTo(false);
            assertThat(counts(summary)).containsEntry("outstandingTargets", 1L).containsEntry("promoted", 1L);
        }
    }

    private static void addOrdinaryReservation(Map<String,Object> raw, String id, String expiresAt) {
        var reservation = new LinkedHashMap<>(table(raw, "reservations").getFirst());
        reservation.put("id", id); reservation.put("expires_at", expiresAt); reservation.put("promotional_request_id", null);
        table(raw, "reservations").add(reservation);
        var allocation = new LinkedHashMap<>(table(raw, "capacity_allocations").getFirst());
        allocation.put("id", "allocation-" + id); allocation.put("reservation_id", id); table(raw, "capacity_allocations").add(allocation);
    }
    private static void fails(Map<String,Object> raw, String message) {
        assertThatThrownBy(() -> WaitlistBaselineEvidence.summarize(raw)).isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
    }
    private static void prependObservation(Map<String,Object> raw, Map<String,Object> before, long start, long end) {
        var observation = object(((List<?>)before.get("observations")).getFirst());
        observation.put("startNanos", start); observation.put("endNanos", end);
        var observations = new ArrayList<Object>((List<?>)raw.get("observations"));
        observations.addFirst(observation); raw.put("observations", observations);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
    private static Map<String,Object> counts(Map<String,Object> summary) { return object(summary.get("finalCounts")); }
    private static Map<String,Object> snapshot(Map<String,Object> raw) {
        return object(((List<?>)object(((List<?>)raw.get("observations")).getFirst()).get("tenants")).getFirst());
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> table(Map<String,Object> raw, String name) {
        return (List<Map<String,Object>>)snapshot(raw).get(name);
    }
    private static Map<String,Object> row(Object... fields) {
        var row = new LinkedHashMap<String,Object>(); for (int i = 0; i < fields.length; i += 2) row.put((String)fields[i], fields[i + 1]); return row;
    }
    private static Map<String,Object> scoped(Object... fields) {
        var row = row("tenant_id", "tenant", "venue_id", "venue", "resource_id", "resource", "slot_inventory_id", "slot");
        row.putAll(row(fields)); return row;
    }
    private static Map<String,Object> fixture() {
        var snapshot = row("tenantId", "tenant", "businessNow", NOW, "discoveryCursor", 3L);
        for (String table : WaitlistRecoveryDatabase.TABLES) snapshot.put(table, new ArrayList<Map<String,Object>>());
        snapshot.put("registrations", new ArrayList<>(List.of(row("registration_id", "registration", "consumer_id", "waitlist.promotion",
            "event_type", "booking.capacity-released", "schema_version", 1, "activation_boundary", 1L, "deactivation_boundary", null))));
        var raw = row("schemaVersion", WaitlistBaselineEvidence.SCHEMA, "manifest", Map.of("seed", 105),
            "observations", List.of(row("phase", "drain", "verificationNow", NOW, "startNanos", 8_000_000L, "endNanos", 10_000_000L, "tenants", List.of(snapshot))),
            "commands", List.of(row("type", "release", "committed", true, "startNanos", 0L, "endNanos", 2_000_000L)),
            "cycles", List.of(row("phase", "drain", "startNanos", 2_000_000L, "endNanos", 8_000_000L, "claimed", 2)));
        table(raw, "event_records").add(event("event", 2L));
        table(raw, "event_records").add(event("event-noop", 3L));
        table(raw, "event_deliveries").add(row("tenant_id", "tenant", "event_id", "event", "registration_id", "registration", "state", "DONE", "lifetime_attempts", 2L, "cycle_attempts", 2, "fencing_token", 2L));
        table(raw, "event_deliveries").add(row("tenant_id", "tenant", "event_id", "event-noop", "registration_id", "registration", "state", "DONE", "lifetime_attempts", 1L, "cycle_attempts", 1, "fencing_token", 1L));
        table(raw, "waitlist_promotion_receipts").add(receipt("event", "PROMOTED", "offer", "entry", "reservation"));
        table(raw, "waitlist_promotion_receipts").add(receipt("event-noop", "NO_CAPACITY", null, null, null));
        table(raw, "slot_inventories").add(scoped("id", "slot", "capacity", 1, "starts_at", "2026-09-26T11:00:00Z", "ends_at", "2026-09-26T12:00:00Z"));
        table(raw, "waitlist_demands").add(scoped("id", "demand", "party_size", 2, "starts_at", "2026-09-26T11:00:00Z", "ends_at", "2026-09-26T12:00:00Z"));
        table(raw, "waitlist_entries").add(scoped("id", "entry", "customer_principal_id", "customer", "demand_id", "demand", "state", "OFFERED"));
        table(raw, "reservations").add(scoped("id", "reservation", "customer_principal_id", "customer", "party_size", 2, "state", "HELD", "expires_at", LATER, "promotional_request_id", "entry", "promotional_confirmed", false));
        table(raw, "capacity_allocations").add(scoped("id", "allocation", "reservation_id", "reservation", "units", 1, "active", true));
        table(raw, "reservations").add(scoped("id", "source-reservation", "customer_principal_id", "ordinary", "party_size", 2, "state", "CANCELLED", "expires_at", LATER, "promotional_request_id", null, "promotional_confirmed", false));
        table(raw, "capacity_allocations").add(scoped("id", "source-allocation", "reservation_id", "source-reservation", "units", 1, "active", false));
        table(raw, "waitlist_offers").add(scoped("id", "offer", "customer_principal_id", "customer", "demand_id", "demand", "entry_id", "entry", "reservation_id", "reservation", "state", "PENDING", "expires_at", LATER));
        table(raw, "waitlist_notification_requests").add(scoped("offer_id", "offer", "entry_id", "entry", "reservation_id", "reservation", "request_type", "OFFER_AVAILABLE", "expires_at", LATER));
        return raw;
    }
    private static Map<String,Object> event(String id, long boundary) {
        return row("tenant_id", "tenant", "event_id", id, "event_type", "booking.capacity-released", "schema_version", 1,
            "boundary_sequence", boundary, "aggregate_type", "Reservation", "aggregate_id", "source-reservation", "occurred_at", NOW,
            "payload", "{\"venueId\":\"venue\",\"resourceId\":\"resource\",\"slotInventoryId\":\"slot\",\"fromState\":\"HELD\",\"toState\":\"CANCELLED\"}");
    }
    private static Map<String,Object> receipt(String event, String outcome, String offer, String entry, String reservation) {
        return scoped("consumer_id", "waitlist.promotion", "event_id", event, "outcome", outcome, "offer_id", offer,
            "entry_id", entry, "reservation_id", reservation, "signal_type", "CAPACITY_RELEASED", "source_id", "source-reservation",
            "occurred_at", NOW, "from_state", "HELD", "to_state", "CANCELLED");
    }
}
