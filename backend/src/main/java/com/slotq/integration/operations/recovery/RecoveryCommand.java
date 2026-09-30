package com.slotq.integration.operations.recovery;

import java.util.List;
import java.util.UUID;

/** Exact semantic request, excluding the server-generated correlation of a retry. */
public record RecoveryCommand(UUID operationId, UUID tenantId, UUID eventId, UUID registrationId,
    String consumerId, String action, String destination, List<String> affectedConsumers, String reason,
    String expectedState, long expectedFence, String expectedTransport, long expectedAuthorityEpoch,
    String publicationCause) {

    public RecoveryCommand {
        if (affectedConsumers != null) {
            if (affectedConsumers.stream().anyMatch(java.util.Objects::isNull)) throw RecoveryProblem.invalid();
            affectedConsumers = List.copyOf(affectedConsumers);
        }
    }

    void validate() {
        if (operationId == null || tenantId == null || eventId == null || !identifier(consumerId)
            || reason == null || reason.isBlank() || reason.length() > 500
            || reason.chars().anyMatch(Character::isISOControl) || expectedFence < 0) throw RecoveryProblem.invalid();
        if ("BUSINESS_REPLAY".equals(action)) {
            if (registrationId == null || destination != null || affectedConsumers != null || publicationCause != null
                || !"DEAD".equals(expectedState) || expectedAuthorityEpoch < 1
                || !("DB_DIRECT".equals(expectedTransport) || "KAFKA".equals(expectedTransport)))
                throw RecoveryProblem.invalid();
        } else if ("PUBLICATION_RECOVER".equals(action)) {
            if (registrationId != null || !identifier(destination) || expectedTransport != null || expectedAuthorityEpoch != 0
                || affectedConsumers == null || affectedConsumers.isEmpty() || affectedConsumers.size() > 10
                || affectedConsumers.stream().anyMatch(value -> !identifier(value))
                || !affectedConsumers.equals(affectedConsumers.stream().distinct().sorted().toList())
                || !consumerId.equals(affectedConsumers.getFirst())
                || !("DEAD".equals(expectedState) && "PUBLICATION_DEAD".equals(publicationCause)
                    || "PUBLISHED".equals(expectedState) && "RETENTION_GAP".equals(publicationCause)))
                throw RecoveryProblem.invalid();
        } else throw RecoveryProblem.invalid();
    }

    static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9._-]{1,100}"); }
}
