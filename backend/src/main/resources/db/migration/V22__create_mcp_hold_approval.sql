-- Auth-owned material attenuation for the existing PRODUCT audience; no raw HOLD key in Auth.
ALTER TABLE auth_access_credentials
    ADD COLUMN hold_party_size INT NULL,
    ADD COLUMN hold_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;

-- MCP consumer-owned approval/intent state, never Product reliability state.
CREATE TABLE mcp_hold_intents (
    id BINARY(16) NOT NULL PRIMARY KEY,
    approver_id BINARY(16) NOT NULL,
    delegation_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    slot_id BINARY(16) NOT NULL,
    party_size INT NOT NULL,
    tool VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    prepared_at DATETIME(6) NOT NULL,
    prepared_expires_at DATETIME(6) NOT NULL,
    first_dispatch_at DATETIME(6) NULL,
    known_reservation_id BINARY(16) NULL,
    CONSTRAINT ck_mcp_hold_material CHECK(party_size > 0 AND tool='reservation.hold' AND action='RESERVATION_WRITE'),
    CONSTRAINT ck_mcp_prepared_expiry CHECK(prepared_expires_at > prepared_at AND prepared_expires_at <= prepared_at + INTERVAL 5 MINUTE),
    CONSTRAINT ck_mcp_first_dispatch CHECK(first_dispatch_at IS NULL OR first_dispatch_at >= prepared_at)
) ENGINE=InnoDB;

CREATE TABLE mcp_hold_confirmations (
    id BINARY(16) NOT NULL PRIMARY KEY,
    intent_id BINARY(16) NOT NULL,
    approved_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_mcp_confirmation_intent FOREIGN KEY(intent_id) REFERENCES mcp_hold_intents(id),
    CONSTRAINT ck_mcp_confirmation_expiry CHECK(expires_at > approved_at AND expires_at <= approved_at + INTERVAL 5 MINUTE)
) ENGINE=InnoDB;
