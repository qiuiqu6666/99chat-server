-- 频道复用 IM Community，业务类型由后端持久化区分。
ALTER TABLE group_profile
  ADD COLUMN is_channel TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=广播频道，0=普通群/超级大群';
