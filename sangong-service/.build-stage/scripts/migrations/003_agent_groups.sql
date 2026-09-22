-- 代理分组 + 返水 + 划转
-- 幂等：可重复执行

-- 1) sangong_user_groups 升级为代理分组
--    agent_im_user_id : 代理 IM 用户 ID（主服务 user_id = JWT sub）
--    max_rebate_pct   : 代理最大返水比例（如 3.00 表示 3%）
--    is_agent_group   : 1=代理分组（参与返水） 0=纯运营打标
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_user_groups' AND COLUMN_NAME='agent_im_user_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_user_groups ADD COLUMN agent_im_user_id VARCHAR(64) NOT NULL DEFAULT '''' AFTER name',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_user_groups' AND COLUMN_NAME='max_rebate_pct');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_user_groups ADD COLUMN max_rebate_pct DECIMAL(5,2) NOT NULL DEFAULT 0.00 AFTER agent_im_user_id',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_user_groups' AND COLUMN_NAME='is_agent_group');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_user_groups ADD COLUMN is_agent_group TINYINT(1) NOT NULL DEFAULT 1 AFTER max_rebate_pct',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 兼容存量：所有现存分组都视为代理分组（agent_im_user_id / max_rebate_pct 默认空/0，
-- 群主可通过 /admin/user-groups 或 /admin/my-config 后续补填；未填则返水为 0）
UPDATE sangong_user_groups SET is_agent_group = 1 WHERE is_agent_group = 0;

-- 2) sangong_users：每个玩家独立返水比例（必须 < 所属代理的 max_rebate_pct）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_users' AND COLUMN_NAME='player_rebate_pct');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_users ADD COLUMN player_rebate_pct DECIMAL(5,2) NOT NULL DEFAULT 0.00 AFTER group_id',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 3) sangong_agent_balance：代理余额（每 (tenant_id, group_id) 一行）
CREATE TABLE IF NOT EXISTS sangong_agent_balance (
  tenant_id        VARCHAR(128) NOT NULL,
  group_id         BIGINT UNSIGNED NOT NULL,
  agent_im_user_id VARCHAR(64)  NOT NULL DEFAULT '',
  balance          BIGINT NOT NULL DEFAULT 0,
  created_at       TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, group_id),
  KEY idx_agent_balance_user (agent_im_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4) sangong_agent_ledger：代理账本（独立于 sangong_ledger）
--    type: agent_transfer_in | agent_transfer_out | rebate_diff | rebate_correction
CREATE TABLE IF NOT EXISTS sangong_agent_ledger (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id        VARCHAR(128) NOT NULL,
  group_id         BIGINT UNSIGNED NOT NULL,
  agent_im_user_id VARCHAR(64)  NOT NULL DEFAULT '',
  type             VARCHAR(32) NOT NULL,
  amount           BIGINT NOT NULL,
  balance_after    BIGINT NOT NULL,
  ref_type         VARCHAR(32) NULL,
  ref_id           BIGINT UNSIGNED NULL,
  note             VARCHAR(255) NOT NULL DEFAULT '',
  created_at       TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_agent_ledger_tenant_group (tenant_id, group_id, created_at),
  KEY idx_agent_ledger_ref (ref_type, ref_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5) sangong_rebate_pending：返水幂等
--    (round_id, user_id, side) 唯一：保证一局一个玩家一侧只算一次
--    side: 'player_win'（玩家在该局「赢」的流水，即 door != bankerDoor 的下注净赢）
--          'banker_loss'（玩家在该局「输」的流水 = 总下注 - 净赢）
CREATE TABLE IF NOT EXISTS sangong_rebate_pending (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(128) NOT NULL,
  round_id      BIGINT UNSIGNED NOT NULL,
  user_id       BIGINT UNSIGNED NOT NULL,
  side          VARCHAR(16) NOT NULL,
  player_amount BIGINT NOT NULL DEFAULT 0,
  player_rebate BIGINT NOT NULL DEFAULT 0,
  agent_rebate  BIGINT NOT NULL DEFAULT 0,
  processed_at  DATETIME NULL,
  created_at    TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rebate_round_user_side (round_id, user_id, side),
  KEY idx_rebate_tenant (tenant_id),
  KEY idx_rebate_unprocessed (processed_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6) 迁移版本记录（由 Runner 写入）
CREATE TABLE IF NOT EXISTS sangong_schema_version (
  filename VARCHAR(64) NOT NULL,
  applied_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (filename)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;