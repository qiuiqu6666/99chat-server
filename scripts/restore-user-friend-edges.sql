-- 恢复 IM 同步误标记为 removed 的好友边（status 0 -> 1）
UPDATE user_friend
SET status = 1,
    updated_at = NOW()
WHERE status = 0;
