-- 走法 B：每租户代理实例表
-- 幂等：可重复执行
-- 前置：003_agent_groups.sql 已应用

-- 1) sangong_user_groups：标 is_agent_group=0 的运营分组继续跨租户共用
--    标 is_agent_group=1 的代理分组在 sangong_tenant_agent_groups 里管理
--    不删除字段，避免破坏现存 SELECT *
--    新逻辑：代理相关查询全部走 sangong_tenant_agent_groups
-- 2) sangong_tenant_agent_groups 表：
--    每租户一份代理实例
--    UNIQUE KEY (tenant_id, group_id) — 一个分组在本租户只能有一个代理实例
--    KEY (tenant_id, agent_im_user_id) — 反查"代理在本租户绑了哪些分组"

CREATE TABLE IF NOT EXISTS sangong_tenant_agent_groups (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id       VARCHAR(128) NOT NULL,
  group_id        BIGINT UNSIGNED NOT NULL,
  agent_im_user_id VARCHAR(64)  NOT NULL DEFAULT '',
  max_rebate_pct  DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  is_active       TINYINT(1) NOT NULL DEFAULT 1,
  note            VARCHAR(255) NOT NULL DEFAULT '',
  created_at      TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tag_tenant_group (tenant_id, group_id),
  KEY idx_tag_tenant_agent (tenant_id, agent_im_user_id),
  KEY idx_tag_agent (agent_im_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 3) 数据回填：把现有 sangong_user_groups 中 is_agent_group=1 的行复制到每个租户
--    复制后：agent_im_user_id 与 max_rebate_pct 保留原值；新租户没有对应行则自动建
INSERT INTO sangong_tenant_agent_groups (tenant_id, group_id, agent_im_user_id, max_rebate_pct, is_active, note)
SELECT
  t.tenant_id,
  g.id AS group_id,
  g.agent_im_user_id,
  g.max_rebate_pct,
  g.is_active,
  CONCAT('迁移自 user_groups#', g.id)
FROM sangong_user_groups g
CROSS JOIN sangong_tenants t
WHERE g.is_agent_group = 1
ON DUPLICATE KEY UPDATE
  agent_im_user_id = VALUES(agent_im_user_id),
  max_rebate_pct = VALUES(max_rebate_pct),
  is_active = VALUES(is_active);

-- 4) sangong_user_groups.is_agent_group 仍保留字段，但新代码不再读它
--    不 DROP COLUMN（避免破坏存量 SELECT * 的业务），仅注释
--    实际代理关系全部以 sangong_tenant_agent_groups 为准

-- 5) 索引：让 AgentController.myGroup / my-balance / my-players / proxy-bet 的
--    "按 tenant_id + agent_im_user_id 查分组"能走索引
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sangong_tenant_agent_groups'
    AND INDEX_NAME='idx_tag_tenant_active_agent');
SET @sql := IF(@idx=0,
  'CREATE INDEX idx_tag_tenant_active_agent ON sangong_tenant_agent_groups (tenant_id, agent_im_user_id, is_active)',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;