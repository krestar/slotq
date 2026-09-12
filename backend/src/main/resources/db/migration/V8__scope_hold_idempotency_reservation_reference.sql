ALTER TABLE hold_idempotency_records
    DROP FOREIGN KEY fk_hold_idempotency_reservation,
    ADD KEY idx_hold_idempotency_reservation_scope (tenant_id, venue_id, reservation_id),
    ADD CONSTRAINT fk_hold_idempotency_reservation_scope
        FOREIGN KEY (tenant_id, venue_id, reservation_id)
        REFERENCES reservations (tenant_id, venue_id, id);
