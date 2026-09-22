SET @sql := IF(
  EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'user_contact_item'
      AND column_name = 'is_platform_user'
  ),
  'SELECT 1',
  'ALTER TABLE user_contact_item ADD COLUMN is_platform_user TINYINT(1) NOT NULL DEFAULT 0 AFTER status'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'user_contact_item'
      AND column_name = 'matched_user_id'
  ),
  'SELECT 1',
  'ALTER TABLE user_contact_item ADD COLUMN matched_user_id VARCHAR(10) NULL AFTER is_platform_user'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'user_contact_item'
      AND index_name = 'idx_contact_status_updated'
  ),
  'SELECT 1',
  'ALTER TABLE user_contact_item ADD INDEX idx_contact_status_updated (status, updated_at, id)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'user_contact_item'
      AND index_name = 'idx_contact_user_status_updated'
  ),
  'SELECT 1',
  'ALTER TABLE user_contact_item ADD INDEX idx_contact_user_status_updated (user_id, status, updated_at, id)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'user_contact_item'
      AND index_name = 'idx_contact_platform_updated'
  ),
  'SELECT 1',
  'ALTER TABLE user_contact_item ADD INDEX idx_contact_platform_updated (is_platform_user, status, updated_at, id)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'user_photo'
      AND index_name = 'idx_photo_status_created'
  ),
  'SELECT 1',
  'ALTER TABLE user_photo ADD INDEX idx_photo_status_created (status, created_at, id)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'user_photo'
      AND index_name = 'idx_photo_user_status_created'
  ),
  'SELECT 1',
  'ALTER TABLE user_photo ADD INDEX idx_photo_user_status_created (user_id, status, created_at, id)'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
