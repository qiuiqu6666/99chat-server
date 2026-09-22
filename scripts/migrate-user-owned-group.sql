-- 用户作为群主拥有的群（Work / Community 建群上限统计）
CREATE TABLE IF NOT EXISTS user_owned_group (
  group_id        VARCHAR(128) NOT NULL PRIMARY KEY,
  owner_user_id   VARCHAR(32)  NOT NULL,
  group_type      VARCHAR(32)  NOT NULL COMMENT 'Work / Community',
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_owned_user_type (owner_user_id, group_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
