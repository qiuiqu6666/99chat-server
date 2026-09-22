-- group_member: invited_by + join_channel（仅新入群写入，不回填历史）
ALTER TABLE group_member
  ADD COLUMN invited_by VARCHAR(32) NULL COMMENT '邀请人业务 user_id' AFTER muted_until,
  ADD COLUMN join_channel VARCHAR(32) NULL COMMENT 'invite | group_id' AFTER invited_by;
