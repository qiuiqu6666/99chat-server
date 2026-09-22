-- Contacts snapshot audit for a single account. Read-only.
SET @account_id := 'l2jx8s086x';

-- Current authoritative relations used by /me/friends/snapshot.
SELECT uf.*
FROM user_friend uf
JOIN user u ON u.user_id = uf.friend_user_id AND u.status = 1
WHERE uf.user_id = @account_id
  AND uf.friend_user_id <> @account_id
  AND uf.status = 1
  AND uf.deleted = 0
ORDER BY uf.friend_user_id ASC;

-- This count exactly matches the snapshot item filter.
SELECT COUNT(*) AS current_snapshot_total
FROM user_friend uf
JOIN user u ON u.user_id = uf.friend_user_id AND u.status = 1
WHERE uf.user_id = @account_id
  AND uf.friend_user_id <> @account_id
  AND uf.status = 1
  AND uf.deleted = 0;

-- Difference history is diagnostic only; it is not the snapshot source.
SELECT * FROM friend_contact_change
WHERE account_id = @account_id
ORDER BY revision ASC, seq ASC;

-- Audit duplicates before attempting a unique constraint migration.
SELECT user_id, friend_user_id, COUNT(*) AS duplicate_count,
       MAX(item_version) AS max_item_version, MAX(updated_at) AS latest_updated_at
FROM user_friend
WHERE user_id = @account_id
GROUP BY user_id, friend_user_id
HAVING COUNT(*) > 1;
