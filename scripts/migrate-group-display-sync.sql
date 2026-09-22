-- 群 Entity 展示增量 Sync（可重复执行）
CREATE TABLE IF NOT EXISTS group_display_seq (
  id       TINYINT  NOT NULL PRIMARY KEY DEFAULT 1,
  next_val BIGINT   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO group_display_seq (id, next_val) VALUES (1, 1);

SET @db := DATABASE();

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='group_change_event' AND COLUMN_NAME='group_seq');
SET @sql := IF(@exists=0,
  'ALTER TABLE group_change_event ADD COLUMN group_seq BIGINT NULL COMMENT ''全局单调展示/变更序号'' AFTER created_at',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='group_change_event' AND INDEX_NAME='uk_gce_group_seq');
SET @sql := IF(@exists=0,
  'ALTER TABLE group_change_event ADD UNIQUE KEY uk_gce_group_seq (group_seq)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='group_change_event' AND INDEX_NAME='idx_gce_seq');
SET @sql := IF(@exists=0,
  'ALTER TABLE group_change_event ADD KEY idx_gce_seq (group_seq)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=@db AND TABLE_NAME='group_profile' AND COLUMN_NAME='avatar_version');
SET @sql := IF(@exists=0,
  'ALTER TABLE group_profile ADD COLUMN avatar_version INT NOT NULL DEFAULT 0 COMMENT ''头像变更版本'' AFTER avatar_preview_url',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
