-- 原生视频媒体入口随 API 请求主机持久化，不再写死域名
SET @exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'chat_native_video_message'
    AND COLUMN_NAME = 'media_base_url'
);
SET @sql := IF(
  @exists = 0,
  'ALTER TABLE chat_native_video_message ADD COLUMN media_base_url VARCHAR(255) NULL AFTER fail_code',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
