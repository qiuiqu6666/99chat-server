-- 在线状态隐私已由 last_active_visibility 替代，删除废弃列
-- 先执行 migrate-last-active-visibility.sql 完成数据迁移
ALTER TABLE users
    DROP COLUMN online_privacy_protection_enabled;
