CREATE TABLE integration_profile (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    business_domain VARCHAR(100) NOT NULL,
    external_source VARCHAR(100) NOT NULL,
    sync_direction VARCHAR(20) NOT NULL,
    source_of_truth VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL,
    active_profile_key TINYINT GENERATED ALWAYS AS (IF(active, 1, NULL)) STORED,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_integration_profile_tenant_active (tenant_id, active),
    KEY idx_integration_profile_tenant_identity_active (tenant_id, business_domain, external_source, active),
    UNIQUE KEY uq_integration_profile_active_identity
        (tenant_id, business_domain, external_source, active_profile_key)
);
