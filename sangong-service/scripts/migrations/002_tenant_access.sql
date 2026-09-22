-- 按账号隔离租户：主服务用户 <-> 租户 访问关系 + 租户水群字段
-- 幂等：可重复执行

-- 1) 租户增加水群字段
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_tenants' AND COLUMN_NAME='im_group_water_id');
SET @sql := IF(@c=0,
  'ALTER TABLE sangong_tenants ADD COLUMN im_group_water_id VARCHAR(128) NOT NULL DEFAULT '''' AFTER im_group_ledger_id',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 2) 账号-租户访问关系
--    main_user_id = 主服务 users.user_id（即 JWT sub / IM 用户 ID）
--    role: owner | admin
CREATE TABLE IF NOT EXISTS sangong_tenant_access (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  main_user_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(128) NOT NULL,
  role VARCHAR(16) NOT NULL DEFAULT 'admin',
  is_default TINYINT(1) NOT NULL DEFAULT 0,
  created_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_access_user_tenant (main_user_id, tenant_id),
  KEY idx_access_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
