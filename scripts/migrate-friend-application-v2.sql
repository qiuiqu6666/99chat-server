-- 好友申请历史 v2：软删 deleted tombstone

ALTER TABLE friend_application_history
  ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 AFTER status,
  ADD INDEX idx_fah_deleted (user_id, deleted);
