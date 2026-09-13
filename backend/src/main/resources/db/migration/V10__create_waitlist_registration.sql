CREATE TABLE waitlist_demands (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    ends_at TIMESTAMP(6) NOT NULL,
    party_size INT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_waitlist_demands_identity (
        tenant_id, venue_id, starts_at, ends_at, party_size
    ),
    UNIQUE KEY uk_waitlist_demands_scope_id (tenant_id, venue_id, id),
    CONSTRAINT fk_waitlist_demands_venue
        FOREIGN KEY (tenant_id, venue_id) REFERENCES venues (tenant_id, id),
    CONSTRAINT chk_waitlist_demands_time_range CHECK (starts_at < ends_at),
    CONSTRAINT chk_waitlist_demands_party_size CHECK (party_size > 0)
) ENGINE = InnoDB;

CREATE TABLE waitlist_entries (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    customer_principal_id BINARY(16) NOT NULL,
    demand_id BINARY(16) NOT NULL,
    joined_at TIMESTAMP(6) NOT NULL,
    state VARCHAR(16) NOT NULL,
    active_membership TINYINT GENERATED ALWAYS AS (
        CASE WHEN state IN ('WAITING', 'OFFERED') THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_waitlist_entries_scope_id (tenant_id, venue_id, id),
    UNIQUE KEY uk_waitlist_entries_owner_scope_id (
        tenant_id, venue_id, customer_principal_id, id
    ),
    UNIQUE KEY uk_waitlist_entries_active_membership (
        tenant_id, customer_principal_id, demand_id, active_membership
    ),
    KEY idx_waitlist_entries_customer_order (
        tenant_id, venue_id, customer_principal_id, joined_at, id
    ),
    KEY idx_waitlist_entries_venue_order (tenant_id, venue_id, joined_at, id),
    CONSTRAINT fk_waitlist_entries_demand
        FOREIGN KEY (tenant_id, venue_id, demand_id)
        REFERENCES waitlist_demands (tenant_id, venue_id, id),
    CONSTRAINT fk_waitlist_entries_customer
        FOREIGN KEY (customer_principal_id) REFERENCES auth_principals (id),
    CONSTRAINT chk_waitlist_entries_state CHECK (
        state IN ('WAITING', 'OFFERED', 'FULFILLED', 'DECLINED', 'EXPIRED', 'CANCELLED')
    )
) ENGINE = InnoDB;

CREATE TABLE waitlist_registration_requests (
    tenant_id BINARY(16) NOT NULL,
    customer_principal_id BINARY(16) NOT NULL,
    idempotency_key BINARY(16) NOT NULL,
    claim_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    slot_inventory_id BINARY(16) NOT NULL,
    party_size INT UNSIGNED NOT NULL,
    state VARCHAR(16) NOT NULL,
    entry_id BINARY(16) NULL,
    original_status SMALLINT UNSIGNED NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (tenant_id, customer_principal_id, idempotency_key),
    KEY idx_waitlist_registration_entry_scope (
        tenant_id, venue_id, customer_principal_id, entry_id
    ),
    CONSTRAINT fk_waitlist_registration_customer
        FOREIGN KEY (customer_principal_id) REFERENCES auth_principals (id),
    CONSTRAINT fk_waitlist_registration_slot
        FOREIGN KEY (tenant_id, venue_id, resource_id, slot_inventory_id)
        REFERENCES slot_inventories (tenant_id, venue_id, resource_id, id),
    CONSTRAINT fk_waitlist_registration_entry
        FOREIGN KEY (tenant_id, venue_id, customer_principal_id, entry_id)
        REFERENCES waitlist_entries (tenant_id, venue_id, customer_principal_id, id),
    CONSTRAINT chk_waitlist_registration_party_size CHECK (party_size > 0),
    CONSTRAINT chk_waitlist_registration_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT chk_waitlist_registration_completion CHECK (
        (state = 'IN_PROGRESS' AND entry_id IS NULL AND original_status IS NULL
            AND completed_at IS NULL)
        OR
        (state = 'COMPLETED' AND entry_id IS NOT NULL
            AND original_status IN (200, 201) AND completed_at IS NOT NULL)
    )
) ENGINE = InnoDB;
