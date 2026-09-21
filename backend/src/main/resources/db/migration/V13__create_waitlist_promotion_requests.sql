CREATE TABLE waitlist_promotion_requests (
    tenant_id BINARY(16) NOT NULL,
    slot_inventory_id BINARY(16) NOT NULL,
    last_event_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, slot_inventory_id)
    -- Only an admission mutex and the last identity, not another completion/retry ledger.
    -- No Slot/receipt/event FK: admission must not acquire parent business locks before receipt,
    -- nor event-record locks before event_boundary. Ownership comes from the stored Slot read.
    -- The link and mandatory append commit/rollback together; no independent link commit.
) ENGINE = InnoDB;
