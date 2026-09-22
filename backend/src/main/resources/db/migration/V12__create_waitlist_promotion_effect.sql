ALTER TABLE waitlist_entries
    ADD KEY idx_waitlist_entries_promotion (demand_id, state, joined_at, id);

ALTER TABLE waitlist_offers
    ADD UNIQUE KEY uk_waitlist_offers_effect (tenant_id, venue_id, entry_id, reservation_id, id);

CREATE TABLE waitlist_promotion_receipts (
    tenant_id BINARY(16) NOT NULL,
    consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id BINARY(16) NOT NULL,
    signal_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id BINARY(16) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    resource_id BINARY(16) NOT NULL,
    slot_inventory_id BINARY(16) NOT NULL,
    from_state VARCHAR(16) NULL,
    to_state VARCHAR(16) NULL,
    outcome VARCHAR(16) NULL,
    offer_id BINARY(16) NULL,
    entry_id BINARY(16) NULL,
    reservation_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, consumer_id, event_id),
    -- The uncommitted claim intentionally has no business FK lock before Slot serialization.
    -- A receipt is completed only by the joined promotion effect; null outcome must never commit.
    CONSTRAINT fk_promotion_receipt_offer
        FOREIGN KEY (tenant_id, venue_id, entry_id, reservation_id, offer_id)
        REFERENCES waitlist_offers (tenant_id, venue_id, entry_id, reservation_id, id),
    CONSTRAINT chk_promotion_consumer CHECK (consumer_id = 'waitlist.promotion'),
    CONSTRAINT chk_promotion_signal CHECK (
        (signal_type = 'PROMOTION_REQUESTED' AND from_state IS NULL AND to_state IS NULL AND source_id = slot_inventory_id)
        OR (signal_type = 'CAPACITY_RELEASED' AND from_state IS NOT NULL AND to_state IS NOT NULL AND (
            (from_state = 'HELD' AND to_state IN ('CANCELLED', 'EXPIRED'))
            OR (from_state = 'CONFIRMED' AND to_state IN ('CANCELLED', 'NO_SHOW'))
            OR (from_state = 'CHECKED_IN' AND to_state = 'COMPLETED')
        ))
    ),
    CONSTRAINT chk_promotion_result CHECK (
        (outcome IS NULL AND offer_id IS NULL AND entry_id IS NULL AND reservation_id IS NULL)
        OR (outcome IS NOT NULL AND outcome = 'PROMOTED' AND offer_id IS NOT NULL AND entry_id IS NOT NULL AND reservation_id IS NOT NULL)
        OR (outcome IS NOT NULL AND outcome IN ('NO_CAPACITY', 'NO_CANDIDATE', 'NOT_ELIGIBLE', 'SLOT_PAST', 'DEFERRED')
            AND offer_id IS NULL AND entry_id IS NULL AND reservation_id IS NULL)
    )
) ENGINE = InnoDB;

CREATE TABLE waitlist_notification_requests (
    tenant_id BINARY(16) NOT NULL,
    offer_id BINARY(16) NOT NULL,
    request_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    venue_id BINARY(16) NOT NULL,
    entry_id BINARY(16) NOT NULL,
    reservation_id BINARY(16) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (tenant_id, offer_id, request_type),
    CONSTRAINT fk_waitlist_notification_offer
        FOREIGN KEY (tenant_id, venue_id, entry_id, reservation_id, offer_id)
        REFERENCES waitlist_offers (tenant_id, venue_id, entry_id, reservation_id, id),
    CONSTRAINT chk_waitlist_notification_type CHECK (request_type = 'OFFER_AVAILABLE')
) ENGINE = InnoDB;
