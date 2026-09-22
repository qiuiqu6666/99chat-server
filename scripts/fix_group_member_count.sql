-- ============================================================
-- 一次性数据修复：重算 group_profile.member_count
-- 修复：GroupMemberRepository.countByGroupId 历史上没过滤
--       deleted=true 的 tombstone 行，导致缓存的 member_count
--       比真实活成员数多。
-- 等价 SQL 逻辑（与 GroupMemberRepository.countActiveByGroupId 一致）：
--       COUNT(*) FROM group_member WHERE group_id=? AND deleted=0
-- 幂等：可重跑
-- 回滚：见 /www/wwwroot/99chat-server/scripts/rollback_group_member_count.sql
-- ============================================================

-- 1) 跑前备份（建议 DBA 在生产跑前先手工执行 CREATE TABLE 备份）
-- CREATE TABLE group_profile_member_count_backup AS
--   SELECT group_id, member_count FROM group_profile;

-- 2) 记录跑前总数（用于审计）
SELECT COUNT(*) AS profile_total_before
FROM group_profile
WHERE dismissed = 0;

-- 3) 一次性重算
UPDATE group_profile gp
JOIN (
    SELECT group_id, COUNT(*) AS active_count
    FROM group_member
    WHERE deleted = 0
    GROUP BY group_id
) m ON m.group_id = gp.group_id
SET gp.member_count = m.active_count
WHERE gp.dismissed = 0;

-- 4) 兜底：存在群但没有任何成员记录（理论上不应该，但要补 0）
UPDATE group_profile gp
SET gp.member_count = 0
WHERE gp.dismissed = 0
  AND gp.member_count IS NULL;

-- 5) 跑后核对（关键群 @TGS#_mc2SX4NMM62CZ）
SELECT group_id, group_name, member_count, updated_at
FROM group_profile
WHERE group_id = '@TGS#_mc2SX4NMM62CZ';