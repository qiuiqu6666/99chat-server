-- P0: 一会话一组 + 分组名不可重复
-- 顺序：先洗脏数据，再加列/唯一约束（避免迁移失败）

-- ---------------------------------------------------------------------------
-- 1) 多组成员：同 (user_id, chat_type, peer_id) 只留 updated_at 最大的一行；
--    updated_at 相同则 folder_id 字典序更小的留下。
-- ---------------------------------------------------------------------------
DELETE FROM user_conversation_folder_member
WHERE (user_id, folder_id, chat_type, peer_id) IN (
  SELECT user_id, folder_id, chat_type, peer_id FROM (
    SELECT user_id,
           folder_id,
           chat_type,
           peer_id,
           ROW_NUMBER() OVER (
             PARTITION BY user_id, chat_type, peer_id
             ORDER BY updated_at DESC, folder_id ASC
           ) AS rn
    FROM user_conversation_folder_member
  ) ranked
  WHERE rn > 1
);

-- ---------------------------------------------------------------------------
-- 2) 重名分组：保留 sort_order 更小、再 created_at 更早；其余改名加 (2)/(3)...
-- ---------------------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_ucf_rename;
CREATE TEMPORARY TABLE tmp_ucf_rename AS
SELECT user_id, folder_id, name, rn FROM (
  SELECT user_id,
         folder_id,
         name,
         ROW_NUMBER() OVER (
           PARTITION BY user_id, LOWER(TRIM(name))
           ORDER BY sort_order ASC, created_at ASC, folder_id ASC
         ) AS rn
  FROM user_conversation_folder
) ranked
WHERE rn > 1;

UPDATE user_conversation_folder f
INNER JOIN tmp_ucf_rename t
  ON f.user_id = t.user_id AND f.folder_id = t.folder_id
SET f.name = CONCAT(
      LEFT(TRIM(f.name), GREATEST(0, 64 - CHAR_LENGTH(CONCAT(' (', t.rn, ')')))),
      ' (', t.rn, ')'
    ),
    f.updated_at = UNIX_TIMESTAMP() * 1000;

DROP TEMPORARY TABLE IF EXISTS tmp_ucf_rename;

-- ---------------------------------------------------------------------------
-- 3) name_key：应用层归一化存储（trim + lower），与客户端一致
-- ---------------------------------------------------------------------------
SET @has_name_key := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'user_conversation_folder'
    AND COLUMN_NAME = 'name_key'
);
SET @sql_add_name_key := IF(
  @has_name_key = 0,
  'ALTER TABLE user_conversation_folder ADD COLUMN name_key VARCHAR(64) NULL AFTER name',
  'SELECT 1'
);
PREPARE stmt FROM @sql_add_name_key;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE user_conversation_folder
SET name_key = LOWER(TRIM(name))
WHERE name_key IS NULL OR name_key <> LOWER(TRIM(name));

ALTER TABLE user_conversation_folder
  MODIFY COLUMN name_key VARCHAR(64) NOT NULL;

SET @has_uk_name := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'user_conversation_folder'
    AND INDEX_NAME = 'uk_ucf_user_name_key'
);
SET @sql_uk_name := IF(
  @has_uk_name = 0,
  'ALTER TABLE user_conversation_folder ADD UNIQUE KEY uk_ucf_user_name_key (user_id, name_key)',
  'SELECT 1'
);
PREPARE stmt FROM @sql_uk_name;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 4) 成员唯一：同一会话全局最多一行
-- ---------------------------------------------------------------------------
SET @has_uk_peer := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'user_conversation_folder_member'
    AND INDEX_NAME = 'uk_ucfm_user_peer'
);
SET @sql_uk_peer := IF(
  @has_uk_peer = 0,
  'ALTER TABLE user_conversation_folder_member ADD UNIQUE KEY uk_ucfm_user_peer (user_id, chat_type, peer_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql_uk_peer;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 唯一键建好后删掉同列非唯一索引（若仍存在）
SET @has_old_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'user_conversation_folder_member'
    AND INDEX_NAME = 'idx_ucfm_user_peer'
);
SET @sql_drop_old := IF(
  @has_old_idx > 0,
  'ALTER TABLE user_conversation_folder_member DROP INDEX idx_ucfm_user_peer',
  'SELECT 1'
);
PREPARE stmt FROM @sql_drop_old;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
