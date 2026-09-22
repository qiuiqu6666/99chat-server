-- 大附件视频时长：探测来源与探测时间。
-- duration_ms / width / height / metadata_provenance 已在 migrate-chat-attachment.sql 中。
-- 已有历史附件不回填时长。

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'chat_attachment'
    AND COLUMN_NAME = 'media_probe_source'
);
SET @sql := IF(
  @exists = 0,
  'ALTER TABLE chat_attachment ADD COLUMN media_probe_source VARCHAR(16) NULL AFTER metadata_provenance',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'chat_attachment'
    AND COLUMN_NAME = 'media_probed_at'
);
SET @sql := IF(
  @exists = 0,
  'ALTER TABLE chat_attachment ADD COLUMN media_probed_at DATETIME(3) NULL AFTER media_probe_source',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE chat_attachment
SET media_probe_source = metadata_provenance
WHERE media_probe_source IS NULL
  AND metadata_provenance IS NOT NULL;
