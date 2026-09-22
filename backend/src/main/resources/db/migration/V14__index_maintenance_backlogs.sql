-- Advisory state/keyset scans only; no new capacity authority or business constraint.
CREATE INDEX idx_reservations_maintenance ON reservations (state, id);
CREATE INDEX idx_waitlist_offers_maintenance ON waitlist_offers (state, id);
CREATE INDEX idx_waitlist_entries_maintenance ON waitlist_entries (state, id);
