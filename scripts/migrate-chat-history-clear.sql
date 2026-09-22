-- 用户会话历史清空水位（仅影响 /me/messages/* 拉取，不删归档消息）
-- 执行：mysql -u... -p... chat99 < scripts/migrate-chat-history-clear.sql

CREATE TABLE IF NOT EXISTS user_chat_history_clear (
  user_id            VARCHAR(32)  NOT NULL,
  chat_type          TINYINT      NOT NULL COMMENT '0=C2C 1=GROUP',
  conv_key           VARCHAR(64)  NOT NULL COMMENT 'C2C=peerUserId GROUP=groupId',
  cleared_before_ms  BIGINT       NOT NULL COMMENT 'msg_time_ms <= 此值的记录对该用户不可见',
  updated_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, chat_type, conv_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
