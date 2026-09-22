-- Group create / join limit config (app_setting)
-- Run once to seed defaults; runtime reads from DB via GroupCreateLimitConfigService

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('group.create_limit.enabled', 'true', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('group.create_limit.max_community', '3', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('group.join_limit.max', '10000', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('group.join_limit.max_community', '1000', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('group.create_limit.enforce', 'true', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('group.create_limit.log_only', 'false', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('group.create_limit.use_im_count_fallback', 'false', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

-- Legacy key retained in DB but unused for Work create limits:
-- group.create_limit.max_work
