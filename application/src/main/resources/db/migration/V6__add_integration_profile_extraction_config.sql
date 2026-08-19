ALTER TABLE integration_profile
    ADD COLUMN extraction_config_json JSON NULL AFTER rate_limit_policy_json;
