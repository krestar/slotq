package com.slotq.experiments.events;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;

final class EventEvidence {
    static Map<String, Object> summarize(JsonNode rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var boundary : EventFixture.Boundary.values()) {
            int total = 0, lost = 0, committed = 0, effects = 0, crashLoss = 0;
            double slowProducerMs = 0;
            for (JsonNode row : rows) {
                if (!row.path("boundary").asText().equals(boundary.name())) continue;
                total++;
                JsonNode after = row.path("afterRecovery");
                committed += after.path("business").asInt();
                effects += after.path("effects").asInt();
                boolean missing = after.path("business").asInt() == 1
                    && after.path("effects").asInt() == 0 && after.path("durable").asInt() == 0;
                if (missing) lost++;
                if (missing && row.path("fault").asText().equals("AFTER_COMMIT")) crashLoss++;
                if (row.path("fault").asText().equals("SLOW_HANDLER")) {
                    slowProducerMs += row.path("producerElapsedMs").asDouble();
                }
            }
            result.put(boundary.name(), Map.of("cases", total, "committed", committed,
                "effectsAfterRecovery", effects, "lost", lost, "postCommitCrashLoss", crashLoss,
                "slowProducerMeanMs", slowProducerMs / 3));
        }
        return result;
    }
}
