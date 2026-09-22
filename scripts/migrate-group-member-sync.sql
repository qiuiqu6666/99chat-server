-- 群成员变动游标增量 Sync（可重复执行）
CREATE TABLE IF NOT EXISTS group_member_sync_seq (
  id       TINYINT  NOT NULL PRIMARY KEY DEFAULT 1,
  next_val BIGINT   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO group_member_sync_seq (id, next_val) VALUES (1, 1);

CREATE TABLE IF NOT EXISTS group_member_change (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  seq          BIGINT       NOT NULL,
  group_id     VARCHAR(128) NOT NULL,
  event_type   VARCHAR(32)  NOT NULL COMMENT 'MEMBER_UPSERTED|MEMBER_REMOVED',
  user_id      VARCHAR(10)  NOT NULL,
  member_count INT          NOT NULL,
  payload_json JSON         NULL,
  created_at   BIGINT       NOT NULL,
  UNIQUE KEY uk_gmc_seq (seq),
  KEY idx_gmc_group_seq (group_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
