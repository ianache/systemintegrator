CREATE TABLE vehicle (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    vin VARCHAR(100) NOT NULL,
    brand_code VARCHAR(100) NOT NULL,
    model_code VARCHAR(100) NOT NULL,
    model_year INT NOT NULL,
    active BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_vehicle_tenant_vin (tenant_id, vin),
    KEY idx_vehicle_tenant_active (tenant_id, active)
);

CREATE TABLE integration_outbox (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL,
    available_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_pending (status, available_at),
    KEY idx_outbox_tenant (tenant_id)
);

CREATE TABLE integration_inbox (
    event_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL,
    last_error VARCHAR(1000) NULL,
    received_at TIMESTAMP(6) NOT NULL,
    processed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (event_id),
    KEY idx_inbox_tenant_status (tenant_id, status)
);
