-- Push & IM chat callback config (app_setting)
-- Run once to seed defaults; runtime reads from DB via PushConfigService

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.enabled', 'false', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.skip_when_online', 'true', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.voip_enabled', 'true', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.enabled', 'true', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.chat_push_enabled', 'true', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.callback_token', '', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.allowed_sdk_app_ids', '', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.chat_push_skip_when_online', 'false', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.skip_sender_ids', 'administrator', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.max_group_members_per_push', '0', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.group_push_agg_seconds_small', '10', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.group_push_agg_seconds_medium', '30', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.group_push_agg_seconds_large', '60', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.group_push_small_group_threshold', '200', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.group_push_large_group_threshold', '2000', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.group_member_cache_ttl_minutes', '10', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.group_push_flush_batch_size', '300', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('im.callback.dedup_ttl_hours', '48', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.jpush_enabled', 'false', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.jpush_app_key', '', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.jpush_master_secret', '', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.jpush_base_url', 'https://api.jpush.cn', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.jpush_third_party_enabled', 'true', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.jpush_huawei_distribution', 'secondary_push', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.jpush_honor_distribution', 'secondary_push', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.jpush_oppo_distribution', 'secondary_push', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.jpush_vivo_distribution', 'secondary_push', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.jpush_xiaomi_distribution', 'secondary_push', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;

INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES
('push.jpush_xiaomi_channel_id', '', NOW())
ON DUPLICATE KEY UPDATE updated_at = updated_at;
