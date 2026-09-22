-- 添加「添加我时需验证」开关，默认 true（需验证）
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS friend_add_requires_verify TINYINT(1) NOT NULL DEFAULT 1;
