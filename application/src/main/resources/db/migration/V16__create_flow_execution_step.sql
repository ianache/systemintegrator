CREATE TABLE flow_execution_step (
    id BINARY(16) NOT NULL,
    flow_execution_id BINARY(16) NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    duration_ms BIGINT NOT NULL,
    error_message TEXT NULL,
    step_order INT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_flow_execution_step_execution (flow_execution_id, step_order),
    CONSTRAINT fk_flow_execution_step_execution FOREIGN KEY (flow_execution_id) REFERENCES flow_execution (id) ON DELETE CASCADE
);
