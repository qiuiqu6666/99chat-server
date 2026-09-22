-- 群游戏开关（按群白名单，默认关闭）
ALTER TABLE group_settings
    ADD COLUMN IF NOT EXISTS game_enabled BOOLEAN NOT NULL DEFAULT FALSE;
