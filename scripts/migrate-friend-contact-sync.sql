-- 好友通讯录 Versioned Sync（可重复执行）
-- 全局单调 seq + 按 account_id（通讯录 owner）过滤；游标过期 → HTTP 410 SNAPSHOT_REQUIRED

CREATE TABLE IF NOT EXISTS friend_contact_sync_seq (
  id       TINYINT  NOT NULL PRIMARY KEY DEFAULT 1,
  next_val BIGINT   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO friend_contact_sync_seq (id, next_val) VALUES (1, 1);

CREATE TABLE IF NOT EXISTS friend_contact_change (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  seq          BIGINT       NOT NULL,
  account_id   VARCHAR(64)  NOT NULL COMMENT '通讯录 owner user_id',
  event_type   VARCHAR(40)  NOT NULL COMMENT 'CONTACT_CREATED|CONTACT_DELETED|CONTACT_UPDATED|CONTACT_REMARK_UPDATED|CONTACT_PROFILE_UPDATED',
  peer_user_id VARCHAR(64)  NOT NULL,
  payload_json JSON         NULL,
  created_at   BIGINT       NOT NULL,
  UNIQUE KEY uk_fcc_seq (seq),
  KEY idx_fcc_account_seq (account_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
