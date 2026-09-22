-- 最后上线时间可见性：everyone | friends_only | hidden
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS last_active_visibility VARCHAR(20) NOT NULL DEFAULT 'everyone';

UPDATE users
SET last_active_visibility = 'hidden'
WHERE online_privacy_protection_enabled = 1
  AND last_active_visibility = 'everyone';
