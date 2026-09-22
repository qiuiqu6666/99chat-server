-- 回填 last_active_at：优先最近成功登录 > 设备最后登录 > 注册时间
UPDATE users u
LEFT JOIN (
    SELECT user_id, MAX(created_at) AS last_login
    FROM login_log
    WHERE success = 1 AND user_id IS NOT NULL
    GROUP BY user_id
) ll ON ll.user_id = u.user_id
LEFT JOIN (
    SELECT user_id, MAX(last_login_at) AS last_device_login
    FROM user_device
    GROUP BY user_id
) ud ON ud.user_id = u.user_id
SET u.last_active_at = COALESCE(ll.last_login, ud.last_device_login, u.created_at),
    u.updated_at = NOW()
WHERE u.status = 1
  AND u.last_active_at IS NULL;
