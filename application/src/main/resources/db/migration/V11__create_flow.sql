CREATE TABLE flow (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    code VARCHAR(150) NOT NULL,
    name VARCHAR(200) NOT NULL,
    draft_graph JSON NULL,
    trigger_summary VARCHAR(100) NULL,
    active_version_number INT NULL,
    archived BOOLEAN NOT NULL,
    active_code_key TINYINT GENERATED ALWAYS AS (IF(archived, NULL, 1)) STORED,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_flow_tenant_archived (tenant_id, archived),
    UNIQUE KEY uq_flow_active_code (tenant_id, code, active_code_key)
);
