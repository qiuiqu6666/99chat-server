-- Conversation recent projection for IM snapshot (C2C + group).
-- C2C: one row per (user, peer). Group: one row per group (no member fan-out).

CREATE TABLE IF NOT EXISTS user_c2c_conversation_recent (
  user_id          VARCHAR(32)  NOT NULL,
  peer_id          VARCHAR(32)  NOT NULL,
  last_msg_time_ms BIGINT       NOT NULL,
  last_msg_key     VARCHAR(128) NULL,
  last_seq         BIGINT       NULL,
  last_sender      VARCHAR(32)  NULL,
  last_elem_type   VARCHAR(32)  NULL,
  last_preview     VARCHAR(512) NULL,
  updated_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                   ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, peer_id),
  KEY idx_c2c_recent_user_time (user_id, last_msg_time_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS group_conversation_recent (
  group_id         VARCHAR(128) NOT NULL,
  last_msg_time_ms BIGINT       NOT NULL,
  last_msg_key     VARCHAR(128) NULL,
  last_seq         BIGINT       NULL,
  last_sender      VARCHAR(32)  NULL,
  last_elem_type   VARCHAR(32)  NULL,
  last_preview     VARCHAR(512) NULL,
  updated_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                   ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (group_id),
  KEY idx_group_recent_time (last_msg_time_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Optional helper index for Snapshot JOIN (skip if already present).
SET @idx_exists := (
  SELECT COUNT(1) FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'group_member'
    AND index_name = 'idx_group_member_user'
);
SET @sql := IF(@idx_exists = 0,
  'ALTER TABLE group_member ADD INDEX idx_group_member_user (user_id, group_id)',
  'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
