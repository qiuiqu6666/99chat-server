-- 在线状态隐私保护：true=不向他人展示在线时间与在线状态
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS online_privacy_protection_enabled TINYINT(1) NOT NULL DEFAULT 0;
