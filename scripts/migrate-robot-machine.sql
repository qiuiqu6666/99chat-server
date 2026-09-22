-- Robot machine-code multi-tenant schema for jiqiren database.
-- Usage: mysql -h127.0.0.1 -ujiqiren -p jiqiren < scripts/migrate-robot-machine.sql
-- Mirror of robot-service/sql/migrate-robot-machine.sql

CREATE TABLE IF NOT EXISTS robot_machine (
    machine_code VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    label VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at DATETIME NULL,
    PRIMARY KEY (machine_code),
    KEY idx_robot_machine_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS robot_group_binding (
    im_group_id VARCHAR(255) NOT NULL,
    machine_code VARCHAR(64) NOT NULL,
    robot_id VARCHAR(255) NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    bound_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (im_group_id),
    UNIQUE KEY uk_robot_group_machine (machine_code),
    KEY idx_robot_group_enabled (enabled),
    CONSTRAINT fk_robot_group_machine
      FOREIGN KEY (machine_code) REFERENCES robot_machine (machine_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO robot_machine (machine_code, status, label, created_at, last_seen_at)
SELECT DISTINCT player_group_id, 'ACTIVE', 'legacy-seed', NOW(), NOW()
FROM robot_runtime_state
WHERE player_group_id IS NOT NULL AND player_group_id <> '';

INSERT IGNORE INTO robot_machine (machine_code, status, label, created_at, last_seen_at)
SELECT DISTINCT player_group_id, 'ACTIVE', 'legacy-seed', NOW(), NOW()
FROM robot_player_snapshot
WHERE player_group_id IS NOT NULL AND player_group_id <> '';
