-- 补齐迁移 005 执行后新增的租户用户根节点；不覆盖已有上下级关系。
INSERT IGNORE INTO sangong_user_hierarchy
  (tenant_id, user_id, parent_user_id, level_no, path, is_active)
SELECT u.tenant_id, u.id, NULL, 0, CONCAT('/', u.id, '/'), 1
FROM sangong_users u
LEFT JOIN sangong_user_hierarchy h
  ON h.tenant_id = u.tenant_id AND h.user_id = u.id
WHERE h.user_id IS NULL;
