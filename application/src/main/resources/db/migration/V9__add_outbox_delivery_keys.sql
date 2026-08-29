CREATE TABLE integration_outbox_delivery_key (
    delivery_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    outbox_id BINARY(16) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (delivery_id),
    KEY idx_outbox_delivery_tenant (tenant_id, delivery_id),
    KEY idx_outbox_delivery_outbox (outbox_id),
    CONSTRAINT fk_outbox_delivery_event
        FOREIGN KEY (outbox_id) REFERENCES integration_outbox (id)
        ON DELETE CASCADE
);
