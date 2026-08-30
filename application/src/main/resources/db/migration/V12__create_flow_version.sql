CREATE TABLE flow_version (
    id BINARY(16) NOT NULL,
    flow_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    version_number INT NOT NULL,
    graph JSON NOT NULL,
    state VARCHAR(20) NOT NULL,
    published_by VARCHAR(255) NOT NULL,
    published_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_flow_version_tenant_flow (tenant_id, flow_id),
    UNIQUE KEY uq_flow_version_number (flow_id, version_number),
    CONSTRAINT fk_flow_version_flow FOREIGN KEY (flow_id) REFERENCES flow (id)
);
