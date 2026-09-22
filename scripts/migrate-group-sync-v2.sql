-- 群域 v2 sync：itemVersion + revision + eventId + deleted
-- 与现有 migrate-wallet-deposit.sql 风格一致；前向兼容（默认 0）。

-- 1) 群域全局 revision 分配器（每张变更表一个）
CREATE TABLE IF NOT EXISTS group_member_revision_seq (
  id       TINYINT  NOT NULL PRIMARY KEY DEFAULT 1,
  next_val BIGINT   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO group_member_revision_seq (id, next_val) VALUES (1, 1);

CREATE TABLE IF NOT EXISTS group_change_revision_seq (
  id       TINYINT  NOT NULL PRIMARY KEY DEFAULT 1,
  next_val BIGINT   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO group_change_revision_seq (id, next_val) VALUES (1, 1);

-- 注意：group_change_event 和 group_notice_inbox_change 复用 group_member_revision_seq 也可，
-- 但为清晰语义各自分配。下面按各自 seq 表写。

-- 2) group_member_change 加字段
ALTER TABLE group_member_change
  ADD COLUMN event_id     VARCHAR(64)   NULL AFTER seq,
  ADD COLUMN item_version BIGINT        NOT NULL DEFAULT 0 AFTER event_id,
  ADD COLUMN revision     BIGINT        NOT NULL DEFAULT 0 AFTER item_version,
  ADD COLUMN deleted      TINYINT(1)    NOT NULL DEFAULT 0 AFTER revision;

UPDATE group_member_change
   SET event_id = CONCAT('gmevt_legacy_', LPAD(id, 20, '0'))
 WHERE event_id IS NULL;

ALTER TABLE group_member_change
  MODIFY COLUMN event_id VARCHAR(64) NOT NULL,
  ADD UNIQUE KEY uk_gmc_event_id (event_id),
  ADD INDEX idx_gmc_group_rev (group_id, revision);

-- 3) group_change_event 加字段（已有 change_event_id，缺 item_version / revision）
ALTER TABLE group_change_event
  ADD COLUMN item_version BIGINT NOT NULL DEFAULT 0 AFTER change_event_id,
  ADD COLUMN revision     BIGINT NOT NULL DEFAULT 0 AFTER item_version,
  ADD INDEX idx_gce_group_rev (group_id, revision);

-- 4) group_notice_inbox_change 加字段
ALTER TABLE group_notice_inbox_change
  ADD COLUMN event_id     VARCHAR(64)   NULL AFTER seq,
  ADD COLUMN item_version BIGINT        NOT NULL DEFAULT 0 AFTER event_id,
  ADD COLUMN revision     BIGINT        NOT NULL DEFAULT 0 AFTER item_version,
  ADD COLUMN deleted      TINYINT(1)    NOT NULL DEFAULT 0 AFTER revision;

UPDATE group_notice_inbox_change
   SET event_id = CONCAT('gnievt_legacy_', LPAD(id, 20, '0'))
 WHERE event_id IS NULL;

ALTER TABLE group_notice_inbox_change
  MODIFY COLUMN event_id VARCHAR(64) NOT NULL,
  ADD UNIQUE KEY uk_gnic_event_id (event_id),
  ADD INDEX idx_gnic_user_rev (user_id, revision);

-- 5) group_profile 加 revision（每次写群展示字段 +1）
ALTER TABLE group_profile
  ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 AFTER avatar_version;

-- 6) group_member 表加软删字段（Phase 2 暂不改造删除逻辑，Phase 3 才用）
-- 这里只做字段预留，不影响现有删除物理行为。
ALTER TABLE group_member
  ADD COLUMN deleted     TINYINT(1)  NOT NULL DEFAULT 0,
  ADD COLUMN deleted_at  DATETIME(3) NULL,
  ADD INDEX idx_gm_gid_uid_deleted (group_id, user_id, deleted);
