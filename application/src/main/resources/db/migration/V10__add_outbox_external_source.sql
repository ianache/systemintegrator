ALTER TABLE integration_outbox
    ADD COLUMN external_source VARCHAR(100) NULL AFTER topic;
