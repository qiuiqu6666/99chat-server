-- 普通通知 Token 与 PushKit VoIP Token 独立启停。
ALTER TABLE user_push_token
    ADD COLUMN apns_enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER enabled,
    ADD COLUMN voip_enabled TINYINT(1) NOT NULL DEFAULT 0 AFTER apns_enabled;

-- 普通通知沿用历史 enabled；VoIP 沿用“存在 PushKit token 即可发送”的历史行为。
UPDATE user_push_token
SET apns_enabled = enabled,
    voip_enabled = CASE
        WHEN voip_push_token IS NOT NULL AND TRIM(voip_push_token) <> '' THEN 1
        ELSE 0
    END;

CREATE INDEX idx_push_user_apns_enabled
    ON user_push_token (user_id, apns_enabled);

CREATE INDEX idx_push_user_voip_enabled
    ON user_push_token (user_id, voip_enabled);
