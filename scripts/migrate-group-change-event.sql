-- 群成员变动审计 + TCP changeEventId 去重
CREATE TABLE IF NOT EXISTS group_change_event (
  change_event_id     VARCHAR(64)   NOT NULL PRIMARY KEY,
  group_id            VARCHAR(128)  NOT NULL,
  action              VARCHAR(64)   NOT NULL,
  operator_user_id    VARCHAR(32)   NULL,
  member_user_ids     JSON          NULL,
  occurred_at         BIGINT        NOT NULL,
  timeline_rank       INT           NULL,
  source              VARCHAR(32)   NOT NULL,
  detail              JSON          NULL,
  created_at          BIGINT        NOT NULL,
  KEY idx_gce_group_occurred (group_id, occurred_at),
  KEY idx_gce_group_action (group_id, action, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
