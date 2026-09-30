package com.slotq.integration.operations.recovery;

import java.util.UUID;

/** Authenticated only through the individual operations credential store. */
public record HumanOperator(UUID operatorId, UUID credentialId) { }
