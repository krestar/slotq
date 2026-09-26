package com.slotq.experiments.waitlist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Test-classpath-only raw-table oracle and deterministic summary calculator for #105. */
final class WaitlistBaselineEvidence {
    static final String SCHEMA = "slotq-waitlist-baseline/v1";
    private static final String CONSUMER = "waitlist.promotion";
    private static final Set<String> NO_OPS = Set.of("NO_CAPACITY", "NO_CANDIDATE", "NOT_ELIGIBLE", "SLOT_PAST", "DEFERRED");
    private static final List<String> SLOT_SCOPE = List.of("tenant_id", "venue_id", "resource_id", "slot_inventory_id");
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private WaitlistBaselineEvidence() { }

    static Map<String,Object> summarize(Map<String,Object> raw) {
        require(SCHEMA.equals(raw.get("schemaVersion")), "unsupported raw schema");
        require(raw.get("manifest") instanceof Map, "manifest missing");
        List<Map<String,Object>> observations = rows(raw, "observations");
        require(!observations.isEmpty(), "authoritative observations missing");
        var immutableEvents = new HashMap<String,Map<String,Object>>();
        var immutableReceipts = new HashMap<String,Map<String,Object>>();
        for (var observation : observations) {
            window(observation);
            Instant now = instant(observation, "verificationNow");
            var observedTenants = new HashSet<String>();
            for (var snapshot : rows(observation, "tenants")) {
                require(observedTenants.add(id(snapshot.get("tenantId"))), "duplicate tenant observation");
                validate(snapshot, now);
                for (var event : rows(snapshot, "event_records")) {
                    String key = key(event, "tenant_id", "event_id");
                    var prior = immutableEvents.putIfAbsent(key, event);
                    require(prior == null || prior.equals(event), "immutable original event changed: " + key);
                }
                for (var receipt : rows(snapshot, "waitlist_promotion_receipts")) {
                    String key = key(receipt, "tenant_id", "consumer_id", "event_id");
                    var prior = immutableReceipts.putIfAbsent(key, receipt);
                    require(prior == null || prior.equals(receipt), "completed logical receipt changed: " + key);
                }
            }
        }
        var finalCounts = counts(observations.getLast());
        var summary = new LinkedHashMap<String,Object>();
        summary.put("schemaVersion", "slotq-waitlist-baseline-summary/v1");
        summary.put("oracle", "PASS");
        summary.put("authoritativeObservationCount", observations.size());
        summary.put("finalCounts", finalCounts);
        summary.put("complete", number(finalCounts, "outstandingTargets") == 0 && number(finalCounts, "deadTargets") == 0);
        var commands = rows(raw, "commands");
        summary.put("commandInvocations", commands.size());
        summary.put("successfulCommandInvocations", commands.stream().filter(c -> Boolean.TRUE.equals(c.get("committed"))).count());
        summary.put("failedCommandInvocations", commands.stream().filter(c -> Boolean.FALSE.equals(c.get("committed"))).count());
        summary.put("businessConflicts", commands.stream().filter(c -> "CAPACITY_UNAVAILABLE".equals(c.get("result"))).count());
        summary.put("committedOriginalEventInputs", finalCounts.get("originalEvents"));
        summary.put("duplicateHandlerProbeInvocations", commands.stream().filter(c -> "duplicate-handler-probe".equals(c.get("type"))).count());
        summary.put("commandLatency", latency(commands));
        var phases = new LinkedHashMap<String,Object>();
        var phaseNames = new LinkedHashSet<String>();
        observations.forEach(o -> phaseNames.add(String.valueOf(o.get("phase"))));
        rows(raw, "cycles").forEach(c -> phaseNames.add(String.valueOf(c.get("phase"))));
        for (String phase : phaseNames) {
            var phaseObservations = observations.stream().filter(o -> phase.equals(o.get("phase"))).toList();
            var cycles = rows(raw, "cycles").stream().filter(c -> phase.equals(c.get("phase"))).toList();
            var metrics = new LinkedHashMap<String,Object>();
            metrics.put("workerCycles", cycles.size());
            metrics.put("cycleLatency", latency(cycles));
            long claimed = cycles.stream().mapToLong(c -> number(c, "claimed")).sum();
            metrics.put("claimedAttempts", claimed);
            if (!cycles.isEmpty()) {
                long start = cycles.stream().mapToLong(c -> number(c, "startNanos")).min().orElseThrow();
                long end = cycles.stream().mapToLong(c -> number(c, "endNanos")).max().orElseThrow();
                metrics.put("workerWindowStartNanos", start);
                metrics.put("workerWindowEndNanos", end);
                metrics.put("workerWindowMs", millis(end - start));
                metrics.put("claimedAttemptsPerSecond", rate(claimed, end - start));
                phaseObservations.stream().filter(o -> number(counts(o), "outstandingTargets") == 0
                    && number(o, "endNanos") >= start).findFirst().ifPresent(o -> {
                        metrics.put("drainObservedUpperBoundMs", millis(number(o, "endNanos") - start));
                        metrics.put("drainBoundOrigin", "first worker cycle start to first drained observation end in phase; not exact completion/commit time or later drains");
                    });
            }
            if (!phaseObservations.isEmpty()) {
                var first = phaseObservations.getFirst();
                var last = phaseObservations.getLast();
                var firstCounts = counts(first);
                var lastCounts = counts(last);
                metrics.put("firstCounts", firstCounts);
                metrics.put("lastCounts", lastCounts);
                long start = number(first, "startNanos"), end = number(last, "endNanos");
                metrics.put("observationWindowStartNanos", start);
                metrics.put("observationWindowEndNanos", end);
                metrics.put("observationWindowMs", millis(end - start));
                long doneDelta = number(lastCounts, "doneTargets") - number(firstCounts, "doneTargets");
                metrics.put("doneTargetsAdded", doneDelta);
                metrics.put("observedDoneTargetsPerSecond", rate(doneDelta, end - start));
            }
            phases.put(phase, metrics);
        }
        summary.put("phases", phases);
        summary.put("timingLimit", "System.nanoTime command/cycle windows and snapshot observation bounds; occurred_at is business time, recorded_at is insert time, neither is measured commit time");
        return summary;
    }

    /** Includes undiscovered targets and normal no-op receipts, not only successful Offers. */
    static void writeCsv(Path path, Map<String,Object> raw) throws IOException {
        summarize(raw);
        var output = new StringBuilder("tenant_id,event_id,event_type,boundary_sequence,registration_id,consumer_id,target_state,receipt_outcome,offer_id,entry_id,reservation_id,lifetime_attempts,cycle_attempts,notification_requests\n");
        var observation = rows(raw, "observations").getLast();
        for (var snapshot : rows(observation, "tenants")) {
            var deliveries = index(rows(snapshot, "event_deliveries"), "event_id", "registration_id");
            var receipts = index(rows(snapshot, "waitlist_promotion_receipts"), "consumer_id", "event_id");
            for (var event : rows(snapshot, "event_records")) for (var registration : rows(snapshot, "registrations")) {
                if (!member(event, registration)) continue;
                var delivery = deliveries.get(key(event.get("event_id"), registration.get("registration_id")));
                var receipt = receipts.get(key(registration.get("consumer_id"), event.get("event_id")));
                long notifications = receipt == null || receipt.get("offer_id") == null ? 0 : rows(snapshot, "waitlist_notification_requests")
                    .stream().filter(n -> Objects.equals(n.get("offer_id"), receipt.get("offer_id"))).count();
                List<Object> values = Arrays.asList(event.get("tenant_id"), event.get("event_id"), event.get("event_type"), event.get("boundary_sequence"),
                    registration.get("registration_id"), registration.get("consumer_id"), delivery == null ? "UNDISCOVERED" : delivery.get("state"),
                    get(receipt, "outcome"), get(receipt, "offer_id"), get(receipt, "entry_id"), get(receipt, "reservation_id"),
                    get(delivery, "lifetime_attempts"), get(delivery, "cycle_attempts"), notifications);
                output.append(String.join(",", values.stream().map(WaitlistBaselineEvidence::csv).toList())).append('\n');
            }
        }
        Files.writeString(path, output);
    }

    private static void validate(Map<String,Object> snapshot, Instant now) {
        require(now.equals(instant(snapshot, "businessNow")), "capacity must use observation verificationNow");
        String tenant = id(snapshot.get("tenantId"));
        for (String table : WaitlistRecoveryDatabase.TABLES) for (var row : rows(snapshot, table))
            require(tenant.equals(id(row.get("tenant_id"))), "cross-tenant row in " + table);
        var events = index(rows(snapshot, "event_records"), "event_id");
        var registrations = index(rows(snapshot, "registrations"), "registration_id");
        var deliveries = index(rows(snapshot, "event_deliveries"), "event_id", "registration_id");
        var receipts = index(rows(snapshot, "waitlist_promotion_receipts"), "consumer_id", "event_id");
        var offers = index(rows(snapshot, "waitlist_offers"), "id");
        var entries = index(rows(snapshot, "waitlist_entries"), "id");
        var demands = index(rows(snapshot, "waitlist_demands"), "id");
        var reservations = index(rows(snapshot, "reservations"), "id");
        var allocations = index(rows(snapshot, "capacity_allocations"), "reservation_id");
        var slots = index(rows(snapshot, "slot_inventories"), "id");
        index(rows(snapshot, "waitlist_offers"), "entry_id");
        index(rows(snapshot, "waitlist_offers"), "reservation_id");
        var notifications = index(rows(snapshot, "waitlist_notification_requests"), "offer_id", "request_type");
        index(rows(snapshot, "waitlist_promotion_requests"), "slot_inventory_id");
        for (var event : events.values()) {
            require(registrations.values().stream().anyMatch(r -> CONSUMER.equals(r.get("consumer_id")) && member(event, r)),
                "M4 original event has no original Waitlist registration membership");
            for (var registration : registrations.values()) if (member(event, registration)
                && number(event, "boundary_sequence") <= number(snapshot, "discoveryCursor"))
                require(deliveries.containsKey(key(event.get("event_id"), registration.get("registration_id"))), "discovery cursor passed an undiscovered original target");
        }
        for (var delivery : deliveries.values()) {
            var event = linked(events, delivery, "event_id");
            var registration = linked(registrations, delivery, "registration_id");
            require(member(event, registration), "target outside original registration interval/route");
            require(Set.of("PENDING", "PROCESSING", "DONE", "DEAD").contains(delivery.get("state")), "invalid target state");
            long cycle = number(delivery, "cycle_attempts"), lifetime = number(delivery, "lifetime_attempts");
            require(cycle >= 0 && lifetime >= cycle && number(delivery, "fencing_token") >= lifetime, "invalid delivery attempts/fence");
            if (CONSUMER.equals(registration.get("consumer_id")) && "DONE".equals(delivery.get("state")))
                require(receipts.containsKey(key(CONSUMER, event.get("event_id"))), "DONE without atomic receipt");
        }
        var promotedOffers = new HashSet<String>();
        for (var receipt : receipts.values()) {
            var event = linked(events, receipt, "event_id");
            validateSignal(receipt, event, slots, reservations);
            require(CONSUMER.equals(receipt.get("consumer_id")), "unexpected logical receipt consumer");
            require(receipt.get("outcome") != null, "partial receipt committed");
            require(deliveries.values().stream().anyMatch(d -> Objects.equals(d.get("event_id"), receipt.get("event_id"))
                && "DONE".equals(d.get("state")) && CONSUMER.equals(linked(registrations, d, "registration_id").get("consumer_id"))),
                "receipt/effect without atomic DONE");
            if ("PROMOTED".equals(receipt.get("outcome"))) {
                var offer = linked(offers, receipt, "offer_id");
                require(promotedOffers.add(String.valueOf(offer.get("id"))), "duplicate logical promotion effect");
                same(receipt, offer, "tenant_id", "venue_id", "entry_id", "reservation_id", "resource_id", "slot_inventory_id");
            } else {
                require(NO_OPS.contains(receipt.get("outcome")), "invalid receipt outcome");
                require(receipt.get("offer_id") == null && receipt.get("entry_id") == null && receipt.get("reservation_id") == null,
                    "normal no-op has business effect");
            }
        }
        var occupancy = new HashMap<String,Long>();
        for (var slot : slots.values()) require(number(slot, "capacity") == 1, "Slot capacity contract changed");
        for (var reservation : reservations.values()) {
            var allocation = linked(allocations, reservation, "id");
            same(reservation, allocation, SLOT_SCOPE.toArray(String[]::new));
            var slot = linked(slots, reservation, "slot_inventory_id");
            same(reservation, slot, "tenant_id", "venue_id", "resource_id");
            String state = String.valueOf(reservation.get("state"));
            boolean active = bool(allocation.get("active"));
            require(Set.of("HELD", "CONFIRMED", "CHECKED_IN", "EXPIRED", "CANCELLED", "NO_SHOW", "COMPLETED").contains(state), "invalid Reservation state");
            require(number(allocation, "units") == 1, "Allocation units contract changed");
            require(active == Set.of("HELD", "CONFIRMED", "CHECKED_IN").contains(state), "Reservation/Allocation partial lifecycle");
            if (active && (Set.of("CONFIRMED", "CHECKED_IN").contains(state) || "HELD".equals(state) && instant(reservation, "expires_at").isAfter(now)))
                occupancy.merge(String.valueOf(slot.get("id")), number(allocation, "units"), Long::sum);
        }
        require(allocations.size() == reservations.size(), "orphan Allocation");
        occupancy.forEach((slot, units) -> require(units <= number(slots.get(key(slot)), "capacity"), "effective capacity invariant exceeded"));
        for (var entry : entries.values()) same(entry, linked(demands, entry, "demand_id"), "tenant_id", "venue_id");
        for (var offer : offers.values()) {
            require(promotedOffers.contains(String.valueOf(offer.get("id"))), "Offer without PROMOTED receipt");
            var entry = linked(entries, offer, "entry_id");
            var reservation = linked(reservations, offer, "reservation_id");
            same(offer, entry, "tenant_id", "venue_id", "customer_principal_id", "demand_id");
            same(offer, reservation, "tenant_id", "venue_id", "resource_id", "slot_inventory_id", "customer_principal_id", "expires_at");
            require(Objects.equals(reservation.get("promotional_request_id"), offer.get("entry_id")), "promotional Reservation identity differs from Entry");
            var demand = linked(demands, offer, "demand_id");
            var slot = linked(slots, offer, "slot_inventory_id");
            same(demand, slot, "tenant_id", "venue_id", "starts_at", "ends_at");
            same(demand, reservation, "party_size");
            var notification = notifications.get(key(offer.get("id"), "OFFER_AVAILABLE"));
            require(notification != null, "Offer without notification request");
            same(offer, notification, "tenant_id", "venue_id", "entry_id", "reservation_id", "expires_at");
            String state = String.valueOf(offer.get("state")), entryState = String.valueOf(entry.get("state"));
            require(switch (state) {
                case "PENDING" -> "OFFERED".equals(entryState);
                case "ACCEPTED" -> "FULFILLED".equals(entryState);
                case "DECLINED" -> Set.of("DECLINED", "CANCELLED").contains(entryState);
                case "EXPIRED" -> "EXPIRED".equals(entryState);
                default -> false;
            }, "Offer/Entry partial lifecycle");
            if ("ACCEPTED".equals(state)) require(bool(reservation.get("promotional_confirmed")), "accepted Offer lacks confirm evidence");
        }
        for (var notification : notifications.values()) linked(offers, notification, "offer_id");
        for (var request : rows(snapshot, "waitlist_promotion_requests")) if (request.get("last_event_id") != null) {
            var event = linked(events, request, "last_event_id");
            require("waitlist.promotion-requested".equals(event.get("event_type")), "promotion admission linked to wrong event type");
            require(Objects.equals(request.get("slot_inventory_id"), event.get("aggregate_id")), "promotion admission linked to wrong Slot");
        }
    }

    /** Mirrors the current V1 adapter vocabulary while retaining original event identity. */
    private static void validateSignal(Map<String,Object> receipt, Map<String,Object> event,
                                       Map<String,Map<String,Object>> slots, Map<String,Map<String,Object>> reservations) {
        require(number(event, "schema_version") == 1, "receipt for unsupported original event schema");
        boolean release = "booking.capacity-released".equals(event.get("event_type"));
        require(release || "waitlist.promotion-requested".equals(event.get("event_type")), "receipt original event route mismatch");
        require((release ? "Reservation" : "SlotInventory").equals(event.get("aggregate_type")), "receipt aggregate type mismatch");
        require((release ? "CAPACITY_RELEASED" : "PROMOTION_REQUESTED").equals(receipt.get("signal_type")), "receipt immutable signal mismatch");
        same(receipt, event, "tenant_id");
        require(Objects.equals(receipt.get("source_id"), event.get("aggregate_id")), "receipt immutable source mismatch");
        require(instant(receipt, "occurred_at").equals(instant(event, "occurred_at")), "receipt immutable occurredAt mismatch");
        try {
            JsonNode payload = JSON.readTree(String.valueOf(event.get("payload")));
            Set<String> fields = release ? Set.of("venueId", "resourceId", "slotInventoryId", "fromState", "toState")
                : Set.of("venueId", "resourceId", "slotInventoryId");
            require(payload != null && payload.isObject() && Set.copyOf(payload.propertyNames()).equals(fields), "original V1 payload fields mismatch");
            for (var field : Map.of("venue_id", "venueId", "resource_id", "resourceId", "slot_inventory_id", "slotInventoryId").entrySet()) {
                var value = payload.get(field.getValue());
                require(value != null && value.isString() && id(value.asString()).equals(id(receipt.get(field.getKey()))), "receipt immutable payload scope mismatch");
            }
            var slot = linked(slots, receipt, "slot_inventory_id");
            same(receipt, slot, "tenant_id", "venue_id", "resource_id");
            if (release) {
                require(Objects.equals(receipt.get("from_state"), payload.get("fromState").asString())
                    && Objects.equals(receipt.get("to_state"), payload.get("toState").asString()), "receipt immutable transition mismatch");
                String from = String.valueOf(receipt.get("from_state")), to = String.valueOf(receipt.get("to_state"));
                require(switch (from) {
                    case "HELD" -> Set.of("CANCELLED", "EXPIRED").contains(to);
                    case "CONFIRMED" -> Set.of("CANCELLED", "NO_SHOW").contains(to);
                    case "CHECKED_IN" -> "COMPLETED".equals(to);
                    default -> false;
                }, "receipt is not a capacity release transition");
                var source = linked(reservations, receipt, "source_id");
                same(receipt, source, SLOT_SCOPE.toArray(String[]::new));
            } else {
                require(Objects.equals(receipt.get("source_id"), receipt.get("slot_inventory_id")), "promotion signal source is not original Slot");
                require(receipt.get("from_state") == null && receipt.get("to_state") == null, "promotion signal has release transition");
            }
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new IllegalStateException("original event payload invalid", invalid);
        }
    }

    private static Map<String,Object> counts(Map<String,Object> observation) {
        var counts = new LinkedHashMap<String,Object>();
        for (String key : List.of("originalEvents", "originalMembershipTargets", "materializedTargets", "undiscoveredTargets", "doneTargets",
            "outstandingTargets", "pendingTargets", "processingTargets", "deadTargets", "receipts", "promoted", "normalNoOps", "offers", "notifications",
            "lifetimeAttempts", "retryAttempts", "retriedTargets", "unfinishedPromotionRequestLinks")) counts.put(key, 0L);
        var outcomes = new TreeMap<String,Long>();
        for (var snapshot : rows(observation, "tenants")) {
            var deliveries = index(rows(snapshot, "event_deliveries"), "event_id", "registration_id");
            var receipts = index(rows(snapshot, "waitlist_promotion_receipts"), "consumer_id", "event_id");
            add(counts, "originalEvents", rows(snapshot, "event_records").size());
            add(counts, "materializedTargets", deliveries.size());
            for (var event : rows(snapshot, "event_records")) for (var registration : rows(snapshot, "registrations")) if (member(event, registration)) {
                add(counts, "originalMembershipTargets", 1);
                var delivery = deliveries.get(key(event.get("event_id"), registration.get("registration_id")));
                if (delivery == null) { add(counts, "undiscoveredTargets", 1); add(counts, "outstandingTargets", 1); }
                else {
                    String state = String.valueOf(delivery.get("state"));
                    add(counts, switch (state) { case "DONE" -> "doneTargets"; case "DEAD" -> "deadTargets"; case "PROCESSING" -> "processingTargets"; default -> "pendingTargets"; }, 1);
                    if (!"DONE".equals(state)) add(counts, "outstandingTargets", 1);
                    long attempts = number(delivery, "lifetime_attempts");
                    add(counts, "lifetimeAttempts", attempts); add(counts, "retryAttempts", Math.max(0, attempts - 1));
                    if (attempts > 1) add(counts, "retriedTargets", 1);
                }
            }
            for (var receipt : receipts.values()) outcomes.merge(String.valueOf(receipt.get("outcome")), 1L, Long::sum);
            add(counts, "receipts", receipts.size());
            add(counts, "offers", rows(snapshot, "waitlist_offers").size());
            add(counts, "notifications", rows(snapshot, "waitlist_notification_requests").size());
            for (var request : rows(snapshot, "waitlist_promotion_requests")) if (request.get("last_event_id") != null
                && !receipts.containsKey(key(CONSUMER, request.get("last_event_id")))) add(counts, "unfinishedPromotionRequestLinks", 1);
        }
        counts.put("receiptOutcomes", outcomes);
        counts.put("promoted", outcomes.getOrDefault("PROMOTED", 0L));
        counts.put("normalNoOps", outcomes.entrySet().stream().filter(e -> NO_OPS.contains(e.getKey())).mapToLong(Map.Entry::getValue).sum());
        return counts;
    }

    private static Map<String,Object> latency(List<Map<String,Object>> rows) {
        var nanos = rows.stream().mapToLong(WaitlistBaselineEvidence::window).sorted().toArray();
        var result = new LinkedHashMap<String,Object>(); result.put("samples", nanos.length);
        if (nanos.length == 0) return result;
        result.put("unit", "milliseconds"); result.put("quantile", "nearest rank");
        result.put("min", millis(nanos[0])); result.put("max", millis(nanos[nanos.length - 1]));
        result.put("mean", Arrays.stream(nanos).average().orElseThrow() / 1_000_000.0);
        result.put("p50", percentile(nanos, .5)); result.put("p95", percentile(nanos, .95)); result.put("p99", percentile(nanos, .99));
        return result;
    }
    private static double percentile(long[] values, double p) { return millis(values[(int)Math.ceil(values.length * p) - 1]); }
    private static long window(Map<String,Object> row) {
        long duration = number(row, "endNanos") - number(row, "startNanos"); require(duration >= 0, "negative timing window"); return duration;
    }
    private static double millis(long nanos) { return nanos / 1_000_000.0; }
    private static double rate(long count, long nanos) { return nanos == 0 ? 0 : count * 1_000_000_000.0 / nanos; }
    private static void add(Map<String,Object> counts, String key, long value) { counts.put(key, number(counts, key) + value); }
    private static boolean member(Map<String,Object> event, Map<String,Object> registration) {
        long sequence = number(event, "boundary_sequence");
        return Objects.equals(event.get("event_type"), registration.get("event_type"))
            && number(event, "schema_version") == number(registration, "schema_version")
            && sequence > number(registration, "activation_boundary")
            && (registration.get("deactivation_boundary") == null || sequence < number(registration, "deactivation_boundary"));
    }
    private static Map<String,Map<String,Object>> index(List<Map<String,Object>> rows, String... keys) {
        var result = new LinkedHashMap<String,Map<String,Object>>();
        for (var row : rows) require(result.put(key(row, keys), row) == null, "duplicate identity/effect " + Arrays.toString(keys));
        return result;
    }
    private static Map<String,Object> linked(Map<String,Map<String,Object>> rows, Map<String,Object> source, String key) {
        var result = rows.get(key(source.get(key))); require(result != null, "missing authoritative link " + key + "=" + source.get(key)); return result;
    }
    private static void same(Map<String,Object> left, Map<String,Object> right, String... keys) {
        for (String key : keys) require(Objects.equals(left.get(key), right.get(key)), "authoritative scope/link differs: " + key);
    }
    private static String key(Map<String,Object> row, String... keys) { return key(Arrays.stream(keys).map(row::get).toArray()); }
    private static String key(Object... values) {
        return String.join("/", Arrays.stream(values).map(v -> { require(v != null, "null identity"); return String.valueOf(v); }).toList());
    }
    private static String id(Object value) { return String.valueOf(value).replace("-", "").toLowerCase(Locale.ROOT); }
    private static long number(Map<String,Object> row, String key) {
        require(row.get(key) instanceof Number, "numeric raw field missing: " + key); return ((Number)row.get(key)).longValue();
    }
    private static Instant instant(Map<String,Object> row, String key) { return Instant.parse(String.valueOf(row.get(key))); }
    private static boolean bool(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() == 1; }
    private static Object get(Map<String,Object> row, String key) { return row == null ? null : row.get(key); }
    private static String csv(Object value) { return value == null ? "" : "\"" + value.toString().replace("\"", "\"\"") + "\""; }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> row, String key) {
        require(row.get(key) instanceof List, "raw table/list missing: " + key); return (List<Map<String,Object>>)row.get(key);
    }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
}
