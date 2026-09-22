-- Platform Config Initialization Script
-- Run this SQL to initialize platform configuration in database

-- Insert platform configuration
INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('platform.website', 'https://99chat.example.com', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('platform.email', 'support@99chat.example.com', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('platform.customer_service_url', '', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('platform.version', '1.0.0', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('platform.build', '1', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('platform.download_url', '', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('platform.feedback_prefix', 'feedback/', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('platform.max_feedback_screenshots', '5', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('platform.max_feedback_content_length', '2000', NOW())
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = NOW();
