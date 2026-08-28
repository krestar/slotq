CREATE TABLE tenants (
    id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_tenants_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB;

CREATE TABLE venues (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    timezone VARCHAR(63) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_venues_tenant_id_id (tenant_id, id),
    CONSTRAINT fk_venues_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT chk_venues_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB;

CREATE TABLE venue_operating_hours (
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    day_of_week TINYINT UNSIGNED NOT NULL,
    opens_at TIME(6) NOT NULL,
    closes_at TIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, day_of_week),
    CONSTRAINT fk_venue_operating_hours_venue
        FOREIGN KEY (tenant_id, venue_id) REFERENCES venues (tenant_id, id),
    CONSTRAINT chk_venue_operating_hours_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_venue_operating_hours_range CHECK (opens_at < closes_at)
) ENGINE = InnoDB;

CREATE TABLE booking_policies (
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    policy_version BIGINT UNSIGNED NOT NULL,
    slot_duration_minutes INT UNSIGNED NOT NULL,
    hold_duration_minutes INT UNSIGNED NOT NULL,
    cancellation_cutoff_minutes INT UNSIGNED NOT NULL,
    no_show_grace_minutes INT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id, venue_id, policy_version),
    CONSTRAINT fk_booking_policies_venue
        FOREIGN KEY (tenant_id, venue_id) REFERENCES venues (tenant_id, id),
    CONSTRAINT chk_booking_policies_version CHECK (policy_version > 0),
    CONSTRAINT chk_booking_policies_slot_duration CHECK (slot_duration_minutes > 0),
    CONSTRAINT chk_booking_policies_hold_duration CHECK (hold_duration_minutes > 0)
) ENGINE = InnoDB;
