CREATE TABLE integration_profile (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    business_domain VARCHAR(100) NOT NULL,
    external_source VARCHAR(100) NOT NULL,
    sync_direction VARCHAR(32) NOT NULL,
    source_of_truth VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    active_uniqueness_key TINYINT GENERATED ALWAYS AS (
        CASE WHEN active = 1 THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uq_integration_profile_active_identity
        UNIQUE (tenant_id, business_domain, external_source, active_uniqueness_key),
    INDEX ix_integration_profile_tenant_state (tenant_id, active),
    INDEX ix_integration_profile_tenant_identity_active
        (tenant_id, business_domain, external_source, active)
);
