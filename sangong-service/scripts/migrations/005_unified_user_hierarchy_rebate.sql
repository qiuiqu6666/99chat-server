-- 统一用户树、多租户成员、增量流水与返水账户基础结构
-- 幂等：可重复执行

CREATE TABLE IF NOT EXISTS sangong_tenant_users (
  tenant_id      VARCHAR(128) NOT NULL,
  user_id        BIGINT UNSIGNED NOT NULL,
  im_user_id     VARCHAR(64) NOT NULL,
  status         VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at     TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, user_id),
  UNIQUE KEY uk_tenant_im_user (tenant_id, im_user_id),
  KEY idx_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sangong_user_hierarchy (
  tenant_id       VARCHAR(128) NOT NULL,
  user_id         BIGINT UNSIGNED NOT NULL,
  parent_user_id  BIGINT UNSIGNED NULL,
  level_no        INT UNSIGNED NOT NULL DEFAULT 0,
  path            VARCHAR(2048) NOT NULL DEFAULT '',
  is_active       TINYINT(1) NOT NULL DEFAULT 1,
  created_at      TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, user_id),
  KEY idx_hierarchy_parent (tenant_id, parent_user_id, is_active),
  KEY idx_hierarchy_path (tenant_id, path(255)),
  CONSTRAINT chk_hierarchy_not_self CHECK (parent_user_id IS NULL OR parent_user_id <> user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sangong_rebate_turnover (
  id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id         VARCHAR(128) NOT NULL,
  stat_date         DATE NOT NULL,
  user_id           BIGINT UNSIGNED NOT NULL,
  banker_turnover   BIGINT NOT NULL DEFAULT 0,
  player_turnover   BIGINT NOT NULL DEFAULT 0,
  total_turnover    BIGINT NOT NULL DEFAULT 0,
  source_round_count INT UNSIGNED NOT NULL DEFAULT 0,
  created_at        TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_turnover_tenant_date_user (tenant_id, stat_date, user_id),
  KEY idx_turnover_user_date (tenant_id, user_id, stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sangong_rebate_turnover_events (
  tenant_id       VARCHAR(128) NOT NULL,
  round_id        BIGINT UNSIGNED NOT NULL,
  user_id         BIGINT UNSIGNED NOT NULL,
  turnover_type   VARCHAR(16) NOT NULL,
  stat_date       DATE NOT NULL,
  amount          BIGINT NOT NULL,
  reversed_at     DATETIME NULL,
  created_at      TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, round_id, user_id, turnover_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sangong_rebate_accounts (
  tenant_id          VARCHAR(128) NOT NULL,
  user_id            BIGINT UNSIGNED NOT NULL,
  account_type       VARCHAR(32) NOT NULL,
  turnover_total     BIGINT NOT NULL DEFAULT 0,
  turnover_claimed   BIGINT NOT NULL DEFAULT 0,
  amount_total       BIGINT NOT NULL DEFAULT 0,
  amount_claimed     BIGINT NOT NULL DEFAULT 0,
  rebate_pct         DECIMAL(7,4) NOT NULL DEFAULT 0.0000,
  last_claimed_at    DATETIME NULL,
  created_at         TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, user_id, account_type),
  KEY idx_rebate_account_user (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sangong_rebate_ledger (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id          VARCHAR(128) NOT NULL,
  user_id            BIGINT UNSIGNED NOT NULL,
  account_type       VARCHAR(32) NOT NULL,
  source_user_id     BIGINT UNSIGNED NULL,
  source_round_id    BIGINT UNSIGNED NULL,
  turnover_amount    BIGINT NOT NULL DEFAULT 0,
  rebate_pct         DECIMAL(7,4) NOT NULL DEFAULT 0.0000,
  amount             BIGINT NOT NULL,
  claim_type         VARCHAR(32) NOT NULL,
  reference_id       VARCHAR(128) NULL,
  created_at         TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rebate_reference (tenant_id, claim_type, reference_id),
  KEY idx_rebate_ledger_user (tenant_id, user_id, created_at),
  KEY idx_rebate_ledger_source (tenant_id, source_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sangong_rebate_rate_changes (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id          VARCHAR(128) NOT NULL,
  target_user_id     BIGINT UNSIGNED NOT NULL,
  old_rate           DECIMAL(7,4) NOT NULL DEFAULT 0.0000,
  new_rate           DECIMAL(7,4) NOT NULL DEFAULT 0.0000,
  operator_user_id   BIGINT UNSIGNED NOT NULL,
  reason             VARCHAR(255) NOT NULL DEFAULT '',
  created_at         TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_rate_changes_target (tenant_id, target_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sangong_user_transfers (
  id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id          VARCHAR(128) NOT NULL,
  from_user_id       BIGINT UNSIGNED NOT NULL,
  to_user_id         BIGINT UNSIGNED NOT NULL,
  amount             BIGINT NOT NULL,
  transfer_type      VARCHAR(32) NOT NULL DEFAULT 'AGENT_TO_CHILD',
  reference_id       VARCHAR(128) NULL,
  created_at         TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_transfer_reference (tenant_id, reference_id),
  KEY idx_transfer_from (tenant_id, from_user_id, created_at),
  KEY idx_transfer_to (tenant_id, to_user_id, created_at),
  CONSTRAINT chk_transfer_not_self CHECK (from_user_id <> to_user_id),
  CONSTRAINT chk_transfer_positive CHECK (amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 将已有用户登记为当前租户成员；不覆盖已有租户成员状态。
INSERT IGNORE INTO sangong_tenant_users (tenant_id, user_id, im_user_id)
SELECT tenant_id, id, im_user_id
FROM sangong_users;

-- 为现有用户创建根节点，旧 group_id 关系暂不自动推断为用户上下级。
INSERT IGNORE INTO sangong_user_hierarchy (tenant_id, user_id, parent_user_id, level_no, path)
SELECT tenant_id, id, NULL, 0, CONCAT('/', id, '/')
FROM sangong_users;
