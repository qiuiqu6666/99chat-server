-- 对齐 user_friend 快照行与 friend_contact_change 增量事件的 item_version。
-- 可重复执行：只提升版本，绝不降低现有值。
UPDATE user_friend f
LEFT JOIN (
    SELECT account_id, peer_user_id, MAX(item_version) AS max_item_version
    FROM friend_contact_change
    GROUP BY account_id, peer_user_id
) c ON BINARY c.account_id = BINARY f.user_id AND BINARY c.peer_user_id = BINARY f.friend_user_id
SET f.item_version = GREATEST(f.item_version, COALESCE(c.max_item_version, 0), 1)
WHERE f.item_version < GREATEST(COALESCE(c.max_item_version, 0), 1);