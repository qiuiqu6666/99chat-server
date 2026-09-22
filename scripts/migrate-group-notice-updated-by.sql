-- 群公告发布人（最近一次修改公告的用户 userId）
ALTER TABLE group_profile
    ADD COLUMN IF NOT EXISTS notice_updated_by VARCHAR(10) NULL;
