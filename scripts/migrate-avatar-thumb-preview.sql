-- 用户头像 thumb/preview 双变体回填
-- 1. users.avatar_url 当前存的是 _preview.jpg,需要拆分:
--    avatar_url <- _thumb.jpg (普通展示)
--    avatar_preview_url <- 原 _preview.jpg (全屏预览)
--    avatar_version <- 0 (历史数据基线)
-- 2. user_friend.friend_avatar_url 同理

-- 幂等: 只处理 avatar_preview_url IS NULL 的行(未回填)

-- Step 1: users 表回填
UPDATE users
SET avatar_preview_url = avatar_url,
    avatar_url = REPLACE(avatar_url, '_preview.jpg', '_thumb.jpg'),
    avatar_version = 0
WHERE avatar_preview_url IS NULL
  AND avatar_url LIKE '%_preview.jpg';

-- Step 2: user_friend 表回填
UPDATE user_friend
SET friend_avatar_preview_url = friend_avatar_url,
    friend_avatar_url = REPLACE(friend_avatar_url, '_preview.jpg', '_thumb.jpg')
WHERE friend_avatar_preview_url IS NULL
  AND friend_avatar_url LIKE '%_preview.jpg';

-- Step 3: group_profile 表已有 avatar_url(thumb) 和 avatar_preview_url(preview),检查是否有遗漏
-- 如果 group_profile.avatar_preview_url 为空但 avatar_url 是 _preview.jpg,回填
UPDATE group_profile
SET avatar_preview_url = avatar_url,
    avatar_url = REPLACE(avatar_url, '_preview.jpg', '_thumb.jpg')
WHERE avatar_preview_url IS NULL
  AND avatar_url LIKE '%_preview.jpg';

-- 验证:检查回填结果
SELECT 'users' AS tbl,
  COUNT(*) AS total,
  SUM(avatar_url LIKE '%_thumb.jpg') AS thumb_count,
  SUM(avatar_preview_url IS NOT NULL) AS preview_filled,
  SUM(avatar_url NOT LIKE '%_thumb.jpg' AND avatar_url NOT LIKE '%default%' AND avatar_url NOT LIKE '%moren%') AS non_thumb_remaining
FROM users
UNION ALL
SELECT 'user_friend',
  COUNT(*),
  SUM(friend_avatar_url LIKE '%_thumb.jpg'),
  SUM(friend_avatar_preview_url IS NOT NULL),
  SUM(friend_avatar_url NOT LIKE '%_thumb.jpg' AND friend_avatar_url IS NOT NULL)
FROM user_friend;
