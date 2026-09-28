SET @sql := IF(
  EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sync_session'
      AND column_name = 'deleted_count'
  ),
  'SELECT 1',
  'ALTER TABLE sync_session ADD COLUMN deleted_count INT NOT NULL DEFAULT 0 AFTER failed_count'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
  EXISTS(
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'sync_session'
      AND column_name = 'committed_revision'
  ),
  'SELECT 1',
  'ALTER TABLE sync_session ADD COLUMN committed_revision BIGINT NULL AFTER deleted_count'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS user_device_sync_state (
  user_id VARCHAR(10) NOT NULL,
  device_id VARCHAR(64) NOT NULL,
  sync_type VARCHAR(16) NOT NULL,
  last_full_sync_at DATETIME(6) NULL,
  last_incremental_sync_at DATETIME(6) NULL,
  server_revision BIGINT NOT NULL DEFAULT 0,
  baseline_ready TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (user_id, device_id, sync_type)
);

CREATE TABLE IF NOT EXISTS user_contact_source (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(10) NOT NULL,
  device_id VARCHAR(64) NOT NULL,
  local_contact_id VARCHAR(128) NOT NULL,
  contact_item_id BIGINT NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_contact_source (user_id, device_id, local_contact_id),
  KEY idx_contact_source_item (user_id, local_contact_id)
);

CREATE TABLE IF NOT EXISTS contact_sync_batch (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_uuid VARCHAR(36) NOT NULL,
  batch_id VARCHAR(64) NOT NULL,
  payload_hash VARCHAR(128) NOT NULL,
  result_json MEDIUMTEXT NOT NULL,
  uploaded INT NOT NULL,
  skipped INT NOT NULL,
  failed INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_contact_batch (session_uuid, batch_id)
);

CREATE TABLE IF NOT EXISTS contact_sync_staging (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_uuid VARCHAR(36) NOT NULL,
  batch_id VARCHAR(64) NOT NULL,
  local_contact_id VARCHAR(128) NOT NULL,
  fingerprint VARCHAR(64) NOT NULL,
  display_name VARCHAR(256) NULL,
  phones_json TEXT NOT NULL,
  contact_updated_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_contact_staging (session_uuid, local_contact_id)
);

INSERT INTO user_contact_source (
  user_id, device_id, local_contact_id, contact_item_id, fingerprint, created_at, updated_at
)
SELECT c.user_id, 'legacy', c.local_contact_id, c.id, c.fingerprint, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM user_contact_item c
WHERE NOT EXISTS (
  SELECT 1 FROM user_contact_source s
  WHERE s.user_id = c.user_id AND s.device_id = 'legacy' AND s.local_contact_id = c.local_contact_id
);
