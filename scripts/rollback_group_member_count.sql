-- ============================================================
-- 回滚脚本：把 group_profile.member_count 还原到 fix 之前
-- 前置：执行过 fix_group_member_count.sql，且事先做了备份表
--       group_profile_member_count_backup(group_id, member_count)
-- ============================================================

UPDATE group_profile gp
JOIN group_profile_member_count_backup b ON b.group_id = gp.group_id
SET gp.member_count = b.member_count;

-- 用完即可丢弃：
-- DROP TABLE group_profile_member_count_backup;