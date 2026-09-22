-- 单笔上下分流水（player.updown.recorded）
-- Usage: mysql -h127.0.0.1 -ujiqiren -p jiqiren < robot-service/sql/migrate-robot-player-updown.sql

CREATE TABLE IF NOT EXISTS robot_player_updown_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(255) NOT NULL,
    player_group_id VARCHAR(255) NOT NULL,
    statistics_group_id VARCHAR(255) NOT NULL,
    database_generation VARCHAR(100) NULL,
    record_id VARCHAR(255) NOT NULL,
    wxid VARCHAR(255) NOT NULL,
    player_no VARCHAR(100) NULL,
    nickname VARCHAR(255) NULL,
    direction VARCHAR(10) NOT NULL,
    amount DECIMAL(20,4) NOT NULL,
    balance_delta DECIMAL(20,4) NOT NULL,
    balance_after DECIMAL(20,4) NOT NULL,
    total_up_after DECIMAL(20,4) NOT NULL,
    total_down_after DECIMAL(20,4) NOT NULL,
    approved_at BIGINT NOT NULL,
    business_timestamp BIGINT NOT NULL,
    business_timezone VARCHAR(20) NOT NULL,
    source_updated_at BIGINT NOT NULL,
    sync_reason VARCHAR(100) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_robot_updown_event (event_id),
    UNIQUE KEY uk_robot_updown_record (player_group_id, record_id),
    KEY idx_robot_updown_player (player_group_id, wxid, approved_at),
    KEY idx_robot_updown_time (player_group_id, approved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
