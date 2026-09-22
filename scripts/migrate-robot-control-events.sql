-- Robot control events: SYNCING/READY runtime + snapshot generation isolation.
-- Usage: mysql -h127.0.0.1 -ujiqiren -p jiqiren < scripts/migrate-robot-control-events.sql

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET @db := DATABASE();

-- ── robot_runtime_state: sync lifecycle columns ──────────────────────────────

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_runtime_state' AND COLUMN_NAME = 'sync_status') = 0,
    'ALTER TABLE robot_runtime_state ADD COLUMN sync_status VARCHAR(32) NOT NULL DEFAULT ''READY''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_runtime_state' AND COLUMN_NAME = 'player_count') = 0,
    'ALTER TABLE robot_runtime_state ADD COLUMN player_count INT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_runtime_state' AND COLUMN_NAME = 'sync_started_at') = 0,
    'ALTER TABLE robot_runtime_state ADD COLUMN sync_started_at DATETIME(3) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_runtime_state' AND COLUMN_NAME = 'sync_completed_at') = 0,
    'ALTER TABLE robot_runtime_state ADD COLUMN sync_completed_at DATETIME(3) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE robot_runtime_state
SET sync_status = 'READY'
WHERE sync_status IS NULL OR sync_status = '';

-- ── robot_player_snapshot: database_generation isolation ─────────────────────

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND COLUMN_NAME = 'database_generation') = 0,
    'ALTER TABLE robot_player_snapshot ADD COLUMN database_generation VARCHAR(100) NOT NULL DEFAULT ''gen-1''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE robot_player_snapshot s
INNER JOIN robot_runtime_state r ON r.player_group_id = s.player_group_id
SET s.database_generation = r.database_generation
WHERE s.database_generation = 'gen-1'
   OR s.database_generation IS NULL
   OR s.database_generation = '';

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND INDEX_NAME = 'uk_robot_player') > 0,
    'ALTER TABLE robot_player_snapshot DROP INDEX uk_robot_player',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND INDEX_NAME = 'uk_robot_player_gen') = 0,
    'ALTER TABLE robot_player_snapshot ADD UNIQUE KEY uk_robot_player_gen (player_group_id, database_generation, wxid)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'robot_player_snapshot' AND INDEX_NAME = 'idx_robot_player_gen') = 0,
    'ALTER TABLE robot_player_snapshot ADD KEY idx_robot_player_gen (player_group_id, database_generation, active)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
