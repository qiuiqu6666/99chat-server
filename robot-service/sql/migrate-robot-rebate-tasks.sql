-- Robot rebate settlement tasks (stored on player snapshot + runtime state).
-- Usage: mysql -h127.0.0.1 -ujiqiren -p jiqiren < scripts/migrate-robot-rebate-tasks.sql

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS robot_runtime_state (
    player_group_id VARCHAR(255) PRIMARY KEY,
    database_generation VARCHAR(100) NOT NULL,
    sync_status VARCHAR(32) NOT NULL DEFAULT 'READY',
    player_count INT NOT NULL DEFAULT 0,
    sync_started_at DATETIME(3) NULL,
    sync_completed_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @db := DATABASE();

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_request_id') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_request_id VARCHAR(100) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_request_type') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_request_type VARCHAR(20) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_request_status') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_request_status VARCHAR(20) NOT NULL DEFAULT ''NONE''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_lease_token') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_lease_token VARCHAR(100) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_requested_at') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_requested_at DATETIME(3) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_flow_to_consume') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_flow_to_consume DECIMAL(20,4) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_amount') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_amount DECIMAL(20,4) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_expected_balance') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_expected_balance DECIMAL(20,4) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_expected_total_flow') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_expected_total_flow DECIMAL(20,4) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_expected_used_flow') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_expected_used_flow DECIMAL(20,4) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_database_generation') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_database_generation VARCHAR(100) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_result_flow') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_result_flow DECIMAL(20,4) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_result_amount') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_result_amount DECIMAL(20,4) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_result_code') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_result_code VARCHAR(100) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_result_message') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_result_message TEXT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'rebate_retryable') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN rebate_retryable TINYINT(1) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND INDEX_NAME = 'idx_robot_rebate_pull') = 0,
    'ALTER TABLE robot_player_snapshot ADD KEY idx_robot_rebate_pull (player_group_id, rebate_request_status, rebate_requested_at)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO robot_runtime_state (player_group_id, database_generation)
SELECT DISTINCT s.player_group_id, 'gen-1'
FROM robot_player_snapshot s
ON DUPLICATE KEY UPDATE database_generation = database_generation;
