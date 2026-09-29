-- Original target provenance is independent of broker coordinates and remains stable on redelivery.
CREATE TABLE event_kafka_target_intakes (
    tenant_id BINARY(16) NOT NULL,
    event_id BINARY(16) NOT NULL,
    registration_id BINARY(16) NOT NULL,
    consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    partition_id INT NOT NULL,
    first_offset BIGINT NOT NULL,
    authority_epoch BIGINT NOT NULL,
    first_materialization BOOLEAN NOT NULL,
    intaken_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (tenant_id, event_id, registration_id),
    KEY idx_kafka_target_consumer_time (consumer_id, intaken_at),
    CONSTRAINT fk_kafka_target_delivery FOREIGN KEY (tenant_id, event_id, registration_id)
        REFERENCES event_deliveries (tenant_id, event_id, registration_id),
    CONSTRAINT ck_kafka_target_coordinate CHECK (partition_id >= 0 AND first_offset >= 0 AND authority_epoch > 0)
);

-- A broker coordinate is a transport decision, never a business completion receipt.
CREATE TABLE event_kafka_intake_records (
    consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    partition_id INT NOT NULL,
    record_offset BIGINT NOT NULL,
    record_sha256 BINARY(32) NOT NULL,
    disposition VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id BINARY(16) NULL,
    event_id BINARY(16) NULL,
    registration_id BINARY(16) NULL,
    authority_epoch BIGINT NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    intaken_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (consumer_id, topic, partition_id, record_offset),
    KEY idx_kafka_intake_target (tenant_id, event_id, registration_id),
    KEY idx_kafka_intake_quarantine (disposition, intaken_at),
    CONSTRAINT fk_kafka_intake_target FOREIGN KEY (tenant_id, event_id, registration_id)
        REFERENCES event_deliveries (tenant_id, event_id, registration_id),
    CONSTRAINT ck_kafka_intake_coordinate CHECK (partition_id >= 0 AND record_offset >= 0),
    CONSTRAINT ck_kafka_intake_disposition CHECK (disposition IN ('TARGET', 'NON_TARGET', 'QUARANTINED')),
    CONSTRAINT ck_kafka_intake_meaning CHECK (
        (disposition = 'TARGET' AND tenant_id IS NOT NULL AND event_id IS NOT NULL
            AND registration_id IS NOT NULL AND authority_epoch IS NOT NULL AND failure_code IS NULL) OR
        (disposition = 'NON_TARGET' AND tenant_id IS NULL AND event_id IS NULL
            AND registration_id IS NULL AND authority_epoch IS NULL AND failure_code IS NOT NULL) OR
        (disposition = 'QUARANTINED' AND tenant_id IS NULL AND event_id IS NULL
            AND registration_id IS NULL AND authority_epoch IS NULL AND failure_code IS NOT NULL)
    )
);

-- The MySQL durable prefix is independent of Kafka's committed group position.
CREATE TABLE event_kafka_consumer_positions (
    consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    partition_id INT NOT NULL,
    last_durable_offset BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (consumer_id, topic, partition_id),
    CONSTRAINT ck_kafka_consumer_position CHECK (partition_id >= 0 AND last_durable_offset >= -1)
);
