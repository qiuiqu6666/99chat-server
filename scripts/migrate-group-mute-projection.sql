-- 群禁言投影：mute-status / muted 列表读本地，不打 IM 热路径
-- muted_until: Unix 秒；NULL 或 0 = 未禁言；> now 为仍在禁言
-- shut_up_all: 对应 IM ShutUpAllMember On/Off

ALTER TABLE group_member
  ADD COLUMN muted_until BIGINT NULL COMMENT 'Unix sec; null/0=not muted' AFTER name_card;

ALTER TABLE group_profile
  ADD COLUMN shut_up_all TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'IM ShutUpAllMember' AFTER dismissed;

CREATE INDEX idx_group_member_muted ON group_member (group_id, muted_until);
