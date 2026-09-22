-- Phase 3 tombstone 历史数据回填：
--   1) 解散群（group_profile.dismissed=1）的成员行统一标软删（迁移前物理删的行无法追溯，保持现状）
--   2) 已软删列存在但历史值为 0 的解散群成员行 → deleted=1
-- 幂等：可重复执行（WHERE 条件保证只更新未标记的行）。
-- 前置：先执行 migrate-group-sync-v2.sql（加列）。

-- 解散群的成员行：全部标 tombstone（deleted=1 + item_version 起始 1）
UPDATE group_member gm
  JOIN group_profile gp ON gp.group_id = gm.group_id
   SET gm.deleted = 1,
       gm.deleted_at = COALESCE(gp.updated_at, NOW(3)),
       gm.item_version = gm.item_version + 1
 WHERE gp.dismissed = 1
   AND gm.deleted = 0;

-- 验证（人工核对）：
-- SELECT COUNT(*) FROM group_member gm JOIN group_profile gp ON gp.group_id = gm.group_id
--  WHERE gp.dismissed = 1 AND gm.deleted = 0;  -- 期望 0
