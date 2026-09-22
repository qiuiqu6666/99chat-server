-- 会话置顶多端同步（per-user 已置顶会话，稀疏表，上限 100）
CREATE TABLE IF NOT EXISTS user_conversation_pin (
  user_id     VARCHAR(64)  NOT NULL,
  chat_type   VARCHAR(16)  NOT NULL,
  peer_id     VARCHAR(128) NOT NULL,
  pinned_at   BIGINT       NOT NULL,
  updated_at  BIGINT       NOT NULL,
  PRIMARY KEY (user_id, chat_type, peer_id),
  KEY idx_ucp_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
