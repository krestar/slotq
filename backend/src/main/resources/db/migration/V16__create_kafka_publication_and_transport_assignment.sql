-- A registration's target membership is immutable; this separate row grants execution authority.
-- #108 owns any fenced change of transport/epoch. #107 creates only DB_DIRECT assignments.
CREATE TABLE event_transport_assignments (
    registration_id BINARY(16) NOT NULL PRIMARY KEY,
    transport VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    authority_epoch BIGINT NOT NULL,
    assigned_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    CONSTRAINT fk_event_transport_registration FOREIGN KEY (registration_id)
        REFERENCES event_registrations (registration_id) ON DELETE CASCADE,
    CONSTRAINT ck_event_transport CHECK (transport IN ('DB_DIRECT', 'KAFKA')),
    CONSTRAINT ck_event_transport_epoch CHECK (authority_epoch > 0)
);
INSERT INTO event_transport_assignments (registration_id, transport, authority_epoch)
SELECT registration_id, 'DB_DIRECT', 1 FROM event_registrations;

-- This cursor has no relationship to event_discovery, which owns DB target materialization.
CREATE TABLE event_kafka_discovery (
    singleton_id TINYINT NOT NULL PRIMARY KEY,
    boundary_sequence BIGINT NOT NULL,
    destination VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT ck_event_kafka_discovery_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_event_kafka_discovery_sequence CHECK (boundary_sequence >= 0)
);
INSERT INTO event_kafka_discovery (singleton_id, boundary_sequence) VALUES (1, 0);

CREATE TABLE event_kafka_topic_state (
    destination VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    topic_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observed_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6))
);

CREATE TABLE event_kafka_publications (
    tenant_id BINARY(16) NOT NULL,
    event_id BINARY(16) NOT NULL,
    destination VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    discovered_boundary BIGINT NOT NULL,
    cycle_attempts INT NOT NULL DEFAULT 0,
    lifetime_attempts BIGINT NOT NULL DEFAULT 0,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ack_partition INT NULL,
    ack_offset BIGINT NULL,
    ack_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (tenant_id, event_id, destination),
    KEY idx_kafka_publication_due (state, next_attempt_at),
    KEY idx_kafka_publication_reclaim (state, lease_until),
    CONSTRAINT fk_kafka_publication_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES event_records (tenant_id, event_id),
    CONSTRAINT ck_kafka_publication_state CHECK (state IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'DEAD')),
    CONSTRAINT ck_kafka_publication_attempts CHECK (
        cycle_attempts >= 0 AND lifetime_attempts >= cycle_attempts AND fencing_token >= lifetime_attempts
    ),
    CONSTRAINT ck_kafka_publication_lease CHECK (
        (state = 'PROCESSING' AND lease_until IS NOT NULL AND next_attempt_at IS NULL) OR
        (state = 'PENDING' AND lease_until IS NULL AND next_attempt_at IS NOT NULL) OR
        (state IN ('PUBLISHED', 'DEAD') AND lease_until IS NULL AND next_attempt_at IS NULL)
    ),
    CONSTRAINT ck_kafka_publication_ack CHECK (
        (state = 'PUBLISHED' AND ack_partition IS NOT NULL AND ack_offset IS NOT NULL AND ack_at IS NOT NULL) OR
        (state <> 'PUBLISHED' AND ack_partition IS NULL AND ack_offset IS NULL AND ack_at IS NULL)
    )
);
