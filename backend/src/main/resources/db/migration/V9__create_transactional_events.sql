CREATE TABLE event_boundary (
    singleton_id TINYINT NOT NULL PRIMARY KEY,
    sequence_value BIGINT NOT NULL,
    CONSTRAINT ck_event_boundary_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_event_boundary_sequence CHECK (sequence_value >= 0)
);
INSERT INTO event_boundary VALUES (1, 0);

CREATE TABLE event_records (
    event_id BINARY(16) NOT NULL PRIMARY KEY,
    tenant_id BINARY(16) NOT NULL,
    aggregate_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    event_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    -- Canonical JSON text preserves exact decimals; native JSON normalizes numbers.
    payload MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    boundary_sequence BIGINT NOT NULL,
    recorded_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    UNIQUE KEY uk_event_tenant_identity (tenant_id, event_id),
    UNIQUE KEY uk_event_boundary (boundary_sequence),
    CONSTRAINT fk_event_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT ck_event_aggregate_type CHECK (CHAR_LENGTH(aggregate_type) > 0 AND NOT REGEXP_LIKE(aggregate_type, '[^A-Za-z0-9._-]', 'c')),
    CONSTRAINT ck_event_type CHECK (CHAR_LENGTH(event_type) > 0 AND NOT REGEXP_LIKE(event_type, '[^A-Za-z0-9._-]', 'c')),
    CONSTRAINT ck_event_version CHECK (schema_version > 0),
    CONSTRAINT ck_event_payload CHECK (JSON_VALID(payload)),
    CONSTRAINT ck_event_sequence CHECK (boundary_sequence > 0)
);

CREATE TABLE event_registrations (
    registration_id BINARY(16) NOT NULL PRIMARY KEY,
    consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT NOT NULL,
    activation_boundary BIGINT NOT NULL,
    deactivation_boundary BIGINT NULL,
    active_route TINYINT GENERATED ALWAYS AS (IF(deactivation_boundary IS NULL, 1, NULL)) STORED,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    UNIQUE KEY uk_event_active_route (consumer_id, event_type, schema_version, active_route),
    KEY idx_event_registration_route (event_type, schema_version, activation_boundary),
    CONSTRAINT ck_event_consumer CHECK (CHAR_LENGTH(consumer_id) > 0 AND NOT REGEXP_LIKE(consumer_id, '[^A-Za-z0-9._-]', 'c')),
    CONSTRAINT ck_event_registration_type CHECK (CHAR_LENGTH(event_type) > 0 AND NOT REGEXP_LIKE(event_type, '[^A-Za-z0-9._-]', 'c')),
    CONSTRAINT ck_event_registration_version CHECK (schema_version > 0),
    CONSTRAINT ck_event_registration_interval CHECK (
        activation_boundary > 0 AND
        (deactivation_boundary IS NULL OR deactivation_boundary > activation_boundary)
    )
);

CREATE TABLE event_discovery (
    singleton_id TINYINT NOT NULL PRIMARY KEY,
    boundary_sequence BIGINT NOT NULL,
    CONSTRAINT ck_event_discovery_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_event_discovery_sequence CHECK (boundary_sequence >= 0)
);
INSERT INTO event_discovery VALUES (1, 0);

CREATE TABLE event_deliveries (
    tenant_id BINARY(16) NOT NULL,
    event_id BINARY(16) NOT NULL,
    registration_id BINARY(16) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cycle_attempts INT NOT NULL DEFAULT 0,
    lifetime_attempts BIGINT NOT NULL DEFAULT 0,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    next_attempt_at DATETIME(6) NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    failure_detail VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (tenant_id, event_id, registration_id),
    UNIQUE KEY uk_event_delivery_target (registration_id, event_id),
    KEY idx_event_delivery_due (state, next_attempt_at),
    KEY idx_event_delivery_reclaim (state, lease_until),
    CONSTRAINT fk_event_delivery_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES event_records (tenant_id, event_id),
    CONSTRAINT fk_event_delivery_registration FOREIGN KEY (registration_id)
        REFERENCES event_registrations (registration_id),
    CONSTRAINT ck_event_delivery_state CHECK (state IN ('PENDING', 'PROCESSING', 'DONE', 'DEAD')),
    CONSTRAINT ck_event_delivery_attempts CHECK (
        cycle_attempts >= 0 AND lifetime_attempts >= cycle_attempts AND fencing_token >= lifetime_attempts
    ),
    CONSTRAINT ck_event_delivery_lease CHECK (
        (state = 'PROCESSING' AND lease_until IS NOT NULL AND next_attempt_at IS NULL) OR
        (state = 'PENDING' AND lease_until IS NULL AND next_attempt_at IS NOT NULL) OR
        (state IN ('DONE', 'DEAD') AND lease_until IS NULL AND next_attempt_at IS NULL)
    )
);

CREATE TABLE event_replay_audit (
    replay_id BINARY(16) NOT NULL PRIMARY KEY,
    tenant_id BINARY(16) NOT NULL,
    event_id BINARY(16) NOT NULL,
    registration_id BINARY(16) NOT NULL,
    consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(500) NOT NULL,
    recovery_origin VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    prior_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    prior_cycle_attempts INT NOT NULL,
    lifetime_attempts BIGINT NOT NULL,
    prior_fencing_token BIGINT NOT NULL,
    prior_failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    prior_failure_detail VARCHAR(500) NULL,
    CONSTRAINT fk_event_replay_delivery FOREIGN KEY (tenant_id, event_id, registration_id)
        REFERENCES event_deliveries (tenant_id, event_id, registration_id),
    CONSTRAINT ck_event_replay_dead CHECK (prior_state = 'DEAD'),
    CONSTRAINT ck_event_replay_reason CHECK (CHAR_LENGTH(TRIM(reason)) > 0)
);
