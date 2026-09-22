CREATE TABLE IF NOT EXISTS admin_user_generation_tasks (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_no VARCHAR(64) NOT NULL,
  created_by VARCHAR(100) NOT NULL,
  requested_count INT NOT NULL,
  processed_count INT NOT NULL DEFAULT 0,
  success_count INT NOT NULL DEFAULT 0,
  fail_count INT NOT NULL DEFAULT 0,
  status VARCHAR(30) NOT NULL,
  sex VARCHAR(10) NULL,
  password_ciphertext VARCHAR(1000) NULL,
  last_error VARCHAR(1000) NULL,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  expires_at DATETIME(6) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_augt_task_no (task_no),
  KEY idx_augt_status_created (status, created_at),
  KEY idx_augt_created_by (created_by, created_at),
  KEY idx_augt_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_user_generation_items (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  item_index INT NOT NULL,
  nickname VARCHAR(100) NOT NULL,
  status VARCHAR(20) NOT NULL,
  user_uid VARCHAR(64) NULL,
  trx_address VARCHAR(128) NULL,
  deposit_address VARCHAR(128) NULL,
  usdt_contract VARCHAR(128) NULL,
  min_deposit_usdt VARCHAR(40) NULL,
  error_code VARCHAR(100) NULL,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_augi_task_index (task_id, item_index),
  KEY idx_augi_task_status (task_id, status),
  CONSTRAINT fk_augi_task FOREIGN KEY (task_id)
    REFERENCES admin_user_generation_tasks (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE admin_user_generation_tasks
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE admin_user_generation_items
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
