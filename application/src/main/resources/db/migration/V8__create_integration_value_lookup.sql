CREATE TABLE integration_value_lookup (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    external_source VARCHAR(100) NOT NULL,
    catalog_code VARCHAR(100) NOT NULL,
    source_value VARCHAR(255) NOT NULL,
    target_value VARCHAR(255) NOT NULL,
    description VARCHAR(255) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_lookup_tenant_source_catalog (tenant_id, external_source, catalog_code),
    UNIQUE KEY uq_lookup_tenant_source_catalog_value (tenant_id, external_source, catalog_code, source_value)
);
