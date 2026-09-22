-- Agent report export tasks for jiqiren database.
-- Usage: mysql -h127.0.0.1 -ujiqiren -p jiqiren < scripts/migrate-agent-report-export.sql

CREATE TABLE IF NOT EXISTS agent_report_export_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_no VARCHAR(100) NOT NULL,
    requester_user_id VARCHAR(100) NOT NULL,
    player_group_id VARCHAR(255) NOT NULL,
    agent_player_no VARCHAR(100) NULL,
    agent_name VARCHAR(255) NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    file_type VARCHAR(20) NOT NULL,
    include_agent_detail TINYINT(1) NOT NULL DEFAULT 0,
    include_player_detail TINYINT(1) NOT NULL DEFAULT 0,
    task_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    progress INT NOT NULL DEFAULT 0,
    file_path VARCHAR(500) NULL,
    file_name VARCHAR(255) NULL,
    file_size BIGINT NULL,
    content_type VARCHAR(100) NULL,
    row_count INT NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    expires_at DATETIME(3) NULL,
    UNIQUE KEY uk_agent_export_task_no (task_no),
    KEY idx_agent_export_user_time (requester_user_id, created_at),
    KEY idx_agent_export_status (task_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
