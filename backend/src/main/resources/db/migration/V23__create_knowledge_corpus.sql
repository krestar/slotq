-- Knowledge-owned authority; indexes/providers are derived and cannot publish.
CREATE TABLE knowledge_documents (
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    document_id BINARY(16) NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    current_version_id BINARY(16) NULL,
    PRIMARY KEY (tenant_id, venue_id, document_id),
    CONSTRAINT fk_knowledge_venue FOREIGN KEY (tenant_id, venue_id) REFERENCES venues(tenant_id, id),
    CONSTRAINT ck_knowledge_document_state CHECK (state IN ('UNPUBLISHED','ACTIVE','WITHDRAWN')),
    CONSTRAINT ck_knowledge_revision CHECK (revision >= 0),
    CONSTRAINT ck_knowledge_current CHECK ((state='ACTIVE' AND current_version_id IS NOT NULL)
        OR (state IN ('UNPUBLISHED','WITHDRAWN') AND current_version_id IS NULL))
) ENGINE=InnoDB;

CREATE TABLE knowledge_versions (
    tenant_id BINARY(16) NOT NULL,
    venue_id BINARY(16) NOT NULL,
    document_id BINARY(16) NOT NULL,
    version_id BINARY(16) NOT NULL,
    content_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id BINARY(16) NOT NULL,
    source_reference VARCHAR(512) NOT NULL,
    source_title VARCHAR(160) NOT NULL,
    visibility VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    staged_revision BIGINT NOT NULL,
    content TEXT NULL,
    PRIMARY KEY (tenant_id, venue_id, document_id, version_id),
    CONSTRAINT fk_knowledge_version_document FOREIGN KEY (tenant_id, venue_id, document_id)
        REFERENCES knowledge_documents(tenant_id, venue_id, document_id),
    CONSTRAINT ck_knowledge_visibility CHECK (visibility IN ('VENUE_PUBLIC','VENUE_OPERATOR')),
    CONSTRAINT ck_knowledge_version_state CHECK (state IN ('STAGED','VALIDATED','FAILED','PUBLISHED','SUPERSEDED','WITHDRAWN')),
    CONSTRAINT ck_knowledge_staged_revision CHECK (staged_revision > 0)
) ENGINE=InnoDB;

ALTER TABLE knowledge_documents ADD CONSTRAINT fk_knowledge_current_version
    FOREIGN KEY (tenant_id, venue_id, document_id, current_version_id)
    REFERENCES knowledge_versions(tenant_id, venue_id, document_id, version_id);
