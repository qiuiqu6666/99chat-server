-- 群资料 / 群成员投影（REST 展示源）
CREATE TABLE IF NOT EXISTS group_profile (
  group_id            VARCHAR(128)  NOT NULL PRIMARY KEY,
  group_type          VARCHAR(32)   NOT NULL,
  group_name          VARCHAR(512)  NOT NULL DEFAULT '',
  display_alias       VARCHAR(128)  NOT NULL DEFAULT '',
  avatar_url          VARCHAR(1024) NULL,
  avatar_preview_url  VARCHAR(1024) NULL,
  notice              TEXT          NULL,
  notice_updated_at   TIMESTAMP(3)  NULL,
  member_count        INT           NOT NULL DEFAULT 0,
  owner_user_id       VARCHAR(32)   NULL,
  dismissed           TINYINT(1)    NOT NULL DEFAULT 0,
  created_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  KEY idx_group_profile_owner (owner_user_id),
  KEY idx_group_profile_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS group_member (
  group_id     VARCHAR(128) NOT NULL,
  user_id      VARCHAR(32)  NOT NULL,
  role         INT          NOT NULL DEFAULT 200,
  name_card    VARCHAR(512) NULL,
  joined_at    TIMESTAMP(3) NULL,
  updated_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (group_id, user_id),
  KEY idx_group_member_user_joined (user_id, joined_at),
  KEY idx_group_member_list (group_id, role, joined_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
