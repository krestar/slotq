CREATE TABLE event_observation_projections (
    tenant_id BINARY(16) NOT NULL,
    event_id BINARY(16) NOT NULL,
    registration_id BINARY(16) NOT NULL,
    consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT NOT NULL,
    aggregate_type VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    slot_inventory_id BINARY(16) NOT NULL,
    from_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    -- NULL for DB-direct targets; never substitute projection time for durable Kafka intake.
    intaken_at DATETIME(6) NULL,
    projected_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, event_id, registration_id),
    KEY idx_event_observation_venue_time (tenant_id, venue_id, projected_at, event_id),
    CONSTRAINT fk_event_observation_delivery FOREIGN KEY (tenant_id, event_id, registration_id)
        REFERENCES event_deliveries (tenant_id, event_id, registration_id),
    CONSTRAINT chk_event_observation_consumer CHECK (consumer_id = 'operations.event-observation'),
    CONSTRAINT chk_event_observation_route CHECK (
        (event_type = 'booking.capacity-released' AND schema_version = 1
            AND aggregate_type = 'Reservation' AND from_state IS NOT NULL AND to_state IS NOT NULL AND (
                (from_state = 'HELD' AND to_state IN ('CANCELLED', 'EXPIRED'))
                OR (from_state = 'CONFIRMED' AND to_state IN ('CANCELLED', 'NO_SHOW'))
                OR (from_state = 'CHECKED_IN' AND to_state = 'COMPLETED')
            ))
        OR (event_type = 'waitlist.promotion-requested' AND schema_version = 1
            AND aggregate_type = 'SlotInventory' AND aggregate_id = slot_inventory_id
            AND from_state IS NULL AND to_state IS NULL)
    )
) ENGINE = InnoDB;
