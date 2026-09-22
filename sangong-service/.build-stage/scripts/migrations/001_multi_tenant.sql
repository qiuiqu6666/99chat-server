-- 多租户：以 IM 游戏群 ID 为 tenant_id，群间数据互不互通
-- 幂等：可重复执行（已存在列/表会跳过相关步骤需人工确认）

SET @tenant := (
  SELECT TRIM(setting_value) FROM sangong_settings
  WHERE setting_key = 'im_group_game_id' AND TRIM(setting_value) <> ''
  LIMIT 1
);
SET @tenant := IFNULL(NULLIF(@tenant, ''), 'default');

CREATE TABLE IF NOT EXISTS sangong_tenants (
  tenant_id VARCHAR(128) NOT NULL,
  name VARCHAR(128) NOT NULL DEFAULT '',
  im_group_game_id VARCHAR(128) NOT NULL,
  im_group_admin_stats_id VARCHAR(128) NOT NULL DEFAULT '',
  im_group_ledger_id VARCHAR(128) NOT NULL DEFAULT '',
  im_bot_user_id VARCHAR(64) NOT NULL DEFAULT '',
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id),
  UNIQUE KEY uk_tenants_game_group (im_group_game_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sangong_tenants (tenant_id, name, im_group_game_id, im_group_admin_stats_id, im_bot_user_id, active)
SELECT
  @tenant,
  '默认游戏群',
  @tenant,
  IFNULL((SELECT TRIM(setting_value) FROM sangong_settings WHERE setting_key='im_group_admin_stats_id' LIMIT 1), ''),
  IFNULL((SELECT TRIM(setting_value) FROM sangong_settings WHERE setting_key='im_bot_user_id' LIMIT 1), ''),
  1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sangong_tenants WHERE tenant_id = @tenant);

-- sessions
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_sessions' AND COLUMN_NAME='tenant_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_sessions ADD COLUMN tenant_id VARCHAR(128) NOT NULL DEFAULT '''' AFTER id, ADD KEY idx_sessions_tenant_status (tenant_id, status)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE sangong_sessions SET tenant_id = @tenant WHERE tenant_id = '' OR tenant_id IS NULL;

-- users
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_users' AND COLUMN_NAME='tenant_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_users ADD COLUMN tenant_id VARCHAR(128) NOT NULL DEFAULT '''' AFTER id',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE sangong_users SET tenant_id = @tenant WHERE tenant_id = '' OR tenant_id IS NULL;

-- drop global unique on im_user_id if present, add (tenant_id, im_user_id)
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_users' AND INDEX_NAME='sangong_users_im_user_id_unique');
SET @sql := IF(@idx>0, 'ALTER TABLE sangong_users DROP INDEX sangong_users_im_user_id_unique', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_users' AND INDEX_NAME='uk_users_tenant_im');
SET @sql := IF(@idx=0,
  'ALTER TABLE sangong_users ADD UNIQUE KEY uk_users_tenant_im (tenant_id, im_user_id)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ledger
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_ledger' AND COLUMN_NAME='tenant_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_ledger ADD COLUMN tenant_id VARCHAR(128) NOT NULL DEFAULT '''' AFTER id, ADD KEY idx_ledger_tenant (tenant_id)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE sangong_ledger SET tenant_id = @tenant WHERE tenant_id = '' OR tenant_id IS NULL;

-- settings → 复合主键 (tenant_id, setting_key)
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_settings' AND COLUMN_NAME='tenant_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_settings ADD COLUMN tenant_id VARCHAR(128) NOT NULL DEFAULT '''' FIRST',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE sangong_settings SET tenant_id = @tenant WHERE tenant_id = '' OR tenant_id IS NULL;

SET @pk := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_settings' AND INDEX_NAME='PRIMARY' AND COLUMN_NAME='tenant_id');
SET @sql := IF(@pk=0,
  'ALTER TABLE sangong_settings DROP PRIMARY KEY, ADD PRIMARY KEY (tenant_id, setting_key)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- rounds 冗余 tenant_id（报表/查询）
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_rounds' AND COLUMN_NAME='tenant_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_rounds ADD COLUMN tenant_id VARCHAR(128) NOT NULL DEFAULT '''' AFTER id, ADD KEY idx_rounds_tenant (tenant_id)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
UPDATE sangong_rounds r
JOIN sangong_sessions s ON s.id = r.session_id
SET r.tenant_id = s.tenant_id
WHERE r.tenant_id = '' OR r.tenant_id IS NULL;
