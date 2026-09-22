-- 红包：status 增加 PENDING / PROCESSING（RedPacketService.send 落库中间态）
-- 原 ENUM 缺 PROCESSING 会导致 insert status=PROCESSING 报 Data truncated，发红包全失败
-- MySQL 8.0 兼容，可重复执行

SET @db := DATABASE();

SET @has_processing := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db
      AND TABLE_NAME = 'wallet_red_packet'
      AND COLUMN_NAME = 'status'
      AND COLUMN_TYPE LIKE '%PROCESSING%'
);

SET @sql := IF(@has_processing = 0,
    'ALTER TABLE wallet_red_packet MODIFY COLUMN status ENUM(''PENDING'',''PROCESSING'',''ACTIVE'',''COMPLETED'',''EXPIRED'',''REFUNDED'') NOT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
