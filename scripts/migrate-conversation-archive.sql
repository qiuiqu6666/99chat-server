-- 会话归档多端同步（per-user 已归档会话）
CREATE TABLE IF NOT EXISTS user_conversation_archive (
  user_id     VARCHAR(64)  NOT NULL,
  chat_type   VARCHAR(16)  NOT NULL,
  peer_id     VARCHAR(128) NOT NULL,
  archived_at BIGINT       NOT NULL,
  updated_at  BIGINT       NOT NULL,
  PRIMARY KEY (user_id, chat_type, peer_id),
  KEY idx_uca_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
