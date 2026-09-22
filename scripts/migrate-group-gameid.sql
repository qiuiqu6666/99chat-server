-- 群游戏 ID（IM AppDefinedData /gameid，空串 = 未绑定）
ALTER TABLE group_profile
    ADD COLUMN gameid VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE group_settings
    ADD COLUMN gameid VARCHAR(128) NOT NULL DEFAULT '';
