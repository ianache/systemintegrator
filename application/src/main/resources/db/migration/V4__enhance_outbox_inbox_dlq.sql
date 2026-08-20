ALTER TABLE integration_outbox
    ADD COLUMN topic VARCHAR(150) NULL AFTER event_type;

CREATE INDEX idx_outbox_relay ON integration_outbox (status, available_at, created_at);

ALTER TABLE integration_inbox
    ADD COLUMN payload JSON NULL AFTER event_type;

CREATE INDEX idx_inbox_retry ON integration_inbox (tenant_id, status, attempts);
