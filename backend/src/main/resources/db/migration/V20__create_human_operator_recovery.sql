-- Individually provisioned human identities; no Product role or SystemPrincipal mapping.
CREATE TABLE operations_operators (
    operator_id BINARY(16) NOT NULL PRIMARY KEY,
    principal_reference VARCHAR(160) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE operations_credentials (
    credential_id BINARY(16) NOT NULL PRIMARY KEY,
    operator_id BINARY(16) NOT NULL,
    token_hash BINARY(32) NOT NULL UNIQUE,
    issued_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    CONSTRAINT fk_operations_credential_operator FOREIGN KEY (operator_id)
        REFERENCES operations_operators(operator_id),
    CONSTRAINT ck_operations_credential_expiry CHECK (expires_at > issued_at)
);
CREATE TABLE operations_grants (
    operator_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY(operator_id, tenant_id, consumer_id, action),
    CONSTRAINT fk_operations_grant_operator FOREIGN KEY (operator_id)
        REFERENCES operations_operators(operator_id),
    CONSTRAINT fk_operations_grant_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT ck_operations_grant_action CHECK (action IN ('READ', 'BUSINESS_REPLAY', 'PUBLICATION_RECOVER'))
);
-- An uncommitted reservation serializes concurrent reuse of a globally unique operation ID.
-- Only the complete request/result/audit transaction becomes visible to another request.
CREATE TABLE operations_recovery_operations (
    operation_id BINARY(16) NOT NULL PRIMARY KEY,
    operator_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    exact_request MEDIUMTEXT COLLATE utf8mb4_bin NOT NULL,
    result_json MEDIUMTEXT COLLATE utf8mb4_bin NULL,
    CONSTRAINT fk_operations_recovery_operator FOREIGN KEY (operator_id)
        REFERENCES operations_operators(operator_id),
    CONSTRAINT ck_operations_recovery_request CHECK (JSON_VALID(exact_request)),
    CONSTRAINT ck_operations_recovery_result CHECK (result_json IS NULL OR JSON_VALID(result_json))
);
CREATE TABLE operations_recovery_audit (
    operation_id BINARY(16) NOT NULL PRIMARY KEY,
    operator_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    consumer_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    -- Typed result includes principal reference, exact target, reason, correlation,
    -- expected/prior/post states, attempts/lifetime/fences and affected consumer scopes.
    result_json MEDIUMTEXT COLLATE utf8mb4_bin NOT NULL,
    KEY idx_operations_audit_scope (tenant_id, consumer_id, recorded_at, operation_id),
    CONSTRAINT fk_operations_audit_operation FOREIGN KEY (operation_id)
        REFERENCES operations_recovery_operations(operation_id),
    CONSTRAINT ck_operations_audit_result CHECK (JSON_VALID(result_json))
);
CREATE TRIGGER operations_recovery_audit_no_update BEFORE UPDATE ON operations_recovery_audit
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Human recovery audit is append-only';
CREATE TRIGGER operations_recovery_audit_no_delete BEFORE DELETE ON operations_recovery_audit
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Human recovery audit is append-only';
