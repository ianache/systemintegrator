ALTER TABLE integration_profile
    ADD COLUMN protocol VARCHAR(20) NULL AFTER source_of_truth,
    ADD COLUMN connector VARCHAR(100) NULL AFTER protocol,
    ADD COLUMN adapter VARCHAR(100) NULL AFTER connector,
    ADD COLUMN endpoint VARCHAR(500) NULL AFTER adapter,
    ADD COLUMN credential_ref VARCHAR(255) NULL AFTER endpoint,
    ADD COLUMN mapping_json JSON NULL AFTER credential_ref,
    ADD COLUMN transformation_json JSON NULL AFTER mapping_json,
    ADD COLUMN sync_policy_json JSON NULL AFTER transformation_json,
    ADD COLUMN retry_policy_json JSON NULL AFTER sync_policy_json,
    ADD COLUMN rate_limit_policy_json JSON NULL AFTER retry_policy_json;
