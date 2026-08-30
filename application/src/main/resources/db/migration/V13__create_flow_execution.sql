CREATE TABLE flow_execution (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    flow_id BINARY(16) NOT NULL,
    flow_version_number INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NOT NULL,
    duration_ms BIGINT NOT NULL,
    error_message TEXT NULL,
    PRIMARY KEY (id),
    KEY idx_flow_execution_tenant_started (tenant_id, started_at, duration_ms),
    CONSTRAINT fk_flow_execution_flow FOREIGN KEY (flow_id) REFERENCES flow (id)
);
