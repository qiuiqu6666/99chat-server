-- Sync schema reconciliation v1.
-- Safe to re-run on MySQL 5.7/8.0; run against the intended database.
SET @db := DATABASE();

CREATE TABLE IF NOT EXISTS group_notice_inbox_revision_seq (
  id TINYINT NOT NULL PRIMARY KEY DEFAULT 1,
  next_val BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO group_notice_inbox_revision_seq (id, next_val) VALUES (1, 1);

SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='group_member' AND COLUMN_NAME='item_version');
SET @sql := IF(@n=0, 'ALTER TABLE group_member ADD COLUMN item_version BIGINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @n := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='group_member' AND INDEX_NAME='idx_gm_gid_uid_deleted');
SET @sql := IF(@n=0, 'ALTER TABLE group_member ADD INDEX idx_gm_gid_uid_deleted (group_id,user_id,deleted)', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @n := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='group_member' AND INDEX_NAME='idx_gm_item_ver');
SET @sql := IF(@n=0, 'ALTER TABLE group_member ADD INDEX idx_gm_item_ver (group_id,item_version)', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='friend_application_history' AND COLUMN_NAME='deleted');
SET @sql := IF(@n=0, 'ALTER TABLE friend_application_history ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='friend_application_history' AND COLUMN_NAME='item_version');
SET @sql := IF(@n=0, 'ALTER TABLE friend_application_history ADD COLUMN item_version BIGINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='friend_application_history' AND COLUMN_NAME='deleted_at');
SET @sql := IF(@n=0, 'ALTER TABLE friend_application_history ADD COLUMN deleted_at DATETIME(3) NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @n := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='friend_application_history' AND INDEX_NAME='idx_fah_deleted');
SET @sql := IF(@n=0, 'ALTER TABLE friend_application_history ADD INDEX idx_fah_deleted (user_id,deleted)', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @n := (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='friend_application_history' AND INDEX_NAME='idx_fah_item_ver');
SET @sql := IF(@n=0, 'ALTER TABLE friend_application_history ADD INDEX idx_fah_item_ver (user_id,item_version)', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- Existing v2 deployments must also contain these inbox fields.
SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='group_notice_inbox_change' AND COLUMN_NAME='event_id');
SET @sql := IF(@n=0, 'ALTER TABLE group_notice_inbox_change ADD COLUMN event_id VARCHAR(64) NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='group_notice_inbox_change' AND COLUMN_NAME='item_version');
SET @sql := IF(@n=0, 'ALTER TABLE group_notice_inbox_change ADD COLUMN item_version BIGINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='group_notice_inbox_change' AND COLUMN_NAME='revision');
SET @sql := IF(@n=0, 'ALTER TABLE group_notice_inbox_change ADD COLUMN revision BIGINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @n := (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@db AND TABLE_NAME='group_notice_inbox_change' AND COLUMN_NAME='deleted');
SET @sql := IF(@n=0, 'ALTER TABLE group_notice_inbox_change ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE group_notice_inbox_change SET event_id=CONCAT('gnievt_legacy_',LPAD(id,20,'0')) WHERE event_id IS NULL;

-- user_friend must have one relation row per owner/peer pair.
-- Before running this manually, use audit-contacts-snapshot.sql to confirm no duplicate pairs.
-- Then execute: ALTER TABLE user_friend ADD UNIQUE KEY uk_user_friend (user_id, friend_user_id);
