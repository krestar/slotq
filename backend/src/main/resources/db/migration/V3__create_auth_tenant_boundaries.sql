CREATE TABLE auth_principals (
    id BINARY(16) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE tenant_memberships (
    tenant_id BINARY(16) NOT NULL,
    principal_id BINARY(16) NOT NULL,
    role VARCHAR(16) NOT NULL,
    PRIMARY KEY (tenant_id, principal_id),
    UNIQUE KEY uk_tenant_memberships_tenant_principal_role (tenant_id, principal_id, role),
    CONSTRAINT fk_tenant_memberships_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_tenant_memberships_principal
        FOREIGN KEY (principal_id) REFERENCES auth_principals (id),
    CONSTRAINT chk_tenant_memberships_role CHECK (role IN ('OWNER', 'MANAGER', 'STAFF'))
) ENGINE = InnoDB;

CREATE TABLE venue_grants (
    tenant_id BINARY(16) NOT NULL,
    principal_id BINARY(16) NOT NULL,
    role VARCHAR(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    PRIMARY KEY (tenant_id, principal_id, venue_id),
    CONSTRAINT fk_venue_grants_membership
        FOREIGN KEY (tenant_id, principal_id, role)
        REFERENCES tenant_memberships (tenant_id, principal_id, role),
    CONSTRAINT fk_venue_grants_venue
        FOREIGN KEY (tenant_id, venue_id) REFERENCES venues (tenant_id, id),
    CONSTRAINT chk_venue_grants_role CHECK (role IN ('MANAGER', 'STAFF'))
) ENGINE = InnoDB;
