CREATE TABLE integration_sync_state (
    profile_id           BINARY(16) NOT NULL,
    last_watermark        TIMESTAMP(6) NULL,
    last_run_started_at   TIMESTAMP(6) NULL,
    last_run_status        VARCHAR(20) NULL,
    last_error             VARCHAR(1000) NULL,
    PRIMARY KEY (profile_id),
    CONSTRAINT fk_sync_state_profile FOREIGN KEY (profile_id) REFERENCES integration_profile(id)
);
