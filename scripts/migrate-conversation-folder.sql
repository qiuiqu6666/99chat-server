-- 会话自定义分组多端同步
-- P0：name_key 唯一（同用户分组名 trim+大小写不敏感）；成员 (user_id,chat_type,peer_id) 唯一（一会话一组）
CREATE TABLE IF NOT EXISTS user_conversation_folder (
  user_id     VARCHAR(64)  NOT NULL,
  folder_id   VARCHAR(64)  NOT NULL,
  name        VARCHAR(64)  NOT NULL,
  name_key    VARCHAR(64)  NOT NULL,
  scope       VARCHAR(16)  NOT NULL,
  sort_order  INT          NOT NULL DEFAULT 0,
  updated_at  BIGINT       NOT NULL,
  created_at  BIGINT       NOT NULL,
  PRIMARY KEY (user_id, folder_id),
  UNIQUE KEY uk_ucf_user_name_key (user_id, name_key),
  KEY idx_ucf_user_scope_sort (user_id, scope, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_conversation_folder_member (
  user_id     VARCHAR(64)  NOT NULL,
  folder_id   VARCHAR(64)  NOT NULL,
  chat_type   VARCHAR(16)  NOT NULL,
  peer_id     VARCHAR(128) NOT NULL,
  updated_at  BIGINT       NOT NULL,
  PRIMARY KEY (user_id, folder_id, chat_type, peer_id),
  UNIQUE KEY uk_ucfm_user_peer (user_id, chat_type, peer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
