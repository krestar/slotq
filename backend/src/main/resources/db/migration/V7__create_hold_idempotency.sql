CREATE TABLE hold_idempotency_records (
    tenant_id BINARY(16) NOT NULL,
    customer_principal_id BINARY(16) NOT NULL,
    idempotency_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    venue_id BINARY(16) NOT NULL,
    slot_inventory_id BINARY(16) NOT NULL,
    party_size INT UNSIGNED NOT NULL,
    state VARCHAR(16) NOT NULL,
    reservation_id BINARY(16) NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (tenant_id, customer_principal_id, idempotency_key),
    KEY idx_hold_idempotency_cleanup (state, completed_at),
    CONSTRAINT fk_hold_idempotency_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_hold_idempotency_customer
        FOREIGN KEY (customer_principal_id) REFERENCES auth_principals (id),
    CONSTRAINT fk_hold_idempotency_venue
        FOREIGN KEY (tenant_id, venue_id) REFERENCES venues (tenant_id, id),
    CONSTRAINT fk_hold_idempotency_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservations (id),
    CONSTRAINT chk_hold_idempotency_party_size CHECK (party_size > 0),
    CONSTRAINT chk_hold_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT chk_hold_idempotency_completion CHECK (
        (state = 'IN_PROGRESS' AND reservation_id IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND reservation_id IS NOT NULL AND completed_at IS NOT NULL)
    )
) ENGINE = InnoDB;
