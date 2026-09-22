-- 群系统通知 Inbox 游标增量 Sync（可重复执行）
CREATE TABLE IF NOT EXISTS group_notice_inbox_seq (
  id       TINYINT  NOT NULL PRIMARY KEY DEFAULT 1,
  next_val BIGINT   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO group_notice_inbox_seq (id, next_val) VALUES (1, 1);

CREATE TABLE IF NOT EXISTS group_notice_inbox_change (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  seq          BIGINT       NOT NULL,
  user_id      VARCHAR(10)  NOT NULL,
  event_type   VARCHAR(32)  NOT NULL COMMENT 'NOTICE_UPSERTED|NOTICE_DELETED|READ_WATERMARK',
  notice_id    VARCHAR(256) NULL,
  payload_json JSON         NULL,
  created_at   BIGINT       NOT NULL,
  UNIQUE KEY uk_gnic_seq (seq),
  KEY idx_gnic_user_seq (user_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
