-- IM 消息归档：元数据表 + 当月分表 + 失败/对账日志
-- 执行：mysql -u... -p... chat99 < scripts/migrate-chat-message-init.sql

CREATE TABLE IF NOT EXISTS chat_message_table_registry (
  table_suffix   CHAR(6)      NOT NULL PRIMARY KEY COMMENT 'YYYYMM',
  physical_table VARCHAR(64)  NOT NULL,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message_archive_fail_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  msg_key      VARCHAR(128) NOT NULL,
  payload_json JSON         NOT NULL,
  error_message VARCHAR(512),
  retry_count  INT          NOT NULL DEFAULT 0,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_fail_msg_key (msg_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message_reconcile_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  reconcile_day DATE         NOT NULL,
  im_count      BIGINT       NULL,
  local_count   BIGINT       NULL,
  gap_count     BIGINT       NULL,
  note          VARCHAR(512) NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_reconcile_day (reconcile_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 当月表示例（Provisioner Job 也会自动建表）
CREATE TABLE IF NOT EXISTS chat_message_202606 (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  msg_key         VARCHAR(128) NOT NULL,
  chat_type       TINYINT      NOT NULL COMMENT '0=C2C 1=GROUP',
  from_account    VARCHAR(32)  NOT NULL,
  peer_account    VARCHAR(32)  NULL,
  group_id        VARCHAR(32)  NULL,
  msg_seq         BIGINT       NULL,
  msg_time_ms     BIGINT       NOT NULL,
  elem_type       VARCHAR(32)  NULL,
  preview_text    VARCHAR(512) NULL,
  msg_body_json   JSON         NOT NULL,
  status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1=normal 0=revoked',
  callback_cmd    VARCHAR(64)  NOT NULL,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_msg_key (msg_key),
  KEY idx_c2c_peer_time (chat_type, peer_account, from_account, msg_time_ms),
  KEY idx_group_time (chat_type, group_id, msg_time_ms),
  KEY idx_group_seq (chat_type, group_id, msg_seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO chat_message_table_registry (table_suffix, physical_table)
VALUES ('202606', 'chat_message_202606');
