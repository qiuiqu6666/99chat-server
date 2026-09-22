-- 好友申请历史 v2（追加，需先执行 migrate-friend-application-v2.sql）：
--   item_version（单条实体版本号，软删/复活 +1；与 FriendApplicationRepository.softDelete 配套）
--   deleted_at（软删时间）
-- 幂等：可重复执行（已存在的列会报错，忽略即可）。

ALTER TABLE friend_application_history
  ADD COLUMN item_version BIGINT      NOT NULL DEFAULT 0 AFTER deleted,
  ADD COLUMN deleted_at   DATETIME(3) NULL AFTER item_version,
  ADD INDEX idx_fah_item_ver (user_id, item_version);
