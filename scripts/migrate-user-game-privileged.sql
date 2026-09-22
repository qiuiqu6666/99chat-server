-- 游戏特权用户（按用户白名单，默认关闭）
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS game_privileged BOOLEAN NOT NULL DEFAULT FALSE;
