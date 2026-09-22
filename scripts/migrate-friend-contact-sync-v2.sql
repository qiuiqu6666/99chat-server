-- 好友通讯录 v2 sync：item_version + revision + eventId + deleted
-- 与 migrate-friend-contact-sync.sql 配合（双轨，旧表保留）
-- 前向兼容：旧 seq 仍然可用，客户端可读不回写

-- 1) friend_contact_sync_seq 加 revision 分配器（每域独立 revision）
CREATE TABLE IF NOT EXISTS friend_contact_revision_seq (
  id       TINYINT  NOT NULL PRIMARY KEY DEFAULT 1,
  next_val BIGINT   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO friend_contact_revision_seq (id, next_val) VALUES (1, 1);

-- 2) friend_contact_change 加字段
ALTER TABLE friend_contact_change
  ADD COLUMN event_id     VARCHAR(64)   NULL AFTER seq,
  ADD COLUMN item_version BIGINT        NOT NULL DEFAULT 0 AFTER event_id,
  ADD COLUMN revision     BIGINT        NOT NULL DEFAULT 0 AFTER item_version,
  ADD COLUMN deleted      TINYINT(1)    NOT NULL DEFAULT 0 AFTER revision;

UPDATE friend_contact_change
   SET event_id = CONCAT('evt_legacy_', LPAD(id, 20, '0'))
 WHERE event_id IS NULL;

ALTER TABLE friend_contact_change
  MODIFY COLUMN event_id VARCHAR(64) NOT NULL,
  ADD UNIQUE KEY uk_fcc_event_id (event_id);

ALTER TABLE friend_contact_change
  ADD INDEX idx_fcc_account_rev (account_id, revision);

-- 3) user_friend 表加字段（itemVersion + deleted tombstone）
ALTER TABLE user_friend
  ADD COLUMN item_version BIGINT      NOT NULL DEFAULT 0 AFTER status,
  ADD COLUMN deleted      TINYINT(1)  NOT NULL DEFAULT 0 AFTER item_version,
  ADD COLUMN deleted_at   DATETIME(3) NULL AFTER deleted,
  ADD INDEX idx_user_friend_item_ver (user_id, item_version);

-- 4) 历史数据迁移：当前 status=0 的旧行标 deleted=1（视为已删 tombstone）
UPDATE user_friend
   SET deleted = 1, deleted_at = NOW(3)
 WHERE status = 0 AND deleted = 0;
