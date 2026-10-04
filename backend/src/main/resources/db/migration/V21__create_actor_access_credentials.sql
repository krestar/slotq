-- Auth-owned identities. Opaque secret values are never persisted.
CREATE TABLE auth_access_credentials (
    id BINARY(16) NOT NULL PRIMARY KEY,
    token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    audience VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    principal_id BINARY(16) NOT NULL,
    delegation_id BINARY(16) NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    operation VARCHAR(32) NULL,
    target_id BINARY(16) NULL,
    CONSTRAINT fk_access_credential_principal FOREIGN KEY (principal_id) REFERENCES auth_principals(id),
    CONSTRAINT ck_access_audience CHECK (audience IN ('ORIGINAL','MCP','PRODUCT')),
    CONSTRAINT ck_access_credential_scope CHECK (
        (audience = 'ORIGINAL' AND delegation_id IS NULL AND operation IS NULL AND target_id IS NULL)
        OR (audience = 'MCP' AND delegation_id IS NOT NULL AND operation IS NULL AND target_id IS NULL)
        OR (audience = 'PRODUCT' AND delegation_id IS NOT NULL AND operation IS NOT NULL)
    )
) ENGINE=InnoDB;

CREATE TABLE auth_access_delegations (
    id BINARY(16) NOT NULL PRIMARY KEY,
    original_credential_id BINARY(16) NOT NULL,
    principal_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    profile VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actions VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tools VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    CONSTRAINT fk_delegation_original FOREIGN KEY (original_credential_id) REFERENCES auth_access_credentials(id),
    CONSTRAINT fk_delegation_principal FOREIGN KEY (principal_id) REFERENCES auth_principals(id),
    CONSTRAINT fk_delegation_venue FOREIGN KEY (tenant_id, venue_id) REFERENCES venues(tenant_id, id),
    CONSTRAINT ck_delegation_profile CHECK (profile IN ('CUSTOMER','MANAGEMENT')),
    CONSTRAINT ck_delegation_expiry CHECK (expires_at > issued_at AND expires_at <= issued_at + INTERVAL 15 MINUTE)
) ENGINE=InnoDB;
ALTER TABLE auth_access_credentials ADD CONSTRAINT fk_credential_delegation
    FOREIGN KEY (delegation_id) REFERENCES auth_access_delegations(id);
