-- player.updown.recorded：可选 status / approval_source
-- Usage: mysql -h127.0.0.1 -ujiqiren -p jiqiren < robot-service/sql/migrate-robot-player-updown-status.sql

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET @db := DATABASE();

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_updown_record' AND COLUMN_NAME = 'status') = 0,
    'ALTER TABLE robot_player_updown_record ADD COLUMN status VARCHAR(32) NULL AFTER approved_at',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_updown_record' AND COLUMN_NAME = 'approval_source') = 0,
    'ALTER TABLE robot_player_updown_record ADD COLUMN approval_source VARCHAR(32) NULL AFTER status',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
