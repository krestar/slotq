CREATE TABLE reservations (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    slot_inventory_id BINARY(16) NOT NULL,
    customer_principal_id BINARY(16) NOT NULL,
    party_size INT UNSIGNED NOT NULL,
    state VARCHAR(16) NOT NULL,
    applied_policy_version BIGINT UNSIGNED NOT NULL,
    starts_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    cancel_allowed_until TIMESTAMP(6) NOT NULL,
    no_show_eligible_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservations_scope_id (tenant_id, venue_id, id),
    UNIQUE KEY uk_reservations_allocation_scope (
        tenant_id, venue_id, resource_id, slot_inventory_id, id
    ),
    KEY idx_reservations_customer (venue_id, customer_principal_id, id),
    KEY idx_reservations_effective_occupancy (
        tenant_id, venue_id, resource_id, slot_inventory_id, state, expires_at
    ),
    CONSTRAINT fk_reservations_slot
        FOREIGN KEY (tenant_id, venue_id, resource_id, slot_inventory_id)
        REFERENCES slot_inventories (tenant_id, venue_id, resource_id, id),
    CONSTRAINT fk_reservations_customer
        FOREIGN KEY (customer_principal_id) REFERENCES auth_principals (id),
    CONSTRAINT fk_reservations_policy
        FOREIGN KEY (tenant_id, venue_id, applied_policy_version)
        REFERENCES booking_policies (tenant_id, venue_id, policy_version),
    CONSTRAINT chk_reservations_party_size CHECK (party_size > 0),
    CONSTRAINT chk_reservations_state CHECK (
        state IN ('HELD', 'CONFIRMED', 'CHECKED_IN', 'EXPIRED', 'CANCELLED', 'NO_SHOW', 'COMPLETED')
    ),
    CONSTRAINT chk_reservations_policy_version CHECK (applied_policy_version > 0),
    CONSTRAINT chk_reservations_expires_at CHECK (expires_at <= starts_at),
    CONSTRAINT chk_reservations_cancel_until CHECK (cancel_allowed_until <= starts_at),
    CONSTRAINT chk_reservations_no_show_at CHECK (no_show_eligible_at >= starts_at)
) ENGINE = InnoDB;

CREATE TABLE capacity_allocations (
    id BINARY(16) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    slot_inventory_id BINARY(16) NOT NULL,
    units INT UNSIGNED NOT NULL DEFAULT 1,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_capacity_allocations_reservation (reservation_id),
    KEY idx_capacity_allocations_effective (
        tenant_id, venue_id, resource_id, slot_inventory_id, active, reservation_id
    ),
    CONSTRAINT fk_capacity_allocations_reservation
        FOREIGN KEY (tenant_id, venue_id, resource_id, slot_inventory_id, reservation_id)
        REFERENCES reservations (tenant_id, venue_id, resource_id, slot_inventory_id, id),
    CONSTRAINT chk_capacity_allocations_units CHECK (units = 1)
) ENGINE = InnoDB;
