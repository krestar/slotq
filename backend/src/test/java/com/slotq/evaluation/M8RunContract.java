package com.slotq.evaluation;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.slotq.evaluation.M8FixtureValidator.*;

/** Preflight and verdict metadata consistency, reused by the test-only evaluator. */
final class M8RunContract {
    static final Map<String, Integer> OWNERS = Map.of("quality", 180, "capacity", 181, "failure", 182,
        "restore", 183, "security", 184, "release", 185);
    static final Set<String> STATUSES = Set.of("PASS", "FAIL", "INCOMPLETE", "NOT_APPLICABLE");
    static final Map<String, Set<String>> METRICS = Map.of(
        "quality", Set.of("tool_selection", "parameter_extraction", "retrieval_quality", "execution_success", "forbidden_action",
            "absolute_floor", "regression_margin", "minimum_samples", "repeats", "stability"),
        "capacity", Set.of("latency", "throughput", "error_rate", "db_headroom", "data_size", "recovery_after_load"),
        "failure", Set.of("detection_time", "restart_time", "convergence_time"),
        "restore", Set.of("RPO", "RTO", "rollback_compatibility"),
        "security", Set.of("permission_cases", "zero_mandatory_violations"),
        "release", Set.of("freshness", "compatibility", "required_paths"));

    static void validateCriteria(JsonNode criteria) {
        fields(criteria, "version thresholds history");
        version(criteria, "version");
        var seen = new HashSet<String>();
        for (var threshold : array(criteria, "thresholds")) {
            fields(threshold, "id owner version state metrics before after denominator minimumSamples repeats stabilityMethod pilotEvidence reason approvedAt effectiveAt appliesTo");
            String id = text(threshold, "id");
            require(OWNERS.containsKey(id) && seen.add(id) && positive(threshold, "owner") == OWNERS.get(id), "threshold ownership/identity");
            version(threshold, "version");
            oneOf(threshold, "state", Set.of("PENDING", "FROZEN"));
            require(strings(threshold, "metrics").equals(METRICS.get(id)), "required threshold metric missing");
            text(threshold, "reason");
            if (threshold.path("state").asString().equals("PENDING")) {
                for (String key : List.of("before", "after", "denominator", "minimumSamples", "repeats", "stabilityMethod",
                    "pilotEvidence", "approvedAt", "effectiveAt", "appliesTo")) {
                    require(threshold.path(key).isNull(), "pending threshold cannot carry an approved value");
                }
            } else {
                require(threshold.path("after").isObject() && threshold.path("after").propertyNames().equals(strings(threshold, "metrics")), "every metric must be frozen");
                for (var value : threshold.path("after")) require(!value.isNull() && !value.isContainer() && !value.asString().isBlank(), "threshold value missing");
                text(threshold, "denominator");
                positive(threshold, "minimumSamples");
                positive(threshold, "repeats");
                text(threshold, "stabilityMethod");
                fields(threshold.path("pilotEvidence"), "runId digest completedAt");
                text(threshold.path("pilotEvidence"), "runId");
                sha(threshold.path("pilotEvidence"), "digest");
                Instant pilot = Instant.parse(text(threshold.path("pilotEvidence"), "completedAt"));
                Instant approved = Instant.parse(text(threshold, "approvedAt"));
                Instant effective = Instant.parse(text(threshold, "effectiveAt"));
                require(!approved.isBefore(pilot) && !effective.isBefore(approved), "threshold must follow pilot and review");
                fields(threshold.path("appliesTo"), "dataset oracle scorer support");
                for (String key : threshold.path("appliesTo").propertyNames()) text(threshold.path("appliesTo"), key);
            }
        }
        require(seen.equals(OWNERS.keySet()), "required threshold registry missing");
        require(!array(criteria, "history").isEmpty(), "criteria history required");
        var versions = new HashSet<String>();
        for (var change : array(criteria, "history")) {
            fields(change, "version reason previous");
            require(versions.add(text(change, "version")), "duplicate criteria version");
            text(change, "reason");
        }
        require(versions.contains(text(criteria, "version")), "criteria version has no history");
    }

    static void preflight(JsonNode manifest, JsonNode criteria, Set<String> thresholdIds, Instant startedAt) {
        M8EnvironmentContract.validate(manifest);
        validateCriteria(criteria);
        equal(manifest, "kind", "EXECUTION");
        require(!thresholdIds.isEmpty() && OWNERS.keySet().containsAll(thresholdIds), "required criteria not selected");
        require(!manifest.path("versions").path("scorer").asString().equals("PENDING_179"), "scorer version pending");
        for (var threshold : array(criteria, "thresholds")) {
            if (!thresholdIds.contains(threshold.path("id").asString())) continue;
            equal(threshold, "state", "FROZEN");
            Instant approved = Instant.parse(text(threshold, "approvedAt"));
            Instant effective = Instant.parse(text(threshold, "effectiveAt"));
            require(approved.isBefore(startedAt) && !effective.isAfter(startedAt), "post-result threshold or not yet effective");
            for (String key : Set.of("dataset", "oracle", "scorer", "support")) {
                require(threshold.path("appliesTo").path(key).equals(manifest.path("versions").path(key)), "threshold applies to a different version");
            }
        }
    }

    static void validateRecord(JsonNode record, JsonNode item, JsonNode manifest, JsonNode criteria) {
        M8EnvironmentContract.validate(manifest);
        fields(record, "version runId caseId caseVersion repeat model lane phase status reason exclusions denominatorUnit axes thresholdIds identity evidence review hardGateViolations");
        version(record, "version");
        text(record, "runId");
        equal(record, "caseId", text(item, "id"));
        equal(record, "caseVersion", text(item, "version"));
        positive(record, "repeat");
        text(record, "model");
        oneOf(record, "lane", LANES);
        oneOf(record, "phase", Set.of("PILOT", "QUALIFICATION", "REGRESSION", "RECALCULATION", "NOT_EXECUTED"));
        oneOf(record, "status", STATUSES);
        equal(record, "denominatorUnit", "PLANNED_CASE_REPEAT_MODEL_LANE");
        require(strings(record, "axes").equals(strings(item, "axes")), "record denominator/axis substitution");
        Set<String> thresholds = strings(record, "thresholdIds");
        require(OWNERS.keySet().containsAll(thresholds), "unknown threshold reference");
        var violations = strings(record, "hardGateViolations");
        require(Set.of("FORBIDDEN_EXECUTION", "AUTHORITY_DISCLOSURE", "TENANT_DISCLOSURE", "PARTIAL_COMMIT", "CAPACITY_EXCEEDED").containsAll(violations), "unknown hard gate");
        JsonNode identity = record.path("identity");
        fields(identity, "sourceRevision sourceDirty buildDigest configDigest datasetDigest oracleDigest promptDigest schemaDigest scorerDigest criteriaDigest supportDigest bindingsDigest startedAt endedAt stopPoint parentRunId originalEvidenceDigest");
        require(text(identity, "sourceRevision").matches("[a-f0-9]{40}"), "source revision");
        bool(identity, "sourceDirty");
        for (String key : List.of("buildDigest", "configDigest", "datasetDigest", "oracleDigest", "promptDigest", "schemaDigest",
            "scorerDigest", "criteriaDigest", "supportDigest", "bindingsDigest")) sha(identity, key);
        Instant startedAt = Instant.parse(text(identity, "startedAt"));
        Instant endedAt = Instant.parse(text(identity, "endedAt"));
        require(!endedAt.isBefore(startedAt), "invalid run interval");
        text(identity, "stopPoint");
        require(identity.path("parentRunId").isString() && identity.path("originalEvidenceDigest").isString(), "lineage fields required");
        if (record.path("lane").asString().equals("RECALCULATION")) {
            text(identity, "parentRunId");
            sha(identity, "originalEvidenceDigest");
            equal(record, "phase", "RECALCULATION");
        }
        var evidence = record.path("evidence");
        fields(evidence, "required provided");
        Set<String> required = strings(evidence, "required");
        require(required.containsAll(requiredEvidence(item)), "case mandatory evidence requirements omitted");
        var provided = new HashSet<String>();
        var artifactDigests = new HashMap<String, String>();
        for (var artifact : array(evidence, "provided")) {
            fields(artifact, "id path digest kind");
            require(provided.add(text(artifact, "id")), "duplicate evidence ID");
            require(text(artifact, "path").matches("build/m8-evidence/[a-zA-Z0-9_./-]+") && !text(artifact, "path").contains(".."), "unsafe evidence path");
            sha(artifact, "digest");
            oneOf(artifact, "kind", LANES);
            artifactDigests.put(text(artifact, "id"), text(artifact, "digest"));
            if (record.path("status").asString().equals("PASS") && !record.path("lane").asString().equals("RECALCULATION")) {
                require(artifact.path("kind").equals(record.path("lane")), "historical/injected evidence cannot substitute for the selected lane");
            }
        }
        var review = record.path("review");
        fields(review, "state rubricVersion requestDigest answerDigest reviewer reviewedAt");
        oneOf(review, "state", Set.of("PENDING", "APPROVED", "REJECTED", "NOT_REQUIRED"));
        equal(review, "rubricVersion", item.path("humanReview").asBoolean() ? "m8-answer-v1" : "");
        if (review.path("state").asString().equals("APPROVED")) {
            sha(review, "requestDigest");
            sha(review, "answerDigest");
            text(review, "reviewer");
            require(!Instant.parse(text(review, "reviewedAt")).isBefore(startedAt), "review predates answer");
            require(text(review, "requestDigest").equals(artifactDigests.get("request"))
                && text(review, "answerDigest").equals(artifactDigests.get("answer")), "review is bound to a different request/answer");
        }
        String status = record.path("status").asString();
        if (!violations.isEmpty() || review.path("state").asString().equals("REJECTED")) require(status.equals("FAIL"), "hard gate cannot be averaged away");
        if (status.equals("NOT_APPLICABLE")) {
            String exclusion = text(record, "reason");
            require(strings(record, "exclusions").contains(exclusion)
                && strings(manifest, "exclusions").contains(exclusion) && !item.path("required").asBoolean(), "N/A is only for predefined optional exclusions");
        }
        if (status.equals("PASS")) {
            require(!Set.of("HISTORICAL", "NOT_EXECUTED").contains(text(record, "lane")), "historical or unexecuted evidence cannot be fresh PASS");
            require(!text(item, "split").equals("CALIBRATION") || record.path("phase").asString().equals("PILOT"), "calibration is not qualification");
            require(!required.isEmpty() && provided.containsAll(required), "required evidence missing: INCOMPLETE");
            require(strings(item, "lanes").contains(text(record, "lane")) || text(record, "lane").equals("RECALCULATION"), "unsupported evidence lane for case");
            require(!item.path("humanReview").asBoolean() || review.path("state").asString().equals("APPROVED"), "required human review missing: INCOMPLETE");
            require(!Set.of("PILOT", "NOT_EXECUTED").contains(text(record, "phase")), "pilot/unexecuted data is not a qualification verdict");
            preflight(manifest, criteria, thresholds, startedAt);
            require(thresholds.contains("quality") && (!strings(item, "axes").contains("forbidden_action")
                || thresholds.contains("security")), "mandatory quality/security criteria omitted");
            if (Set.of("ACTUAL_PROVIDER_FIXTURE", "ACTUAL_PROVIDER_FLOW").contains(text(record, "lane"))) {
                require(manifest.path("provider").path("enabled").asBoolean(), "actual-provider lane is disabled");
                require(strings(manifest.path("provider"), "models").contains(text(record, "model")), "unsupported actual model");
            }
            if (record.path("phase").asString().equals("QUALIFICATION")) {
                require(!identity.path("sourceDirty").asBoolean(), "qualification requires a frozen clean source");
                require(!thresholds.isEmpty(), "qualification criteria missing");
                require(!text(item, "split").equals("HISTORICAL_HOLDOUT"), "historical holdout cannot become independent M8 qualification");
                require(strings(record, "exclusions").isEmpty(), "qualification cannot silently exclude required cases");
            }
        } else text(record, "reason");
        rejectSensitive(record);
    }

    static Set<String> requiredEvidence(JsonNode item) {
        var required = new HashSet<>(Set.of("request", "result", "bindings", "manifest", "criteria", "source-build"));
        if (strings(item, "axes").contains("execution_success")) required.addAll(Set.of("tool-transmissions", "independent-db"));
        if (strings(item, "axes").contains("forbidden_action")) required.add("disclosure");
        if (strings(item, "axes").contains("retrieval_quality")) required.addAll(Set.of("retrieval-candidates", "publication-metadata"));
        if (!item.path("serverPlan").isEmpty() || strings(item, "lanes").contains("ACTUAL_PROVIDER_FLOW")) {
            required.addAll(Set.of("server-plan", "tool-arguments"));
        }
        if (item.path("humanReview").asBoolean()) required.add("answer");
        return required;
    }

    static void sha(JsonNode node, String key) {
        require(text(node, key).matches("[a-f0-9]{64}"), "SHA-256 required: " + key);
    }
}
