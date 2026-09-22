-- wallet_fee_config / wallet_limit_config：scene 增加 LIVE_TIP（WalletBootstrap 启动种子）
-- MySQL 8.0 兼容，可重复执行

SET @db := DATABASE();

SET @fee_has_live_tip := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db
      AND TABLE_NAME = 'wallet_fee_config'
      AND COLUMN_NAME = 'scene'
      AND COLUMN_TYPE LIKE '%LIVE_TIP%'
);

SET @sql := IF(@fee_has_live_tip = 0,
    'ALTER TABLE wallet_fee_config MODIFY COLUMN scene ENUM(''EXCHANGE'',''RED_PACKET_SEND'',''TRANSFER_PLATFORM'',''WITHDRAW'',''LIVE_TIP'') NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @limit_has_live_tip := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db
      AND TABLE_NAME = 'wallet_limit_config'
      AND COLUMN_NAME = 'scene'
      AND COLUMN_TYPE LIKE '%LIVE_TIP%'
);

SET @sql := IF(@limit_has_live_tip = 0,
    'ALTER TABLE wallet_limit_config MODIFY COLUMN scene ENUM(''EXCHANGE'',''RED_PACKET'',''TRANSFER'',''WITHDRAW'',''LIVE_TIP'') NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
