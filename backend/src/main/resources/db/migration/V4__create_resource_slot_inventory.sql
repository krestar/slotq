CREATE TABLE resources (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    type VARCHAR(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
    seating_capacity INT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_resources_tenant_venue_id (tenant_id, venue_id, id),
    CONSTRAINT fk_resources_venue
        FOREIGN KEY (tenant_id, venue_id) REFERENCES venues (tenant_id, id),
    CONSTRAINT chk_resources_type CHECK (type = 'TABLE'),
    CONSTRAINT chk_resources_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_resources_seating_capacity CHECK (seating_capacity > 0),
    CONSTRAINT chk_resources_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB;

CREATE TABLE slot_inventories (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    ends_at TIMESTAMP(6) NOT NULL,
    capacity INT UNSIGNED NOT NULL DEFAULT 1,
    applied_policy_version BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_slot_inventories_tenant_venue_resource_id (
        tenant_id, venue_id, resource_id, id
    ),
    UNIQUE KEY uk_slot_inventories_resource_starts_at (tenant_id, resource_id, starts_at),
    KEY idx_slot_inventories_resource_window (tenant_id, venue_id, resource_id, ends_at),
    CONSTRAINT fk_slot_inventories_resource
        FOREIGN KEY (tenant_id, venue_id, resource_id)
        REFERENCES resources (tenant_id, venue_id, id),
    CONSTRAINT fk_slot_inventories_policy
        FOREIGN KEY (tenant_id, venue_id, applied_policy_version)
        REFERENCES booking_policies (tenant_id, venue_id, policy_version),
    CONSTRAINT chk_slot_inventories_time_range CHECK (starts_at < ends_at),
    CONSTRAINT chk_slot_inventories_capacity CHECK (capacity = 1),
    CONSTRAINT chk_slot_inventories_policy_version CHECK (applied_policy_version > 0)
) ENGINE = InnoDB;
