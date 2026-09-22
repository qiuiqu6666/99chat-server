-- 提现查单 / Live Activity（MySQL 8.0 兼容，可重复执行）
-- 1) status 增加 CONFIRMING（广播出 tx 后等确认，不再直接 COMPLETED）
-- 2) client_order_id / confirmations / updated_at
-- 3) Live Activity token 表

SET @db := DATABASE();

-- status ENUM 补 CONFIRMING
SET @has_confirming := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_withdrawal'
      AND COLUMN_NAME = 'status' AND COLUMN_TYPE LIKE '%CONFIRMING%'
);
SET @sql := IF(@has_confirming = 0,
    'ALTER TABLE wallet_withdrawal MODIFY COLUMN status ENUM(''PENDING'',''BROADCASTING'',''CONFIRMING'',''COMPLETED'',''FAILED'') NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- client_order_id
SET @c := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_withdrawal' AND COLUMN_NAME = 'client_order_id'
);
SET @sql := IF(@c = 0,
    'ALTER TABLE wallet_withdrawal ADD COLUMN client_order_id VARCHAR(64) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- confirmations
SET @c := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_withdrawal' AND COLUMN_NAME = 'confirmations'
);
SET @sql := IF(@c = 0,
    'ALTER TABLE wallet_withdrawal ADD COLUMN confirmations INT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- updated_at
SET @c := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_withdrawal' AND COLUMN_NAME = 'updated_at'
);
SET @sql := IF(@c = 0,
    'ALTER TABLE wallet_withdrawal ADD COLUMN updated_at DATETIME(6) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE wallet_withdrawal
SET updated_at = COALESCE(completed_at, created_at)
WHERE updated_at IS NULL;

-- 唯一幂等键（MySQL 允许多个 NULL）
SET @c := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_withdrawal' AND INDEX_NAME = 'uk_withdrawal_client_order'
);
SET @sql := IF(@c = 0,
    'CREATE UNIQUE INDEX uk_withdrawal_client_order ON wallet_withdrawal (user_id, client_order_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_withdrawal' AND INDEX_NAME = 'idx_withdrawal_client_order'
);
SET @sql := IF(@c = 0,
    'CREATE INDEX idx_withdrawal_client_order ON wallet_withdrawal (client_order_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS wallet_withdraw_live_activity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    withdrawal_id BIGINT NOT NULL,
    user_id VARCHAR(10) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    activity_id VARCHAR(128) NOT NULL,
    push_token VARCHAR(512) NOT NULL,
    bundle_id VARCHAR(128) NULL,
    environment VARCHAR(32) NULL,
    last_push_at DATETIME(6) NULL,
    last_push_stage VARCHAR(16) NULL,
    last_push_confirmations INT NOT NULL DEFAULT 0,
    last_push_event VARCHAR(8) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wd_live_activity_order (withdrawal_id)
);

-- Phase 2：已有表补推送节流字段
SET @c := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_withdraw_live_activity' AND COLUMN_NAME = 'last_push_at'
);
SET @sql := IF(@c = 0,
    'ALTER TABLE wallet_withdraw_live_activity ADD COLUMN last_push_at DATETIME(6) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_withdraw_live_activity' AND COLUMN_NAME = 'last_push_stage'
);
SET @sql := IF(@c = 0,
    'ALTER TABLE wallet_withdraw_live_activity ADD COLUMN last_push_stage VARCHAR(16) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_withdraw_live_activity' AND COLUMN_NAME = 'last_push_confirmations'
);
SET @sql := IF(@c = 0,
    'ALTER TABLE wallet_withdraw_live_activity ADD COLUMN last_push_confirmations INT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_withdraw_live_activity' AND COLUMN_NAME = 'last_push_event'
);
SET @sql := IF(@c = 0,
    'ALTER TABLE wallet_withdraw_live_activity ADD COLUMN last_push_event VARCHAR(8) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
