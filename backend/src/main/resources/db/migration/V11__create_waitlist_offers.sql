ALTER TABLE reservations
    ADD COLUMN promotional_request_id BINARY(16) NULL AFTER no_show_eligible_at,
    ADD COLUMN promotional_confirmed BOOLEAN NOT NULL DEFAULT FALSE AFTER promotional_request_id,
    ADD UNIQUE KEY uk_reservations_promotional_identity (tenant_id, promotional_request_id),
    ADD UNIQUE KEY uk_reservations_offer_scope (
        tenant_id, venue_id, resource_id, slot_inventory_id, customer_principal_id, id
    ),
    ADD CONSTRAINT chk_reservations_promotional_evidence CHECK (
        promotional_confirmed = FALSE OR promotional_request_id IS NOT NULL
    );

ALTER TABLE waitlist_entries
    ADD UNIQUE KEY uk_waitlist_entries_offer_scope (
        tenant_id, venue_id, customer_principal_id, demand_id, id
    );

CREATE TABLE waitlist_offers (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    slot_inventory_id BINARY(16) NOT NULL,
    customer_principal_id BINARY(16) NOT NULL,
    demand_id BINARY(16) NOT NULL,
    entry_id BINARY(16) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    state VARCHAR(16) NOT NULL,
    terminal_reason VARCHAR(32) NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_waitlist_offers_entry (entry_id),
    UNIQUE KEY uk_waitlist_offers_reservation (reservation_id),
    UNIQUE KEY uk_waitlist_offers_scope_id (tenant_id, venue_id, id),
    KEY idx_waitlist_offers_entry_scope (tenant_id, venue_id, customer_principal_id, entry_id),
    CONSTRAINT fk_waitlist_offers_entry
        FOREIGN KEY (tenant_id, venue_id, customer_principal_id, demand_id, entry_id)
        REFERENCES waitlist_entries (tenant_id, venue_id, customer_principal_id, demand_id, id),
    CONSTRAINT fk_waitlist_offers_reservation
        FOREIGN KEY (
            tenant_id, venue_id, resource_id, slot_inventory_id, customer_principal_id, reservation_id
        ) REFERENCES reservations (
            tenant_id, venue_id, resource_id, slot_inventory_id, customer_principal_id, id
        ),
    CONSTRAINT chk_waitlist_offers_state CHECK (
        state IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED')
    ),
    CONSTRAINT chk_waitlist_offers_reason CHECK (
        (state IN ('PENDING', 'ACCEPTED') AND terminal_reason IS NULL)
        OR (state = 'DECLINED' AND terminal_reason IN (
            'CUSTOMER_DECLINED', 'ENTRY_CANCELLED', 'BACKING_CANCELLED'
        ))
        OR (state = 'EXPIRED' AND terminal_reason = 'HOLD_EXPIRED')
    )
) ENGINE = InnoDB;
